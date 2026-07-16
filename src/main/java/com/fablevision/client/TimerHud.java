package com.fablevision.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public final class TimerHud {
   private TimerHud() {
   }

   public static void register() {
      HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("fablevision", "ten_sec_timer"), (extractor, deltaTracker) -> {
         int ticks = FableVisionClient.timerTicksLeft();
         if (ticks >= 0) {
            Minecraft mc = Minecraft.getInstance();
            int seconds = (ticks + 19) / 20;
            String text = String.valueOf(seconds);
            int color = seconds <= 3 ? -43691 : -1;
            float scale = FableVisionConfig.tenSecBig ? 4.0F : 1.5F;
            float x = mc.getWindow().getGuiScaledWidth() - mc.font.width(text) * scale - 8.0F;
            float y = 8.0F;
            extractor.pose().pushMatrix();
            extractor.pose().translate(x, y);
            extractor.pose().scale(scale, scale);
            extractor.text(mc.font, text, 0, 0, color, true);
            extractor.pose().popMatrix();
         }
      });
   }
}
