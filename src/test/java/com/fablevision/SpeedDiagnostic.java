package com.fablevision;

import com.fablevision.client.seedfinder.SeedCatalog;
import com.fablevision.client.seedfinder.SeedCriteria;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import com.fablevision.client.seedfinder.WorldgenContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Dev-only: measures WHERE the seed finder's per-seed milliseconds actually go and what the
 * real hit rates are for common wishes, over the REAL world-gen code with no game running.
 * Run with {@code gradlew speedDiag}. Prints:
 *  - isolated cost of RandomState.create and findSpawnPosition (the two suspects),
 *  - the real-spawn distance-from-0,0 distribution (how far spawns actually wander),
 *  - single-thread end-to-end throughput + seeds-per-hit for canonical wishes at the new
 *    Exact radius (64) vs the old one (128), plus a Fast-mode contrast,
 *  - a found seed + match lines per wish, so the numbers can be spot-checked in-game
 *    with /locate on that exact seed (ground truth, not just a plausible distribution).
 */
public final class SpeedDiagnostic {
   public static void main(String[] args) {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      List<SeedCriteria.StructureTarget> catalog = SeedCatalog.structures(ctx);

      // ── A) isolated per-seed costs ────────────────────────────────────────
      // Warmup so the JIT doesn't bill its compile time to the first measurement.
      for (long s = 1; s <= 30; s++) {
         ctx.randomState(Dim.OVERWORLD, s).sampler().findSpawnPosition();
      }

      // GROUND TRUTH for the slim (climate-only) RandomState: the game's own spawn pick must
      // be IDENTICAL on slim vs full for every seed — if the slim router changed climate in
      // any way, this catches it (a distribution alone would not — the 1.24.1 lesson).
      int mismatches = 0;
      for (long s = 100; s < 140; s++) {
         BlockPos slim = ctx.randomState(Dim.OVERWORLD, s).sampler().findSpawnPosition();
         BlockPos full = ctx.fullRandomState(Dim.OVERWORLD, s).sampler().findSpawnPosition();
         if (!slim.equals(full)) {
            mismatches++;
            System.out.println("SLIM/FULL MISMATCH seed " + s + ": slim=" + slim + " full=" + full);
         }
      }

      int n = 200;
      RandomState[] states = new RandomState[n];
      long t0 = System.nanoTime();
      for (int i = 0; i < n; i++) {
         states[i] = ctx.randomState(Dim.OVERWORLD, 1_000_000L + i);
      }
      double msSlim = (System.nanoTime() - t0) / 1e6 / n;

      t0 = System.nanoTime();
      for (int i = 0; i < 60; i++) {
         ctx.fullRandomState(Dim.OVERWORLD, 2_000_000L + i);
      }
      double msFull = (System.nanoTime() - t0) / 1e6 / 60;

      long[] dist = new long[n];
      t0 = System.nanoTime();
      for (int i = 0; i < n; i++) {
         BlockPos sp = states[i].sampler().findSpawnPosition();
         dist[i] = Math.round(Math.hypot(sp.getX(), sp.getZ()));
      }
      double msSpawn = (System.nanoTime() - t0) / 1e6 / n;
      Arrays.sort(dist);

      System.out.println("========== SPEED DIAGNOSTIC ==========");
      System.out.println(mismatches == 0 ? "slim == full spawn for 40 seeds : OK (vanilla-exact)"
            : "!!! slim RandomState DIVERGES on " + mismatches + "/40 seeds — DO NOT SHIP !!!");
      System.out.printf("RandomState.create SLIM : %.3f ms/seed  (FULL: %.3f — the old per-seed cost)%n", msSlim, msFull);
      System.out.printf("findSpawnPosition       : %.3f ms/seed%n", msSpawn);
      System.out.printf("spawn distance from 0,0 : min %d · p50 %d · p90 %d · p99 %d · max %d (n=%d)%n",
            dist[0], dist[n / 2], dist[(int) (n * 0.9)], dist[(int) (n * 0.99)], dist[n - 1], n);

      // ── B) end-to-end wishes (single thread; multiply by cores for real rate) ──
      // Nether/End structures + Dungeon were removed from the catalog (2026-07-14),
      // so this harness only exercises the overworld wishes that are actually offered.
      SeedCriteria.StructureTarget village = byLabel(catalog, "Surface Village");
      SeedCriteria.StructureTarget portal = byLabel(catalog, "Ruined Portal");
      SeedCriteria.StructureTarget mansion = byLabel(catalog, "Woodland Mansion");

      run("Village (Exact 64)", ctx, c -> c.structures.add(village.withRadius(64)), false, 11_000L);
      run("Village + jungle (Exact 64)", ctx, c -> {
         c.structures.add(village.withRadius(64));
         c.biomes.add(biome("jungle", 64));
      }, false, 13_000L);
      run("Village + Ruined Portal (Exact 64)", ctx, c -> {
         c.structures.add(village.withRadius(64));
         c.structures.add(portal.withRadius(64));
      }, false, 14_000L);
      run("Mansion (Exact 64)", ctx, c -> c.structures.add(mansion.withRadius(64)), false, 16_000L);
      run("Village + jungle (Fast 100)", ctx, c -> {
         c.structures.add(village.withRadius(100));
         c.biomes.add(biome("jungle", 100));
      }, true, 17_000L);

      System.out.println("======================================");
   }

   private interface Fill {
      void fill(SeedCriteria c);
   }

   /** Sequential seeds from a fixed base so runs are reproducible; stops at 8 hits or 25s. */
   private static void run(String name, WorldgenContext ctx, Fill fill, boolean fast, long seedBase) {
      SeedCriteria c = new SeedCriteria();
      fill.fill(c);
      List<String> lines = new ArrayList<>(4);
      int hits = 0;
      long firstHitSeed = 0;
      List<String> firstHitLines = List.of();
      long checked = 0;
      long start = System.nanoTime();
      long budget = start + 25_000_000_000L;
      long seed = seedBase;
      while (hits < 8 && System.nanoTime() < budget) {
         lines.clear();
         if (c.test(seed, ctx, lines, fast)) {
            hits++;
            if (hits == 1) {
               firstHitSeed = seed;
               firstHitLines = List.copyOf(lines);
            }
         }
         checked++;
         seed++;
      }
      double secs = (System.nanoTime() - start) / 1e9;
      System.out.printf("%n--- %s ---%n", name);
      System.out.printf("checked %,d seeds in %.1fs  →  %,.0f seeds/sec/thread · %d hits (1 in %,d)%n",
            checked, secs, checked / secs, hits, hits == 0 ? checked : checked / hits);
      if (hits > 0) {
         System.out.println("verify in-game with seed " + firstHitSeed + " :");
         for (String l : firstHitLines) {
            System.out.println("   " + l);
         }
      } else {
         System.out.println("NO HITS — if this wish is common, something is wrong with the search.");
      }
   }

   private static SeedCriteria.StructureTarget byLabel(List<SeedCriteria.StructureTarget> catalog, String label) {
      for (SeedCriteria.StructureTarget t : catalog) {
         if (t.label.equals(label)) {
            return t;
         }
      }
      throw new IllegalStateException("catalog is missing: " + label);
   }

   private static SeedCriteria.BiomeTarget biome(String path, int radius) {
      Identifier id = Identifier.withDefaultNamespace(path);
      return new SeedCriteria.BiomeTarget(
            ResourceKey.create(Registries.BIOME, id), path, SeedCatalog.biomeDim(id), radius, 0);
   }

   /** Loads the real vanilla worldgen registries (with tags) headlessly, like the dedicated
    *  server — same recipe as {@link EyeDiagnostic}. */
   private static RegistryAccess.Frozen loadFullRegistries() {
      PackRepository repo = ServerPacksSource.createVanillaTrustedRepository();
      repo.reload();
      List<PackResources> packs = repo.getAvailablePacks().stream().map(Pack::open).toList();
      MultiPackResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs);
      LayeredRegistryAccess<RegistryLayer> layered = RegistryLayer.createRegistryAccess();
      RegistryAccess.Frozen forLoading = layered.getAccessForLoading(RegistryLayer.WORLDGEN);
      List<Registry.PendingTags<?>> pending = TagLoader.loadTagsForExistingRegistries(resources, forLoading);
      List<HolderLookup.RegistryLookup<?>> base = TagLoader.buildUpdatedLookups(forLoading, pending);
      RegistryAccess.Frozen worldgen = RegistryDataLoader.load(resources, base, RegistryDataLoader.WORLDGEN_REGISTRIES, Runnable::run).join();
      pending.forEach(Registry.PendingTags::apply);
      return layered.replaceFrom(RegistryLayer.WORLDGEN, worldgen).compositeAccess();
   }

   private SpeedDiagnostic() {
   }
}
