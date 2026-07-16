package com.fablevision.client.mixin;

import com.fablevision.client.FableVisionConfig;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightmapRenderStateExtractor.class)
public class LightmapRenderStateExtractorMixin {
   @Inject(method = "extract", at = @At("RETURN"))
   private void fablevision$fullbright(LightmapRenderState state, float partialTick, CallbackInfo ci) {
      if (FableVisionConfig.fullbright && FableVisionConfig.fullbrightIntensity > 0.0F) {
         state.nightVisionEffectIntensity = Math.max(state.nightVisionEffectIntensity, FableVisionConfig.fullbrightIntensity);
         state.needsUpdate = true;
      }
   }
}
