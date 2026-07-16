package com.fablevision.client.mixin;

import com.fablevision.client.FableVisionConfig;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class GuiMixin {
   @Unique
   private static final Identifier FABLEVISION$FOOD_FULL = Identifier.withDefaultNamespace("hud/food_full");
   @Unique
   private static final Identifier FABLEVISION$FOOD_HALF = Identifier.withDefaultNamespace("hud/food_half");
   @Unique
   private static final int FABLEVISION$SATURATION_COLOR = -587216859;

   @Inject(method = "extractFood", at = @At("TAIL"))
   private void fablevision$foodOverlays(GuiGraphicsExtractor extractor, Player player, int y, int right, CallbackInfo ci) {
      if (FableVisionConfig.appleSkin) {
         float saturation = player.getFoodData().getSaturationLevel();

         for (int i = 0; i < 10; i++) {
            float covered = saturation - i * 2;
            if (covered <= 0.0F) {
               break;
            }

            int x = right - i * 8 - 9;
            Identifier sprite = covered >= 2.0F ? FABLEVISION$FOOD_FULL : FABLEVISION$FOOD_HALF;
            extractor.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, 9, 9, -587216859);
         }

         FoodProperties food = fablevision$heldFood(player);
         if (food != null && food.nutrition() > 0) {
            int foodLevel = player.getFoodData().getFoodLevel();
            int restoredTo = Math.min(20, foodLevel + food.nutrition());
            if (restoredTo > foodLevel) {
               float pulse = (float)(Math.sin(System.currentTimeMillis() / 150.0) * 0.5 + 0.5);
               int alpha = 48 + (int)(pulse * 160.0F);
               int flashColor = alpha << 24 | 16777215;

               for (int i = foodLevel / 2; i * 2 < restoredTo; i++) {
                  int x = right - i * 8 - 9;
                  Identifier sprite = restoredTo >= i * 2 + 2 ? FABLEVISION$FOOD_FULL : FABLEVISION$FOOD_HALF;
                  extractor.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, 9, 9, flashColor);
               }
            }
         }
      }
   }

   @Unique
   private static FoodProperties fablevision$heldFood(Player player) {
      ItemStack main = player.getMainHandItem();
      FoodProperties food = main.get(DataComponents.FOOD);
      return food != null ? food : player.getOffhandItem().get(DataComponents.FOOD);
   }
}
