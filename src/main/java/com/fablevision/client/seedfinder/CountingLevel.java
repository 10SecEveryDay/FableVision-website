package com.fablevision.client.seedfinder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Base for {@link StrongholdEyes.EyeCounter}: a WorldGenLevel that swallows all world reads
 * (air everywhere, no block entities) and drops writes, so structure block-placement code
 * runs and consumes RNG identically without a real world. Only the RNG matters here — the
 * blocks it "places" go nowhere except the eye tally in the subclass.
 *
 * The full method set is filled in from what the compiler requires.
 */
abstract class CountingLevel implements net.minecraft.world.level.WorldGenLevel {
   protected long worldSeed;
   protected net.minecraft.core.RegistryAccess registries;

   @Override
   public long getSeed() {
      return worldSeed;
   }

   @Override
   public net.minecraft.core.RegistryAccess registryAccess() {
      return registries;
   }

   @Override
   public net.minecraft.world.flag.FeatureFlagSet enabledFeatures() {
      return net.minecraft.world.flag.FeatureFlags.VANILLA_SET;
   }

   @Override
   public net.minecraft.world.level.dimension.DimensionType dimensionType() {
      return null;
   }

   @Override
   public net.minecraft.world.attribute.EnvironmentAttributeReader environmentAttributes() {
      return null;
   }

   @Override
   public BlockState getBlockState(BlockPos pos) {
      return Blocks.AIR.defaultBlockState();
   }

   @Override
   public FluidState getFluidState(BlockPos pos) {
      return Fluids.EMPTY.defaultFluidState();
   }

   @Override
   public BlockEntity getBlockEntity(BlockPos pos) {
      return null;
   }

   @Override
   public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
      return true;
   }

   @Override
   public net.minecraft.world.DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
      return new net.minecraft.world.DifficultyInstance(net.minecraft.world.Difficulty.NORMAL, 0L, 0L, 0.0F);
   }

   @Override
   public net.minecraft.server.level.ServerLevel getLevel() {
      return null;
   }

   @Override
   public net.minecraft.world.level.storage.LevelData getLevelData() {
      return null;
   }

   @Override
   public net.minecraft.server.MinecraftServer getServer() {
      return null;
   }

   @Override
   public net.minecraft.world.level.chunk.ChunkSource getChunkSource() {
      return null;
   }

   @Override
   public net.minecraft.world.level.border.WorldBorder getWorldBorder() {
      return new net.minecraft.world.level.border.WorldBorder();
   }

   @Override
   public net.minecraft.util.RandomSource getRandom() {
      return net.minecraft.util.RandomSource.create();
   }

   @Override
   public void gameEvent(net.minecraft.core.Holder<net.minecraft.world.level.gameevent.GameEvent> event,
                         net.minecraft.world.phys.Vec3 pos, net.minecraft.world.level.gameevent.GameEvent.Context ctx) {
   }

   @Override
   public void levelEvent(net.minecraft.world.entity.Entity entity, int type, BlockPos pos, int data) {
   }

   @Override
   public void addParticle(net.minecraft.core.particles.ParticleOptions p, double x, double y, double z,
                           double vx, double vy, double vz) {
   }

   private net.minecraft.world.level.chunk.ProtoChunk scratchChunk;

   @Override
   public net.minecraft.world.level.chunk.ChunkAccess getChunk(int x, int z,
                                                               net.minecraft.world.level.chunk.status.ChunkStatus status, boolean require) {
      // Structure block placement calls getChunk(pos).markPosForPostprocessing(pos) for shape-check
      // blocks (iron bars, panes, stairs — strongholds are FULL of them). Returning null here made
      // that call NPE, which the eye counter caught and turned into "can't tell" (-1) for EVERY seed
      // — the reason an eye search found nothing. A throwaway ProtoChunk makes the call a harmless
      // no-op (we never read from it; all block reads go through this level's air/empty overrides).
      if (scratchChunk == null && registries != null) {
         scratchChunk = new net.minecraft.world.level.chunk.ProtoChunk(
               new net.minecraft.world.level.ChunkPos(x, z),
               net.minecraft.world.level.chunk.UpgradeData.EMPTY,
               this,
               net.minecraft.world.level.chunk.PalettedContainerFactory.create(registries),
               null);
      }
      return scratchChunk;
   }

   @Override
   public int getHeight(net.minecraft.world.level.levelgen.Heightmap.Types type, int x, int z) {
      return 0;
   }

   @Override
   public int getSkyDarken() {
      return 0;
   }

   @Override
   public void playSound(net.minecraft.world.entity.Entity entity, BlockPos pos, net.minecraft.sounds.SoundEvent sound,
                         net.minecraft.sounds.SoundSource source, float volume, float pitch) {
   }

   @Override
   public java.util.List<net.minecraft.world.entity.Entity> getEntities(net.minecraft.world.entity.Entity entity,
                                                                        net.minecraft.world.phys.AABB area,
                                                                        java.util.function.Predicate<? super net.minecraft.world.entity.Entity> filter) {
      return java.util.List.of();
   }

   @Override
   public java.util.List<? extends net.minecraft.world.entity.player.Player> players() {
      return java.util.List.of();
   }

   @Override
   public long nextSubTickCount() {
      return 0L;
   }

   @Override
   public void scheduleTick(BlockPos pos, net.minecraft.world.level.block.Block block, int delay) {
   }

   @Override
   public void scheduleTick(BlockPos pos, net.minecraft.world.level.material.Fluid fluid, int delay) {
   }

   @Override
   public int getSeaLevel() {
      return 63;
   }

   @Override
   public boolean isStateAtPosition(BlockPos pos, java.util.function.Predicate<BlockState> predicate) {
      return false;
   }

   @Override
   public boolean isFluidAtPosition(BlockPos pos, java.util.function.Predicate<net.minecraft.world.level.material.FluidState> predicate) {
      return false;
   }

   @Override
   public boolean isClientSide() {
      return false;
   }

   @Override
   public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine() {
      return null;
   }

   @Override
   public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getNoiseBiome(int x, int y, int z) {
      return null;
   }

   @Override
   public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getUncachedNoiseBiome(int x, int y, int z) {
      return null;
   }

   @Override
   public net.minecraft.world.level.biome.BiomeManager getBiomeManager() {
      return null;
   }

   @Override
   public int getHeight() {
      return 384;
   }

   @Override
   public int getMinY() {
      return -64;
   }

   @Override
   public boolean destroyBlock(BlockPos pos, boolean drop, net.minecraft.world.entity.Entity breaker, int recursion) {
      return false;
   }

   @Override
   public boolean removeBlock(BlockPos pos, boolean isMoving) {
      return false;
   }

   @Override
   public <T extends net.minecraft.world.entity.Entity> java.util.List<T> getEntities(
         net.minecraft.world.level.entity.EntityTypeTest<net.minecraft.world.entity.Entity, T> test,
         net.minecraft.world.phys.AABB area, java.util.function.Predicate<? super T> filter) {
      return java.util.List.of();
   }

   @Override
   public net.minecraft.world.ticks.LevelTickAccess<net.minecraft.world.level.block.Block> getBlockTicks() {
      return null;
   }

   @Override
   public net.minecraft.world.ticks.LevelTickAccess<net.minecraft.world.level.material.Fluid> getFluidTicks() {
      return null;
   }
}
