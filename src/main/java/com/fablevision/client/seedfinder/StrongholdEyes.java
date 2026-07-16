package com.fablevision.client.seedfinder;

import java.util.function.Predicate;
import com.fablevision.client.FableVisionClient;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;

/**
 * Reads how many eyes of ender a seed's stronghold portal already has — the one truly hard
 * prediction. Rather than re-implementing thousands of RNG draws, it REUSES the game's own
 * stronghold generation: build a real overworld generator for the seed, generate the real
 * stronghold pieces, then run the real Portal Room's block placement into a tiny counting
 * level that just tallies END_PORTAL_FRAME blocks whose HAS_EYE is true — seeded exactly the
 * way chunk population seeds it.
 *
 * Beta: the counting level is a stub of a big interface and the seeding must match the game
 * bit-for-bit, so verify a couple results in-game (/locate + count the portal).
 */
public final class StrongholdEyes {
   private StrongholdEyes() {
   }

   private static final LevelHeightAccessor OVERWORLD_HEIGHT = new LevelHeightAccessor() {
      public int getHeight() {
         return 384;
      }

      public int getMinY() {
         return -64;
      }
   };

   /**
    * Eye count of the stronghold at {@code ringChunk} for {@code seed}, or -1 if it can't be
    * determined. Pure computation, safe on worker threads. {@code rs} is the overworld
    * RandomState the caller already built for this seed — reusing it saves a whole noise-router
    * construction per seed (the priciest part of an eye check), which is why eye searches were slow.
    */
   public static int countEyes(HolderLookup.Provider source, WorldgenContext ctx, long seed, ChunkPos ringChunk, RandomState rs) {
      try {
         RegistryAccess registries = (RegistryAccess) source;
         Holder<Structure> stronghold = source.lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.STRONGHOLD);
         Holder<NoiseGeneratorSettings> noise = source.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
         NoiseBasedChunkGenerator gen = new NoiseBasedChunkGenerator(ctx.overworldBiomes, noise);
         Predicate<Holder<Biome>> anyBiome = h -> true;

         StructureStart start = stronghold.value().generate(
               stronghold, Level.OVERWORLD, registries, gen, ctx.overworldBiomes, rs,
               null, seed, ringChunk, 0, OVERWORLD_HEIGHT, anyBiome);
         if (start == null || !start.isValid()) {
            return -1;
         }

         StructurePiece portal = null;
         for (StructurePiece p : start.getPieces()) {
            if (p instanceof StrongholdPieces.PortalRoom) {
               portal = p;
               break;
            }
         }
         if (portal == null) {
            return -1;
         }

         // Run the real block placement for each chunk the portal room touches, seeded like
         // chunk population, and tally the eyes it lays down.
         BoundingBox box = portal.getBoundingBox();
         EyeCounter counter = new EyeCounter();
         counter.worldSeed = seed;
         counter.registries = registries;
         int minY = OVERWORLD_HEIGHT.getMinY();
         int maxY = minY + OVERWORLD_HEIGHT.getHeight() - 1;
         for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
               ChunkPos cp = new ChunkPos(cx, cz);
               BoundingBox chunkBox = new BoundingBox((cx << 4), minY, (cz << 4), (cx << 4) + 15, maxY, (cz << 4) + 15);
               WorldgenRandom wr = new WorldgenRandom(new XoroshiroRandomSource(0L));
               long deco = wr.setDecorationSeed(seed, cx << 4, cz << 4);
               wr.setFeatureSeed(deco, 0, GenerationStep.Decoration.STRONGHOLDS.ordinal());
               start.placeInChunk(counter, null, gen, wr, chunkBox, cp);
            }
         }
         return counter.frames > 0 ? counter.eyes : -1;
      } catch (Throwable t) {
         FableVisionClient.LOGGER.debug("Eye count failed for seed {}", seed, t);
         return -1;
      }
   }

   /** A throwaway WorldGenLevel that records nothing but END_PORTAL_FRAME eye states. */
   private static final class EyeCounter extends CountingLevel {
      int eyes = 0;
      int frames = 0;

      @Override
      public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
         if (state.getBlock() == Blocks.END_PORTAL_FRAME) {
            frames++;
            if (state.getValue(EndPortalFrameBlock.HAS_EYE)) {
               eyes++;
            }
         }
         return true;
      }
   }
}
