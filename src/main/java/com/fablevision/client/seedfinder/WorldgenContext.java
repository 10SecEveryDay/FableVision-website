package com.fablevision.client.seedfinder;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;

/**
 * The world-gen data every seed check shares, built from REAL registries that exist at
 * runtime (the shipped client jar does NOT contain the data-gen bootstrap class, so this
 * must come from a live source):
 *  - on the Create World screen: that screen's own worldgen registries (datapack-aware),
 *  - in your singleplayer world: the integrated server's registries.
 * Everything here is immutable and safe to share across worker threads; only
 * {@link RandomState} (via {@link #randomState}) is per-seed.
 *
 * Covers all three dimensions (1.21.0 added the End) so structures/biomes from every
 * dimension are searchable.
 */
public final class WorldgenContext {
   private static volatile WorldgenContext instance;

   final HolderLookup.Provider source;
   public final HolderLookup.RegistryLookup<StructureSet> structureSets;
   public final HolderLookup.RegistryLookup<Biome> biomes;
   public final BiomeSource overworldBiomes;
   public final BiomeSource netherBiomes;
   public final BiomeSource endBiomes;
   public final List<Holder.Reference<StructureSet>> allSets;
   private final HolderGetter<NormalNoise.NoiseParameters> noiseParams;
   private final NoiseGeneratorSettings[] fullSettings = new NoiseGeneratorSettings[3];
   private final NoiseGeneratorSettings[] slimSettings = new NoiseGeneratorSettings[3];

   /**
    * Where to get registries from right now. Pass the Create World screen when there is
    * one (the wish flow); otherwise this uses your singleplayer world. Returns null on a
    * remote server / main menu with no screen — the finder can't run there.
    */
   public static HolderLookup.Provider findSource(CreateWorldScreen createScreen) {
      if (createScreen != null) {
         return createScreen.getUiState().getSettings().worldgenLoadContext();
      }
      MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
      return server != null ? server.registryAccess() : null;
   }

   public static WorldgenContext get(HolderLookup.Provider source) {
      WorldgenContext local = instance;
      if (local == null || local.source != source) {
         synchronized (WorldgenContext.class) {
            local = instance;
            if (local == null || local.source != source) {
               instance = local = new WorldgenContext(source);
            }
         }
      }
      return local;
   }

   private WorldgenContext(HolderLookup.Provider source) {
      this.source = source;
      this.structureSets = source.lookupOrThrow(Registries.STRUCTURE_SET);
      this.biomes = source.lookupOrThrow(Registries.BIOME);
      var presets = source.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
      this.overworldBiomes = MultiNoiseBiomeSource.createFromPreset(presets.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
      this.netherBiomes = MultiNoiseBiomeSource.createFromPreset(presets.getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));
      this.endBiomes = TheEndBiomeSource.create(this.biomes);
      this.allSets = structureSets.listElements().toList();
      this.noiseParams = source.lookupOrThrow(Registries.NOISE);
      var noiseSettings = source.lookupOrThrow(Registries.NOISE_SETTINGS);
      for (Dim dim : Dim.values()) {
         NoiseGeneratorSettings full = noiseSettings.getOrThrow(switch (dim) {
            case NETHER -> NoiseGeneratorSettings.NETHER;
            case END -> NoiseGeneratorSettings.END;
            default -> NoiseGeneratorSettings.OVERWORLD;
         }).value();
         fullSettings[dim.ordinal()] = full;
         slimSettings[dim.ordinal()] = climateOnly(full);
      }
   }

   /**
    * The finder's speed trick: {@link RandomState#create} instantiates EVERY noise the router
    * references (~9ms/seed for the full overworld router — measured by {@code gradlew speedDiag}),
    * but the finder only ever reads the CLIMATE side (biome confirms, the spawn search, ring
    * biome checks). So we hand it a copy of the settings whose router keeps the six climate
    * functions and zeroes the rest. This is vanilla-exact by construction: each noise is seeded
    * by its registry NAME (not creation order), so dropping unrelated functions cannot change
    * the climate outputs — and speedDiag diffs slim vs full spawn positions to prove it.
    */
   private static NoiseGeneratorSettings climateOnly(NoiseGeneratorSettings s) {
      NoiseRouter r = s.noiseRouter();
      DensityFunction zero = DensityFunctions.zero();
      NoiseRouter climate = new NoiseRouter(zero, zero, zero, zero,
            r.temperature(), r.vegetation(), r.continents(), r.erosion(), r.depth(), r.ridges(),
            zero, zero, zero, zero, zero);
      return new NoiseGeneratorSettings(s.noiseSettings(), s.defaultBlock(), s.defaultFluid(), climate,
            s.surfaceRule(), s.spawnTarget(), s.seaLevel(), s.disableMobGeneration(), s.aquifersEnabled(),
            s.oreVeinsEnabled(), s.useLegacyRandomSource());
   }

   public BiomeSource biomeSource(Dim dim) {
      return switch (dim) {
         case NETHER -> netherBiomes;
         case END -> endBiomes;
         default -> overworldBiomes;
      };
   }

   /** Per-seed CLIMATE-ONLY noise state — all the finder needs, at a fraction of the cost of
    *  the full router (see {@link #climateOnly}). Never hand this to real structure GENERATION. */
   public RandomState randomState(Dim dim, long seed) {
      return RandomState.create(slimSettings[dim.ordinal()], noiseParams, seed);
   }

   /** The FULL per-seed noise state — expensive (~9ms), needed only when actually generating
    *  pieces (the stronghold eye counter), where terrain functions must be the real ones. */
   public RandomState fullRandomState(Dim dim, long seed) {
      return RandomState.create(fullSettings[dim.ordinal()], noiseParams, seed);
   }

   /** Vanilla's per-seed structure bookkeeping (exclusion zones, stronghold rings). */
   public ChunkGeneratorStructureState structureState(Dim dim, long seed, RandomState randomState) {
      return ChunkGeneratorStructureState.createForNormal(randomState, seed, biomeSource(dim), structureSets);
   }
}
