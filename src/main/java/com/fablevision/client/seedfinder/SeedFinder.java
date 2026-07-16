package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import com.fablevision.client.FableVisionClient;
import com.fablevision.client.FableVisionConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;

/**
 * The search engine: N background workers pushing seeds through {@link SeedCriteria#test}.
 *
 * Zero network, zero AI — pure local math using the game's own world-gen code. Workers are
 * daemon threads at minimum priority (cores-1 of them) so the game stays smooth; the GUI
 * polls {@link Job} for live stats. One job runs at a time; starting a new one stops the old.
 */
public final class SeedFinder {
   private static volatile Job job;

   /** A found seed plus what was found where ("Village @ 184, -232"). */
   public record Result(long seed, List<String> matches) {}

   public static final class Job {
      public final SeedCriteria criteria;
      public final String summary;
      public final int wantCount;
      /** How many worker threads ("bots") this job runs on. */
      public int threads = 1;
      /** Fast mode: measure from 0,0 instead of the real spawn (much faster, close-ish). */
      public final boolean fast;
      public final List<Result> results = Collections.synchronizedList(new ArrayList<>());
      public final AtomicLong checked = new AtomicLong();
      public final long startedMs = System.currentTimeMillis();
      public volatile long finishedMs;
      public volatile boolean running = true;
      public volatile boolean bootstrapping = true;
      public volatile String error;
      /** Exact-mode "aim RIGHT at spawn": when >0, a hit whose spawn-measured distances are
       *  all within this is taken instantly; a looser (but ≤ radius) hit is parked in
       *  {@link #backup} while workers keep hunting a tight one for a bounded extra window. */
      public final int tightRadius;
      public volatile Result backup;
      public volatile int backupWorst;
      private volatile long backupDeadline;
      final HolderLookup.Provider registries;
      final AtomicLong sequential; // null = random 64-bit seeds

      Job(SeedCriteria criteria, HolderLookup.Provider registries, Long sequentialStart, int wantCount, boolean fast) {
         this.criteria = criteria;
         this.registries = registries;
         this.summary = criteria.summary();
         this.wantCount = Math.max(1, wantCount);
         this.sequential = sequentialStart == null ? null : new AtomicLong(sequentialStart);
         this.fast = fast;
         // Tiering only makes sense for a single Exact-mode result with something actually
         // measured from the overworld spawn (Fast measures from 0,0 by design).
         this.tightRadius = !fast && this.wantCount == 1 && criteria.hasOverworldTarget()
               ? FableVisionConfig.SEED_EXACT_TIGHT : 0;
      }

      public boolean done() {
         return !running;
      }

      /** How long the search ran (frozen once it finishes). */
      public long elapsedMs() {
         return (finishedMs > 0 ? finishedMs : System.currentTimeMillis()) - startedMs;
      }
   }

   public static Job current() {
      return job;
   }

   /** Starts a search (stopping any previous one). {@code sequentialStart} null = random seeds. */
   public static synchronized Job start(SeedCriteria criteria, HolderLookup.Provider registries, Long sequentialStart, int wantCount, boolean fast) {
      stop();
      Job started = new Job(criteria, registries, sequentialStart, wantCount, fast);
      job = started;
      if (registries == null) {
         started.error = "Seed finder needs your own world — open it in singleplayer or use the Create World screen.";
         started.running = false;
         started.bootstrapping = false;
         return started;
      }
      if (criteria.isEmpty()) {
         started.error = "Pick at least one thing to search for.";
         started.running = false;
         started.bootstrapping = false;
         return started;
      }
      // "More bots": use EVERY core. On the menu / Create World screen nothing else needs the
      // CPU so run at normal priority; inside a world keep the SAME core count but at MIN
      // priority, so the render/main threads always win a core when they need it and the game
      // stays smooth while every spare cycle still goes to the (heavy) search.
      boolean inWorld = Minecraft.getInstance().level != null;
      int cores = Runtime.getRuntime().availableProcessors();
      int threads = Math.max(1, cores);
      started.threads = threads;
      for (int i = 0; i < threads; i++) {
         Thread t = new Thread(() -> worker(started), "FableVision-SeedFinder-" + i);
         t.setDaemon(true);
         t.setPriority(inWorld ? Thread.MIN_PRIORITY : Thread.NORM_PRIORITY);
         t.start();
      }
      return started;
   }

   public static void stop() {
      Job current = job;
      if (current != null) {
         current.running = false;
      }
   }

   private static void worker(Job j) {
      WorldgenContext ctx;
      try {
         ctx = WorldgenContext.get(j.registries);
      } catch (Throwable t) {
         FableVisionClient.LOGGER.error("Seed finder bootstrap failed", t);
         j.error = "World-gen bootstrap failed: " + t.getClass().getSimpleName();
         j.running = false;
         return;
      }
      j.bootstrapping = false;

      ThreadLocalRandom random = ThreadLocalRandom.current();
      List<String> lines = new ArrayList<>(4);
      int[] worst = new int[1];
      while (j.running) {
         long seed = j.sequential != null ? j.sequential.getAndIncrement() : random.nextLong();
         lines.clear();
         worst[0] = 0;
         boolean hit;
         try {
            hit = j.criteria.test(seed, ctx, lines, j.fast, worst);
         } catch (Throwable t) {
            FableVisionClient.LOGGER.error("Seed finder crashed while testing seed {}", seed, t);
            j.error = "Search crashed: " + t.getClass().getSimpleName() + " (see log)";
            j.running = false;
            return;
         }
         j.checked.incrementAndGet();
         if (hit && j.tightRadius > 0 && worst[0] > j.tightRadius) {
            // Right size (within the Exact radius) but not RIGHT at spawn — park the best
            // such seed as a backup and keep hunting a tighter one. The extra window starts
            // at the FIRST backup and scales with how long that took (4–15s).
            synchronized (j.results) {
               if (j.results.isEmpty() && (j.backup == null || worst[0] < j.backupWorst)) {
                  if (j.backup == null) {
                     long elapsed = System.currentTimeMillis() - j.startedMs;
                     j.backupDeadline = System.currentTimeMillis() + Math.min(15_000L, Math.max(4_000L, elapsed));
                  }
                  j.backupWorst = worst[0];
                  j.backup = new Result(seed, List.copyOf(lines));
               }
            }
         } else if (hit) {
            synchronized (j.results) {
               if (j.results.size() < j.wantCount) {
                  j.results.add(new Result(seed, List.copyOf(lines)));
               }
               if (j.results.size() >= j.wantCount) {
                  j.running = false;
               }
            }
         }
         // Extra window over with nothing tighter found? Settle for the parked backup.
         if (j.tightRadius > 0 && j.running && j.backup != null && System.currentTimeMillis() > j.backupDeadline) {
            synchronized (j.results) {
               if (j.results.isEmpty()) {
                  j.results.add(j.backup);
               }
               j.running = false;
            }
         }
      }
      // Stopped (user or window) while a backup sat unclaimed — don't waste it, it DID
      // match the wish within the Exact radius; it just wasn't within the tight aim.
      if (j.tightRadius > 0 && j.backup != null) {
         synchronized (j.results) {
            if (j.results.isEmpty()) {
               j.results.add(j.backup);
            }
         }
      }
      if (j.finishedMs == 0) {
         j.finishedMs = System.currentTimeMillis();
      }
   }

   /** "under a second", "14s", "2m 05s" — for the searched-N-seeds lines. */
   public static String prettyMs(long ms) {
      long s = ms / 1000;
      if (s < 1) {
         return "under a second";
      }
      if (s < 120) {
         return s + "s";
      }
      return (s / 60) + "m " + String.format("%02d", s % 60) + "s";
   }

   private SeedFinder() {
   }
}
