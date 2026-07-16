package com.fablevision.client.mixin;

import com.fablevision.client.FableVisionConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectRendererMixin {
   @Inject(method = "renderFire", at = @At("HEAD"))
   private static void fablevision$lowerFire(PoseStack poseStack, MultiBufferSource bufferSource, TextureAtlasSprite sprite, CallbackInfo ci) {
      if (FableVisionConfig.lowFire) {
         poseStack.pushPose();
         poseStack.translate(0.0F, -FableVisionConfig.fireOffset, 0.0F);
      }
   }

   @Inject(method = "renderFire", at = @At("RETURN"))
   private static void fablevision$restoreFire(PoseStack poseStack, MultiBufferSource bufferSource, TextureAtlasSprite sprite, CallbackInfo ci) {
      if (FableVisionConfig.lowFire) {
         poseStack.popPose();
      }
   }
}
