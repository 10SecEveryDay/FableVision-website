package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;

/**
 * What the user wants a seed to have, plus the per-seed evaluation.
 *
 * Distances are measured from the seed's REAL spawn point ({@link Climate.Sampler#findSpawnPosition},
 * the game's own pick, which wanders from 0,0 and avoids some climates) — unless Fast mode
 * is on, which measures from 0,0 like classic seed tools (much faster, but the target may be
 * a short walk from where you spawn). NO mode ever moves your spawn; only the SEED is chosen,
 * and you spawn exactly as vanilla would.
 *
 * Evaluation is a funnel so seeds fly by: cheap placement math first, a structure GATE (each
 * wanted structure must truly generate somewhere in reach) before the expensive spawn search,
 * then exact confirms only for survivors.
 */
public final class SeedCriteria {
   public enum Dim { OVERWORLD, NETHER, END }
   /** Non-searchable-by-placement kinds kept in the list for completeness. */
   public enum Special { NORMAL, STRONGHOLD, DUNGEON }
   /** Village abandoned/zombie requirement. */
   public enum ZombieMode { ANY, NORMAL, ABANDONED }

   /** Structures the seed must have, ANDed. */
   public final List<StructureTarget> structures = new ArrayList<>();
   /** Biomes that must appear near spawn, ANDed (max 3 — usually just one). */
   public final List<BiomeTarget> biomes = new ArrayList<>();
   /** Things that must NOT be near spawn — the "X not next to Y" wish. */
   public final List<StructureTarget> excludeStructures = new ArrayList<>();
   public final List<BiomeTarget> excludeBiomes = new ArrayList<>();
   /** Stronghold max distance from spawn; 0 = off. Ring 1 lives ~1280-2816 out. */
   public int strongholdRadius = 0;
   /** Require the nearest stronghold's portal to already have at least this many eyes; -1 = don't care.
    *  Expensive (a real stronghold generation per seed) and high counts are astronomically rare. */
   public int strongholdEyes = -1;

   /** How far the game's climate spawn search can wander from 0,0 (radius 2048) plus the
    *  ±5-chunk ground search around the chosen chunk. Stage-0 prefilters must cover it. */
   private static final int SPAWN_WANDER = 2144;
   /** Same wander in Nether coordinates (portal math: overworld /8) plus a chunk margin. */
   private static final int SPAWN_WANDER_NETHER = SPAWN_WANDER / 8 + 16;

   /** "big"/"huge" biome-size requests: the patch must span at least this many blocks in
    *  BOTH x and z, measured by real noise sampling. Softened in 1.20.0 (was 250/450). */
   public static final int SPAN_BIG = 224;
   public static final int SPAN_HUGE = 384;

   /** Honest per-dimension radius floors (measured by speedDiag). The Nether puts fortress
    *  AND bastion in ONE structure set (~one winner per 432-block region), so a pair can
    *  never sit within ~64 blocks of the portal-in point — 57k seeds gave zero hits. End
    *  Cities only exist in the outer islands (~1000+ blocks from 0,0), so a small End radius
    *  can never match. Every match line still reports the TRUE distance. */
   static int dimRadius(Dim dim, int r) {
      return switch (dim) {
         case NETHER -> Math.max(r, 208);
         case END -> Math.max(r, 3072);
         default -> r;
      };
   }

   /** Same idea for biomes: End biomes (other than the center island) start ~1000 out. */
   static int dimBiomeRadius(Dim dim, int r) {
      return switch (dim) {
         case NETHER -> Math.max(r, 208);
         case END -> Math.max(r, 1024);
         default -> r;
      };
   }

   public boolean isEmpty() {
      // Excludes count as a real search too — "no deserts near spawn" alone is a valid wish.
      return searchableStructures().isEmpty() && biomes.isEmpty()
            && excludeStructures.isEmpty() && excludeBiomes.isEmpty()
            && strongholdRadius <= 0 && strongholdEyes < 0;
   }

   /** True when something is actually measured from the overworld spawn — the only case
    *  where Exact mode's "aim RIGHT at spawn first" tier means anything (Nether/End floors
    *  and stronghold rings are far by nature). */
   public boolean hasOverworldTarget() {
      for (StructureTarget t : searchableStructures()) {
         if (t.dim == Dim.OVERWORLD) {
            return true;
         }
      }
      for (BiomeTarget b : biomes) {
         if (b.dim == Dim.OVERWORLD) {
            return true;
         }
      }
      return false;
   }

   private List<StructureTarget> searchableStructures() {
      List<StructureTarget> out = new ArrayList<>();
      for (StructureTarget t : structures) {
         if (t.special == Special.NORMAL) {
            out.add(t);
         }
      }
      return out;
   }

   /** One-line human summary, e.g. "Village ≤300 + Ruined Portal ≤200 + jungle ≤500". */
   public String summary() {
      List<String> parts = new ArrayList<>();
      for (StructureTarget t : structures) {
         if (t.special == Special.NORMAL) {
            parts.add(t.label + " ≤" + t.radius);
         }
      }
      for (BiomeTarget b : biomes) {
         String size = b.minSpan >= SPAN_HUGE ? "huge " : b.minSpan > 0 ? "big " : "";
         parts.add(size + b.label + " ≤" + b.radius);
      }
      if (strongholdEyes >= 0) {
         parts.add("Stronghold ≥" + strongholdEyes + " eyes");
      } else if (strongholdRadius > 0) {
         parts.add("Stronghold ≤" + strongholdRadius);
      }
      for (StructureTarget t : excludeStructures) {
         parts.add("no " + t.label);
      }
      for (BiomeTarget b : excludeBiomes) {
         parts.add("no " + b.label);
      }
      return String.join(" + ", parts);
   }

   /** Combined honest "can't fully verify" note from the chosen targets (End Ship, Abandoned…). */
   public String notes() {
      List<String> seen = new ArrayList<>();
      for (StructureTarget t : structures) {
         if (!t.note.isEmpty() && !seen.contains(t.note)) {
            seen.add(t.note);
         }
      }
      return String.join("  ", seen);
   }

   /**
    * Full check of one seed. On a hit, human-readable match lines are appended to
    * {@code matchLines}. Thread-safe: only touches shared immutable data.
    */
   public boolean test(long seed, WorldgenContext ctx, List<String> matchLines, boolean fast) {
      return test(seed, ctx, matchLines, fast, null);
   }

   /** Same, also reporting HOW tight the hit is: on a hit, {@code worstOut[0]} gets the
    *  largest distance among the things measured from the overworld spawn — Exact mode's
    *  "aim RIGHT at spawn first" tier sorts backups by it. Only valid when true is returned. */
   public boolean test(long seed, WorldgenContext ctx, List<String> matchLines, boolean fast, int[] worstOut) {
      List<StructureTarget> targets = searchableStructures();
      int worst = 0;

      // Stage 0 for every structure target — the cheap mass rejection.
      List<List<Cand>> candidates = new ArrayList<>(targets.size());
      for (StructureTarget t : targets) {
         List<Cand> c = t.stage0(seed);
         if (c.isEmpty()) {
            return false;
         }
         candidates.add(c);
      }

      // RandomState is the expensive per-seed object (~0.5-2ms) — create it lazily via rsFor
      // only for dimensions actually used, so e.g. a Nether-only wish ("fortress + bastion")
      // never pays for an overworld one it never touches.
      RandomState[] rs = new RandomState[3];
      ChunkGeneratorStructureState[] st = new ChunkGeneratorStructureState[3];

      // GATE: require every structure to truly generate somewhere in reach BEFORE paying
      // for the (expensive) real-spawn search. Rare targets reject most seeds cheaply.
      // Confirms are cached and reused by the exact pass below.
      List<Map<Cand, BlockPos>> caches = new ArrayList<>(targets.size());
      for (int i = 0; i < targets.size(); i++) {
         caches.add(new HashMap<>());
      }
      Integer[] order = new Integer[targets.size()];
      for (int i = 0; i < order.length; i++) {
         order[i] = i;
      }
      java.util.Arrays.sort(order, Comparator.comparingInt(i -> candidates.get(i).size()));
      for (int idx : order) {
         StructureTarget t = targets.get(idx);
         List<Cand> cands = candidates.get(idx);
         cands.sort(Comparator.comparingLong(c -> dist2(c.chunk(), 0, 0)));
         Map<Cand, BlockPos> cache = caches.get(idx);
         boolean any = false;
         for (Cand c : cands) {
            BlockPos hit = t.confirm(seed, ctx, rsFor(ctx, rs, t.dim, seed), stFor(ctx, st, rs, t.dim, seed), c);
            cache.put(c, hit);
            if (hit != null) {
               any = true;
               break;
            }
         }
         if (!any) {
            return false;
         }
      }

      // The seed's REAL spawn point — the pricey part, only reached by survivors. Fast mode
      // skips it entirely and measures from 0,0 (classic seed-tool behaviour).
      boolean needOverworldSpawn = !fast && (anyDim(targets, Dim.OVERWORLD) || anyBiomeDim(Dim.OVERWORLD)
            || strongholdRadius > 0 || anyExcludeOverworld());
      BlockPos spawn = needOverworldSpawn ? rsFor(ctx, rs, Dim.OVERWORLD, seed).sampler().findSpawnPosition() : new BlockPos(0, 64, 0);
      matchLines.add(fast ? "Spawn ~ 0, 0 (fast mode — the find may be a short walk away)"
            : "Spawn @ " + spawn.getX() + ", " + spawn.getZ());

      for (int i = 0; i < targets.size(); i++) {
         StructureTarget t = targets.get(i);
         int ax = originX(t.dim, spawn);
         int az = originZ(t.dim, spawn);
         long r2 = (long) t.radius * t.radius;
         List<Cand> inRange = new ArrayList<>(4);
         for (Cand c : candidates.get(i)) {
            if (dist2(c.chunk(), ax, az) <= r2) {
               inRange.add(c);
            }
         }
         if (inRange.isEmpty()) {
            matchLines.clear();
            return false;
         }
         inRange.sort(Comparator.comparingLong(c -> dist2(c.chunk(), ax, az)));
         Map<Cand, BlockPos> cache = caches.get(i);
         BlockPos hit = null;
         for (Cand c : inRange) {
            BlockPos h = cache.containsKey(c) ? cache.get(c)
                  : t.confirm(seed, ctx, rsFor(ctx, rs, t.dim, seed), stFor(ctx, st, rs, t.dim, seed), c);
            if (h != null) {
               hit = h;
               break;
            }
         }
         if (hit == null) {
            matchLines.clear();
            return false;
         }
         long d = Math.round(Math.hypot(hit.getX() - ax, hit.getZ() - az));
         if (t.dim == Dim.OVERWORLD) {
            worst = (int) Math.max(worst, d);
         }
         matchLines.add(t.label + " @ " + hit.getX() + ", " + hit.getZ() + " (" + d + " blocks"
               + (t.dim == Dim.NETHER ? ", Nether" : t.dim == Dim.END ? ", End" : "") + ")");
      }

      for (BiomeTarget bt : biomes) {
         BiomeSource bs = ctx.biomeSource(bt.dim);
         Climate.Sampler sampler = rsFor(ctx, rs, bt.dim, seed).sampler();
         int ox = originX(bt.dim, spawn);
         int oz = originZ(bt.dim, spawn);
         ResourceKey<Biome> want = bt.biome;
         Holder<Biome> atOrigin = bs.getNoiseBiome(QuartPos.fromBlock(ox), QuartPos.fromBlock(64), QuartPos.fromBlock(oz), sampler);
         BlockPos center;
         String where;
         if (atOrigin.is(want)) {
            center = new BlockPos(ox, 64, oz);
            where = bt.dim == Dim.OVERWORLD ? " right at spawn" : " at 0,0";
         } else {
            // End biomes sit ~1000+ out with a big floor radius, so sample coarser there —
            // outer islands are hundreds of blocks wide and step 96 can't miss them.
            int step = bt.dim == Dim.END ? 96 : bt.minSpan > 0 ? Math.max(48, Math.min(128, bt.minSpan / 3)) : 32;
            Pair<BlockPos, Holder<Biome>> found = bs.findBiomeHorizontal(
               ox, 64, oz, bt.radius, step, h -> h.is(want), RandomSource.create(seed), false, sampler);
            if (found == null) {
               matchLines.clear();
               return false;
            }
            center = found.getFirst();
            long d = Math.round(Math.hypot(center.getX() - ox, center.getZ() - oz));
            if (bt.dim == Dim.OVERWORLD) {
               worst = (int) Math.max(worst, d);
            }
            where = " @ " + center.getX() + ", " + center.getZ() + " (" + d + " blocks)";
         }
         if (bt.minSpan > 0) {
            int[] span = biomeSpan(bs, sampler, want, center);
            if (Math.min(span[0], span[1]) < bt.minSpan) {
               matchLines.clear();
               return false;
            }
            where += " — spans ~" + span[0] + "×" + span[1];
         }
         matchLines.add(bt.label + where);
      }

      // Exclusions ("not next to"): reject if any forbidden thing is actually near spawn.
      // A pass gets its own ✓ line, so an exclude-ONLY wish still counts as a real match
      // (the final size check needs more than just the spawn line).
      for (StructureTarget ex : excludeStructures) {
         int ax = originX(ex.dim, spawn);
         int az = originZ(ex.dim, spawn);
         long r2 = (long) ex.radius * ex.radius;
         for (Cand c : ex.stage0(seed)) {
            if (dist2(c.chunk(), ax, az) <= r2
                  && ex.confirm(seed, ctx, rsFor(ctx, rs, ex.dim, seed), stFor(ctx, st, rs, ex.dim, seed), c) != null) {
               matchLines.clear();
               return false;
            }
         }
         matchLines.add("No " + ex.label + " nearby ✓");
      }
      for (BiomeTarget ex : excludeBiomes) {
         BiomeSource bs = ctx.biomeSource(ex.dim);
         Climate.Sampler sampler = rsFor(ctx, rs, ex.dim, seed).sampler();
         int ox = originX(ex.dim, spawn);
         int oz = originZ(ex.dim, spawn);
         Holder<Biome> at = bs.getNoiseBiome(QuartPos.fromBlock(ox), QuartPos.fromBlock(64), QuartPos.fromBlock(oz), sampler);
         boolean near = at.is(ex.biome) || bs.findBiomeHorizontal(ox, 64, oz, ex.radius, 32,
               h -> h.is(ex.biome), RandomSource.create(seed), false, sampler) != null;
         if (near) {
            matchLines.clear();
            return false;
         }
         matchLines.add("No " + ex.label + " nearby ✓");
      }

      if (strongholdRadius > 0 || strongholdEyes >= 0) {
         ChunkGeneratorStructureState state = stFor(ctx, st, rs, Dim.OVERWORLD, seed);
         ChunkPos ringChunk = nearestStronghold(ctx, state, spawn);
         if (ringChunk == null) {
            matchLines.clear();
            return false;
         }
         int rx = ringChunk.getMiddleBlockX();
         int rz = ringChunk.getMiddleBlockZ();
         long dx = rx - spawn.getX();
         long dz = rz - spawn.getZ();
         if (strongholdRadius > 0 && dx * dx + dz * dz > (long) strongholdRadius * strongholdRadius) {
            matchLines.clear();
            return false;
         }
         String line = "Stronghold @ " + rx + ", " + rz + " (" + Math.round(Math.hypot(dx, dz)) + " blocks)";
         if (strongholdEyes >= 0) {
            // Eyes actually GENERATE the stronghold, so they need the FULL noise state — the
            // slim climate-only one used by everything else would misplace terrain-aware pieces.
            int eyes = StrongholdEyes.countEyes(ctx.source, ctx, seed, ringChunk, ctx.fullRandomState(Dim.OVERWORLD, seed));
            if (eyes < strongholdEyes) {
               matchLines.clear();
               return false;
            }
            line += " — §e" + eyes + " eye" + (eyes == 1 ? "" : "s") + " already in the portal";
         }
         matchLines.add(line);
      }

      if (worstOut != null && worstOut.length > 0) {
         worstOut[0] = worst;
      }
      return matchLines.size() > 1; // spawn line is always present; need a real match too
   }

   // ── per-dimension helpers ─────────────────────────────────────────────────

   private static int originX(Dim dim, BlockPos spawn) {
      return dim == Dim.OVERWORLD ? spawn.getX() : dim == Dim.NETHER ? spawn.getX() / 8 : 0;
   }

   private static int originZ(Dim dim, BlockPos spawn) {
      return dim == Dim.OVERWORLD ? spawn.getZ() : dim == Dim.NETHER ? spawn.getZ() / 8 : 0;
   }

   private static long dist2(ChunkPos c, int ax, int az) {
      long dx = c.getMiddleBlockX() - ax;
      long dz = c.getMiddleBlockZ() - az;
      return dx * dx + dz * dz;
   }

   private boolean anyDim(List<StructureTarget> targets, Dim dim) {
      for (StructureTarget t : targets) {
         if (t.dim == dim) {
            return true;
         }
      }
      return false;
   }

   private boolean anyBiomeDim(Dim dim) {
      for (BiomeTarget b : biomes) {
         if (b.dim == dim) {
            return true;
         }
      }
      return false;
   }

   /** Excludes are measured from spawn too, so they also need the real spawn in Exact mode. */
   private boolean anyExcludeOverworld() {
      for (StructureTarget t : excludeStructures) {
         if (t.dim == Dim.OVERWORLD) {
            return true;
         }
      }
      for (BiomeTarget b : excludeBiomes) {
         if (b.dim == Dim.OVERWORLD) {
            return true;
         }
      }
      return false;
   }

   private static RandomState rsFor(WorldgenContext ctx, RandomState[] rs, Dim dim, long seed) {
      int i = dim.ordinal();
      if (rs[i] == null) {
         rs[i] = ctx.randomState(dim, seed);
      }
      return rs[i];
   }

   private static ChunkGeneratorStructureState stFor(WorldgenContext ctx, ChunkGeneratorStructureState[] st, RandomState[] rs, Dim dim, long seed) {
      int i = dim.ordinal();
      if (st[i] == null) {
         st[i] = ctx.structureState(dim, seed, rsFor(ctx, rs, dim, seed));
      }
      return st[i];
   }

   /** Nearest stronghold ring chunk to spawn (its center is the portal). */
   private static ChunkPos nearestStronghold(WorldgenContext ctx, ChunkGeneratorStructureState st, BlockPos spawn) {
      ChunkPos best = null;
      long bestD = Long.MAX_VALUE;
      for (Holder.Reference<StructureSet> setRef : ctx.allSets) {
         if (setRef.value().placement() instanceof ConcentricRingsStructurePlacement rings) {
            for (ChunkPos c : st.getRingPositionsFor(rings)) {
               long bx = c.getMiddleBlockX() - spawn.getX();
               long bz = c.getMiddleBlockZ() - spawn.getZ();
               long d = bx * bx + bz * bz;
               if (d < bestD) {
                  bestD = d;
                  best = c;
               }
            }
         }
      }
      return best;
   }

   /** Rough size of the biome patch containing {@code center}: ray-walk ±x/±z, then re-measure
    *  once from the patch middle so an edge hit doesn't understate the size. Returns {xSpan, zSpan}. */
   private static int[] biomeSpan(BiomeSource bs, Climate.Sampler sampler, ResourceKey<Biome> want, BlockPos center) {
      int[] r = rays(bs, sampler, want, center.getX(), center.getZ());
      int midX = center.getX() + (r[0] - r[1]) / 2;
      int midZ = center.getZ() + (r[2] - r[3]) / 2;
      if (Math.abs(midX - center.getX()) >= 32 || Math.abs(midZ - center.getZ()) >= 32) {
         Holder<Biome> mid = bs.getNoiseBiome(QuartPos.fromBlock(midX), QuartPos.fromBlock(64), QuartPos.fromBlock(midZ), sampler);
         if (mid.is(want)) {
            int[] second = rays(bs, sampler, want, midX, midZ);
            if (Math.min(second[0] + second[1], second[2] + second[3]) > Math.min(r[0] + r[1], r[2] + r[3])) {
               r = second;
            }
         }
      }
      return new int[]{32 + r[0] + r[1], 32 + r[2] + r[3]};
   }

   private static int[] rays(BiomeSource bs, Climate.Sampler sampler, ResourceKey<Biome> want, int x, int z) {
      int step = 32;
      int cap = 1024;
      int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
      int[] out = new int[4];
      for (int i = 0; i < 4; i++) {
         int reach = 0;
         for (int d = step; d <= cap; d += step) {
            Holder<Biome> h = bs.getNoiseBiome(
               QuartPos.fromBlock(x + dirs[i][0] * d), QuartPos.fromBlock(64), QuartPos.fromBlock(z + dirs[i][1] * d), sampler);
            if (!h.is(want)) {
               break;
            }
            reach = d;
         }
         out[i] = reach;
      }
      return out;
   }

   // ── Candidate + placement ────────────────────────────────────────────────

   record Placement(Holder.Reference<StructureSet> set, RandomSpreadStructurePlacement spread) {}

   record Cand(ChunkPos chunk, Placement placement) {}

   // ── Structure targets ────────────────────────────────────────────────────

   /** "A Woodland Mansion within 500 blocks of spawn" — may span several structure sets
    *  (villages) and several structures in a set (shipwreck). Immutable except radius. */
   public static final class StructureTarget {
      public final String label;
      public final Dim dim;
      public final int sampleY;
      public int radius;
      public final Special special;
      public final ZombieMode zombie;
      public final String note;
      final List<Placement> placements;
      final Set<Identifier> wanted;

      private StructureTarget(String label, Dim dim, int sampleY, int radius, Special special,
                              ZombieMode zombie, String note, List<Placement> placements, Set<Identifier> wanted) {
         this.label = label;
         this.dim = dim;
         this.sampleY = sampleY;
         this.radius = radius;
         this.special = special;
         this.zombie = zombie;
         this.note = note;
         this.placements = placements;
         this.wanted = wanted;
      }

      /** A normal placement-searchable target built by {@link SeedCatalog}. */
      static StructureTarget build(String label, Dim dim, int sampleY, int radius,
                                   List<Holder.Reference<StructureSet>> sets, List<RandomSpreadStructurePlacement> spreads,
                                   Set<Identifier> wanted, ZombieMode zombie, String note) {
         List<Placement> pl = new ArrayList<>();
         for (int i = 0; i < sets.size(); i++) {
            pl.add(new Placement(sets.get(i), spreads.get(i)));
         }
         return new StructureTarget(label, dim, sampleY, radius, Special.NORMAL, zombie, note, pl, new HashSet<>(wanted));
      }

      /** A list-only entry that can't be placement-searched (Stronghold routes to its own
       *  criterion; Dungeon can't be searched at all). */
      static StructureTarget special(String label, Special special, String note) {
         return new StructureTarget(label, Dim.OVERWORLD, 64, 500, special, ZombieMode.ANY, note, List.of(), Set.of());
      }

      /** Copy with its own radius so a shared catalog entry is never mutated by the GUI.
       *  The per-dimension floor is applied here so EVERY path (menu, AI wish, excludes)
       *  gets honest Nether/End distances automatically. */
      public StructureTarget withRadius(int r) {
         return new StructureTarget(label, dim, sampleY, dimRadius(dim, r), special, zombie, note, placements, wanted);
      }

      /** Stage 0: candidate chunks from pure placement math, centered on 0,0 and widened by
       *  the spawn wander so the real spawn (found later) is always covered. */
      List<Cand> stage0(long seed) {
         List<Cand> out = new ArrayList<>(4);
         int reach = radius + (dim == Dim.OVERWORLD ? SPAWN_WANDER : dim == Dim.NETHER ? SPAWN_WANDER_NETHER : 0);
         long r2 = (long) reach * reach;
         for (Placement pl : placements) {
            int spacing = pl.spread().spacing();
            int chunkR = Math.max(1, reach >> 4);
            int rMin = Math.floorDiv(-chunkR, spacing);
            int rMax = Math.floorDiv(chunkR, spacing);
            for (int rx = rMin; rx <= rMax; rx++) {
               for (int rz = rMin; rz <= rMax; rz++) {
                  ChunkPos c = pl.spread().getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                  long bx = c.getMiddleBlockX();
                  long bz = c.getMiddleBlockZ();
                  if (bx * bx + bz * bz <= r2 && pl.spread().applyAdditionalChunkRestrictions(c.x(), c.z(), seed)) {
                     out.add(new Cand(c, pl));
                  }
               }
            }
         }
         return out;
      }

      /** Stage 1: vanilla-exact confirm at one candidate — exclusion zones, the seeded
       *  weighted winner pick, biome membership, and (for villages) the abandoned/zombie
       *  start-piece prediction. Returns the locate pos or null. */
      BlockPos confirm(long seed, WorldgenContext ctx, RandomState rs, ChunkGeneratorStructureState st, Cand cand) {
         StructureSet set = cand.placement().set().value();
         ChunkPos chunk = cand.chunk();
         StructurePlacement placement = set.placement();
         if (!placement.applyInteractionsWithOtherStructures(st, chunk.x(), chunk.z())) {
            return null;
         }
         BlockPos locate = placement.getLocatePos(chunk);
         BiomeSource biomes = ctx.biomeSource(dim);
         Climate.Sampler sampler = rs.sampler();

         List<StructureSet.StructureSelectionEntry> entries = new ArrayList<>(set.structures());
         StructureSet.StructureSelectionEntry won = null;
         if (entries.size() == 1) {
            if (biomeOk(entries.get(0), biomes, sampler, locate)) {
               won = entries.get(0);
            }
         } else {
            WorldgenRandom rand = new WorldgenRandom(new LegacyRandomSource(0L));
            rand.setLargeFeatureSeed(seed, chunk.x(), chunk.z());
            int total = 0;
            for (StructureSet.StructureSelectionEntry e : entries) {
               total += e.weight();
            }
            while (!entries.isEmpty() && total > 0) {
               int roll = rand.nextInt(total);
               int idx = 0;
               for (StructureSet.StructureSelectionEntry e : entries) {
                  roll -= e.weight();
                  if (roll < 0) {
                     break;
                  }
                  idx++;
               }
               StructureSet.StructureSelectionEntry chosen = entries.get(idx);
               if (biomeOk(chosen, biomes, sampler, locate)) {
                  won = chosen;
                  break;
               }
               total -= chosen.weight();
               entries.remove(idx);
            }
         }

         if (won == null) {
            return null;
         }
         Identifier winner = idOf(won);
         if (!wanted.isEmpty() && !wanted.contains(winner)) {
            return null;
         }
         if (zombie != ZombieMode.ANY && !zombieMatches(seed, chunk, won)) {
            return null;
         }
         return locate;
      }

      /** Predicts whether the village at this chunk is a zombie (abandoned) village by
       *  replaying the jigsaw start dice: seed the structure random exactly as the game does
       *  ({@code setLargeFeatureSeed}), consume the rotation draw, then the start-template
       *  pick, and check if that template is a zombie variant. */
      private boolean zombieMatches(long seed, ChunkPos chunk, StructureSet.StructureSelectionEntry won) {
         Structure structure = won.structure().value();
         if (!(structure instanceof JigsawStructure jig)) {
            return zombie == ZombieMode.NORMAL; // no jigsaw start = never zombie
         }
         StructureTemplatePool pool = jig.getStartPool().value();
         WorldgenRandom rand = new WorldgenRandom(new LegacyRandomSource(0L));
         rand.setLargeFeatureSeed(seed, chunk.x(), chunk.z());
         Rotation.getRandom(rand); // consumed before the start piece, same as vanilla
         StructurePoolElement start = pool.getRandomTemplate(rand);
         boolean isZombie = start instanceof SinglePoolElement sp
               && sp.getTemplateLocation().getPath().contains("zombie");
         return zombie == ZombieMode.ABANDONED ? isZombie : !isZombie;
      }

      private boolean biomeOk(StructureSet.StructureSelectionEntry entry, BiomeSource biomes,
                              Climate.Sampler sampler, BlockPos at) {
         Holder<Biome> here = biomes.getNoiseBiome(
            QuartPos.fromBlock(at.getX()), QuartPos.fromBlock(sampleY), QuartPos.fromBlock(at.getZ()), sampler);
         HolderSet<Biome> allowed = entry.structure().value().biomes();
         return allowed.contains(here);
      }

      private static Identifier idOf(StructureSet.StructureSelectionEntry entry) {
         return entry.structure().unwrapKey().map(ResourceKey::identifier).orElse(null);
      }
   }

   // ── Biome targets ────────────────────────────────────────────────────────

   /** "A (huge) Pale Garden within 500 blocks of spawn." minSpan 0 = any size. */
   public static final class BiomeTarget {
      public final ResourceKey<Biome> biome;
      public final String label;
      public final Dim dim;
      public int radius;
      public int minSpan;

      public BiomeTarget(ResourceKey<Biome> biome, String label, Dim dim, int radius, int minSpan) {
         this.biome = biome;
         this.label = label;
         this.dim = dim;
         this.radius = dimBiomeRadius(dim, radius); // honest floors for Nether/End (see above)
         this.minSpan = minSpan;
      }
   }

   // ── Catalog (delegates to the fixed SeedCatalog) ─────────────────────────

   public static List<StructureTarget> catalog(WorldgenContext ctx) {
      return SeedCatalog.structures(ctx);
   }
}
