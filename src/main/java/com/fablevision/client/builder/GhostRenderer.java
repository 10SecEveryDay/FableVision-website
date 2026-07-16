package com.fablevision.client.builder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Draws the builder's per-block "ghost" preview the Litematica way: each not-yet-placed block
 * is rendered as its REAL textured model (you actually see oak planks / stone / bricks), wrapped
 * in a thin outline so you can tell it's a schematic ghost and not a real block. The outline is
 * cyan normally and red when the build is stalled waiting for materials. Ghosts vanish as blocks
 * get placed.
 *
 * Implementation (26.1.2 reworked render pipeline — own code, no Litematica dependency):
 *   • We resolve each block's model with ModelManager.getBlockStateModelSet().get(state), pull
 *     its baked quads (BlockStateModelPart.getQuads) and write them straight into a VertexConsumer
 *     with VertexConsumer.putBakedQuad(...). The buffer comes from the render context's buffer
 *     source bound to Sheets.cutoutBlockSheet() — that render type binds the block texture atlas,
 *     so the quads come out fully textured (the earlier deferred-submit path drew them white
 *     because the atlas wasn't bound at flush time).
 *   • The outline is immediate-mode LINES (ShapeRenderer + RenderTypes.LINES).
 * Both draw during LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN via the context buffer source,
 * flushed with endBatch — no mixins, no custom pipeline, no deferred nodes.
 */
public final class GhostRenderer {
   private GhostRenderer() {
   }

   private static final int RADIUS = 24;      // only draw ghosts within this many blocks
   private static final int CAP = 384;        // max ghosts per frame (models re-resolved each frame)
   private static final int GATHER = 4096;    // candidates collected before keeping the CLOSEST CAP
   private static final int MAX_SCAN = 60000; // bound the scan on huge schematics
   private static final int LIGHT = 0x00F000F0; // full-bright, so ghosts are always visible (even in caves)
   private static final int NO_TINT = -1;       // 0xFFFFFFFF — draw the model at its true texture colour
   private static final long SEED = 42L;        // fixed model-variant seed (a preview doesn't need per-pos variety)
   // Outline colours (ARGB): cyan when ready, red when stalled on materials.
   private static final int EDGE_READY = 0xDD55CCFF;
   private static final int EDGE_STALLED = 0xDDFF5555;

   private static final Direction[] DIRS = Direction.values();

   /** One ghost: where it goes in the world and which block it should be. */
   private record Ghost(BlockPos pos, BlockState state) {
   }

   public static void register() {
      LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(GhostRenderer::onRender);
   }

   private static void onRender(LevelRenderContext ctx) {
      Minecraft mc = Minecraft.getInstance();
      List<Ghost> ghosts = collect(mc);
      if (ghosts == null) return;

      Vec3 cam = mc.gameRenderer.getMainCamera().position();
      PoseStack pose = ctx.poseStack();
      MultiBufferSource.BufferSource buffers = ctx.bufferSource();

      drawModels(mc, ghosts, cam, pose, buffers);
      drawOutlines(ghosts, cam, pose, buffers);
   }

   /** The textured block models — solid/cutout quads first, then translucent quads (stained
    *  glass, ice, …) on the translucent sheet so they alpha-blend instead of being alpha-TESTED
    *  away (on the cutout sheet their semi-transparent pixels were simply discarded). */
   private static void drawModels(Minecraft mc, List<Ghost> ghosts, Vec3 cam, PoseStack pose,
                                  MultiBufferSource.BufferSource buffers) {
      BlockStateModelSet models = mc.getModelManager().getBlockStateModelSet();
      QuadInstance quad = new QuadInstance();
      quad.setColor(NO_TINT);
      quad.setLightCoords(LIGHT);
      quad.setOverlayCoords(OverlayTexture.NO_OVERLAY);
      RandomSource random = RandomSource.create();
      List<BlockStateModelPart> parts = new ArrayList<>();

      drawLayer(models, ghosts, cam, pose, buffers, Sheets.cutoutBlockSheet(), false, quad, random, parts);
      drawLayer(models, ghosts, cam, pose, buffers, Sheets.translucentBlockSheet(), true, quad, random, parts);
   }

   /** One pass over the ghosts emitting only quads of the wanted layer (translucent or not).
    *  Two passes beat interleaving: a BufferSource flushes whenever the render type changes. */
   private static void drawLayer(BlockStateModelSet models, List<Ghost> ghosts, Vec3 cam, PoseStack pose,
                                 MultiBufferSource.BufferSource buffers, RenderType sheet, boolean translucent,
                                 QuadInstance quad, RandomSource random, List<BlockStateModelPart> parts) {
      VertexConsumer vc = buffers.getBuffer(sheet);
      for (Ghost g : ghosts) {
         BlockStateModel model = models.get(g.state());
         if (model == null) continue;
         parts.clear();
         random.setSeed(SEED);
         model.collectParts(random, parts);
         if (parts.isEmpty()) continue;
         pose.pushPose();
         pose.translate(g.pos().getX() - cam.x, g.pos().getY() - cam.y, g.pos().getZ() - cam.z);
         PoseStack.Pose last = pose.last();
         for (BlockStateModelPart part : parts) {
            emit(vc, last, quad, part.getQuads(null), translucent); // general (non-cull) quads
            for (Direction d : DIRS) emit(vc, last, quad, part.getQuads(d), translucent);
         }
         pose.popPose();
      }
      buffers.endBatch(sheet);
   }

   private static void emit(VertexConsumer vc, PoseStack.Pose pose, QuadInstance quad,
                            List<BakedQuad> quads, boolean translucent) {
      for (int i = 0, n = quads.size(); i < n; i++) {
         BakedQuad q = quads.get(i);
         if ((q.materialInfo().layer() == ChunkSectionLayer.TRANSLUCENT) == translucent) {
            vc.putBakedQuad(pose, q, quad);
         }
      }
   }

   /** Thin outline around each ghost so you can tell it apart from a placed block. */
   private static void drawOutlines(List<Ghost> ghosts, Vec3 cam, PoseStack pose,
                                    MultiBufferSource.BufferSource buffers) {
      RenderType lineType = RenderTypes.LINES;
      VertexConsumer lines = buffers.getBuffer(lineType);
      int edge = isStalled() ? EDGE_STALLED : EDGE_READY;
      VoxelShape cube = Shapes.block();
      for (Ghost g : ghosts) {
         ShapeRenderer.renderShape(pose, lines, cube,
               g.pos().getX() - cam.x, g.pos().getY() - cam.y, g.pos().getZ() - cam.z, edge, 1.0f);
      }
      buffers.endBatch(lineType);
   }

   private static boolean isStalled() {
      for (BuilderManager.Loaded l : BuilderManager.loadedList) {
         BuildTask t = l.task;
         if (t != null && t.state == BuildTask.State.WAITING_MATERIALS) return true;
      }
      return false;
   }

   /** Gathers the eligible (unplaced, nearby) ghosts across ALL staged schematics in this
    *  dimension. Returns null when there's nothing to draw. Total work is bounded by CAP/MAX_SCAN. */
   private static List<Ghost> collect(Minecraft mc) {
      if (!BuilderManager.showGhost) return null;
      if (mc.level == null || mc.player == null || BuilderManager.onRemoteOrNoWorld()) return null;
      if (BuilderManager.loadedList.isEmpty()) return null;

      ResourceKey<Level> here = mc.level.dimension();
      BlockPos ppos = mc.player.blockPosition();
      long r2 = (long) RADIUS * RADIUS;

      List<Ghost> out = new ArrayList<>();
      int scanned = 0;
      for (BuilderManager.Loaded l : BuilderManager.loadedList) {
         if (!l.dimension.equals(here)) continue; // ghosts only show in the world they were staged in
         BlockPos origin = l.origin;
         List<Schematic.Placement> ps = l.schem.placements;
         int n = ps.size();
         if (n == 0) continue;
         int startIdx = l.task != null ? Math.min(l.task.index, n) : 0; // building: show the frontier
         for (int i = startIdx; i < n && out.size() < GATHER && scanned < MAX_SCAN; i++) {
            scanned++;
            Schematic.Placement p = ps.get(i);
            BlockPos wp = origin.offset(p.pos().getX(), p.pos().getY(), p.pos().getZ());
            if (ppos.distSqr(wp) > r2) continue;
            if (mc.level.getBlockState(wp) == p.state()) continue; // already built → no ghost
            out.add(new Ghost(wp, p.state()));
         }
         if (out.size() >= GATHER || scanned >= MAX_SCAN) break;
      }
      // Over the per-frame cap → keep the ghosts CLOSEST to the player. (Placements are stored
      // bottom-up, so capping in list order starved everything high up — walk close to a big
      // build and its glass/windows never got a slot; step back and they'd pop in.)
      if (out.size() > CAP) {
         out.sort(Comparator.comparingDouble(g -> ppos.distSqr(g.pos())));
         out.subList(CAP, out.size()).clear();
      }
      return out.isEmpty() ? null : out;
   }
}
