package com.fablevision.client.mixin;

import com.fablevision.client.FableVisionConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {
   @Unique
   private boolean fablevision$loweredShield;

   @Inject(method = "renderArmWithItem", at = @At("HEAD"))
   private void fablevision$lowerShield(
      AbstractClientPlayer player,
      float partialTicks,
      float pitch,
      InteractionHand hand,
      float swingProgress,
      ItemStack stack,
      float equippedProgress,
      PoseStack poseStack,
      SubmitNodeCollector collector,
      int combinedLight,
      CallbackInfo ci
   ) {
      this.fablevision$loweredShield = false;
      if (FableVisionConfig.lowShield
         && !stack.isEmpty()
         && player.isUsingItem()
         && player.getUsedItemHand() == hand
         && stack.getUseAnimation() == ItemUseAnimation.BLOCK) {
         this.fablevision$loweredShield = true;
         poseStack.pushPose();
         poseStack.translate(0.0F, -FableVisionConfig.shieldOffset, 0.0F);
      }
   }

   @Inject(method = "renderArmWithItem", at = @At("RETURN"))
   private void fablevision$restoreShield(
      AbstractClientPlayer player,
      float partialTicks,
      float pitch,
      InteractionHand hand,
      float swingProgress,
      ItemStack stack,
      float equippedProgress,
      PoseStack poseStack,
      SubmitNodeCollector collector,
      int combinedLight,
      CallbackInfo ci
   ) {
      if (this.fablevision$loweredShield) {
         this.fablevision$loweredShield = false;
         poseStack.popPose();
      }
   }
}
