package com.fablevision;

import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import com.fablevision.client.seedfinder.StrongholdEyes;
import com.fablevision.client.seedfinder.WorldgenContext;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.tags.TagLoader;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;

/**
 * Dev-only: runs the stronghold eye counter over real world-gen with NO game running, so we can
 * see whether it actually returns a sane binomial spread of eye counts (0-6 common) or is broken
 * (all -1 = generation/portal failed, all 0 = frames placed but eyes never set). Run with
 * {@code gradlew eyeDiag}.
 */
public final class EyeDiagnostic {
   private static final LevelHeightAccessor HEIGHT = new LevelHeightAccessor() {
      public int getHeight() {
         return 384;
      }

      public int getMinY() {
         return -64;
      }
   };

   public static void main(String[] args) {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      // A FULLY loaded RegistryAccess (with tags) built the same way the server does — this is
      // the RegistryAccess.Frozen the real game passes as `source`, so countEyes' cast + generation
      // behave exactly as in-game (unlike VanillaRegistries.createLookup, which isn't a RegistryAccess).
      RegistryAccess.Frozen reg = loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);

      int sample = args.length > 0 ? Integer.parseInt(args[0]) : 300;
      int failNeg1 = 0;
      int[] hist = new int[13];

      // We can't compute the REAL nearest stronghold headlessly (createForNormal needs biome tags
      // bound, which VanillaRegistries.createLookup doesn't do). But countEyes takes the ring chunk
      // as a parameter and never touches that path — so feed it a plausible ring-0 stronghold chunk
      // (varied per seed) and check the eye-count DISTRIBUTION. A working counter gives a binomial
      // spread (0-6 common); all -1 = generation/portal failed, all 0 = frames placed but eyes never set.
      for (long seed = 1; seed <= sample; seed++) {
         // 1.29.0: the finder's default RandomState went climate-only (slim); eye counting
         // GENERATES pieces, so it must keep using the FULL per-seed noise state.
         RandomState rs = ctx.fullRandomState(Dim.OVERWORLD, seed);
         ChunkPos ring = new ChunkPos(96 + (int) (seed % 13), 88 + (int) (seed % 17)); // ~1400-1700 blocks out
         if (seed <= 8) {
            detail(reg, ctx, seed, ring, rs);
         }
         int eyes = StrongholdEyes.countEyes(reg, ctx, seed, ring, rs);
         if (eyes < 0) {
            failNeg1++;
         } else {
            hist[Math.min(12, eyes)]++;
         }
      }

      int withEyes2 = 0;
      for (int e = 2; e < 13; e++) {
         withEyes2 += hist[e];
      }
      System.out.println("========== EYE DIAGNOSTIC over " + sample + " seeds ==========");
      System.out.println("countEyes returned -1     : " + failNeg1 + "   (generation/portal/placement failed)");
      for (int e = 0; e < 13; e++) {
         if (hist[e] > 0) {
            System.out.println("  eyes = " + e + " : " + hist[e]);
         }
      }
      System.out.println("seeds with >=2 eyes       : " + withEyes2 + " / " + sample
            + "  (a WORKING engine should give roughly " + Math.round(sample * 0.34) + ")");
      System.out.println("=============================================================");
   }

   /** Reproduces countEyes' setup with printing so we see WHERE it fails for the first few seeds. */
   private static void detail(HolderLookup.Provider source, WorldgenContext ctx, long seed, ChunkPos ring, RandomState rs) {
      try {
         RegistryAccess registries = (RegistryAccess) source;
         Holder<Structure> stronghold = source.lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.STRONGHOLD);
         Holder<NoiseGeneratorSettings> noise = source.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
         NoiseBasedChunkGenerator gen = new NoiseBasedChunkGenerator(ctx.overworldBiomes, noise);
         StructureStart start = stronghold.value().generate(
               stronghold, Level.OVERWORLD, registries, gen, ctx.overworldBiomes, rs,
               null, seed, ring, 0, HEIGHT, h -> true);
         boolean valid = start != null && start.isValid();
         int pieces = valid ? start.getPieces().size() : 0;
         StructurePiece portal = null;
         if (valid) {
            for (StructurePiece p : start.getPieces()) {
               if (p instanceof StrongholdPieces.PortalRoom) {
                  portal = p;
                  break;
               }
            }
         }
         System.out.println("seed " + seed + " ring=" + ring + " valid=" + valid + " pieces=" + pieces
               + " portalRoom=" + (portal != null) + (portal != null ? " box=" + portal.getBoundingBox() : ""));
      } catch (Throwable t) {
         System.out.println("seed " + seed + " detail THREW: " + t);
         t.printStackTrace();
      }
   }

   /** Loads the real vanilla worldgen registries (with tags) headlessly, like the dedicated server. */
   private static RegistryAccess.Frozen loadFullRegistries() {
      PackRepository repo = ServerPacksSource.createVanillaTrustedRepository();
      repo.reload();
      List<PackResources> packs = repo.getAvailablePacks().stream().map(Pack::open).toList();
      MultiPackResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs);
      LayeredRegistryAccess<RegistryLayer> layered = RegistryLayer.createRegistryAccess();
      RegistryAccess.Frozen forLoading = layered.getAccessForLoading(RegistryLayer.WORLDGEN);
      // Bind the static-registry tags (item/entity/etc.) first — worldgen data (enchantments, ...)
      // references them, and without this the load fails with "Missing tag".
      List<Registry.PendingTags<?>> pending = TagLoader.loadTagsForExistingRegistries(resources, forLoading);
      List<HolderLookup.RegistryLookup<?>> base = TagLoader.buildUpdatedLookups(forLoading, pending);
      RegistryAccess.Frozen worldgen = RegistryDataLoader.load(resources, base, RegistryDataLoader.WORLDGEN_REGISTRIES, Runnable::run).join();
      pending.forEach(Registry.PendingTags::apply);
      return layered.replaceFrom(RegistryLayer.WORLDGEN, worldgen).compositeAccess();
   }

   private EyeDiagnostic() {
   }
}
