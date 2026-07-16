package com.fablevision.client.mixin;

import com.fablevision.client.FableVisionConfig;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ParticleEngine.class)
public class ParticleEngineMixin {
   @Inject(
      method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;",
      at = @At("HEAD"),
      cancellable = true
   )
   private void fablevision$hideExplosionParticles(
      ParticleOptions options, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed, CallbackInfoReturnable<Particle> cir
   ) {
      if (FableVisionConfig.noExplosionParticles && (options.getType() == ParticleTypes.EXPLOSION_EMITTER || options.getType() == ParticleTypes.EXPLOSION)) {
         cir.setReturnValue(null);
      }
   }
}
