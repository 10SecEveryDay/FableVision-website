package com.fablevision.client;

import java.util.Locale;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.food.FoodProperties;

public final class FoodTooltips {
   private FoodTooltips() {
   }

   public static void register() {
      ItemTooltipCallback.EVENT
         .register(
            (ItemTooltipCallback)(stack, context, flag, lines) -> {
               if (FableVisionConfig.appleSkin) {
                  FoodProperties food = stack.get(DataComponents.FOOD);
                  if (food != null) {
                     lines.add(
                        Component.literal("+" + food.nutrition() + " Hunger  ")
                           .withStyle(ChatFormatting.GOLD)
                           .append(Component.literal(String.format(Locale.ROOT, "+%.1f Saturation", food.saturation())).withStyle(ChatFormatting.AQUA))
                     );
                  }
               }
            }
         );
   }
}
