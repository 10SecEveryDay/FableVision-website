package com.fablevision.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * The one-time FABLEVISION splash, shown the first time the mod runs (per install). Drawn
 * as a HUD overlay (same approach as {@link TimerHud}) so it plays over the game without
 * opening a screen or stealing input.
 *
 * The sequence (~5.5s, {@link FableVisionClient#WELCOME_TICKS} ticks):
 *   1. "WELCOME TO" fades in and settles.
 *   2. FABLEVISION types itself out letter by letter with a blinking cursor — like a
 *      command being typed — in the brand ghost-cyan.
 *   3. A row of ghost blocks assembles beneath it left-to-right (the builder's signature),
 *      then keeps shimmering.
 *   4. "by 10SecEveryDay" and a quick-start hint fade in; everything fades out together.
 */
public final class WelcomeHud {
   private static final String WORD = "FABLEVISION";
   private static final int TYPE_START = 8;   // tick the typewriter begins
   private static final int TYPE_RATE = 3;    // ticks per letter

   private WelcomeHud() {
   }

   public static void register() {
      HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("fablevision", "welcome"), (extractor, deltaTracker) -> {
         int ticks = FableVisionClient.welcomeTicksLeft();
         if (ticks <= 0) {
            return;
         }
         Minecraft mc = Minecraft.getInstance();
         if (mc.font == null) {
            return;
         }
         int total = FableVisionClient.WELCOME_TICKS;
         int elapsed = total - ticks;

         // Global alpha: quick fade-in, hold, fade out over the last 18 ticks.
         float a = 1.0F;
         if (elapsed < 8) {
            a = elapsed / 8.0F;
         } else if (ticks < 18) {
            a = ticks / 18.0F;
         }
         a = Math.max(0.0F, Math.min(1.0F, a));
         if ((int) (a * 255.0F) <= 6) {
            return;
         }

         float cx = mc.getWindow().getGuiScaledWidth() / 2.0F;
         float cy = mc.getWindow().getGuiScaledHeight() * 0.40F;
         float big = 3.1F;

         // 1) "WELCOME TO" — eases down into place while fading in.
         float wa = Math.min(1.0F, elapsed / 12.0F);
         int white = (int) (a * wa * 255.0F) << 24 | 0xFFFFFF;
         drawCentered(extractor, mc, "WELCOME TO", cx, cy - 26.0F - (1.0F - wa) * 6.0F, 1.3F, white);

         // 2) FABLEVISION types in, letter by letter, with a blinking cursor while typing.
         int shown = elapsed < TYPE_START ? 0
               : Math.min(WORD.length(), (elapsed - TYPE_START) / TYPE_RATE + 1);
         boolean typing = shown < WORD.length();
         if (shown > 0) {
            String vis = WORD.substring(0, shown);
            if (typing && (elapsed / TYPE_RATE) % 2 == 0) {
               vis += "_";
            }
            int cyan = (int) (a * 255.0F) << 24 | 0x53E0D0;
            // Centered on the FINAL width so letters appear in place instead of sliding.
            float fullW = mc.font.width(WORD) * big;
            extractor.pose().pushMatrix();
            extractor.pose().translate(cx - fullW / 2.0F, cy);
            extractor.pose().scale(big, big);
            extractor.text(mc.font, vis, 0, 0, cyan, true);
            extractor.pose().popMatrix();
         }

         // 3) Ghost blocks assemble under the name (one per letter), then shimmer softly.
         float rowY = cy + 8.0F * big + 7.0F;
         int blocks = WORD.length();
         float bScale = 1.15F;
         float bw = mc.font.width("■") * bScale + 3.0F;
         float rowX = cx - (blocks * bw - 3.0F) / 2.0F;
         for (int i = 0; i < blocks; i++) {
            int appearAt = TYPE_START + 4 + i * TYPE_RATE; // trails just behind the letters
            if (elapsed <= appearAt) {
               continue;
            }
            float in = Math.min(1.0F, (elapsed - appearAt) / 4.0F);
            float shimmer = 0.62F + 0.38F * (float) Math.sin(elapsed * 0.33F - i * 0.7F);
            int ba = (int) (a * in * shimmer * 255.0F);
            if (ba <= 6) {
               continue;
            }
            int color = ba << 24 | 0x2FD4C6;
            extractor.pose().pushMatrix();
            extractor.pose().translate(rowX + i * bw, rowY);
            extractor.pose().scale(bScale, bScale);
            extractor.text(mc.font, "■", 0, 0, color, false);
            extractor.pose().popMatrix();
         }

         // 4) Credit + quick-start hint, once the name has finished typing.
         if (elapsed > 52) {
            float ca = Math.min(1.0F, (elapsed - 52) / 10.0F);
            int amber = (int) (a * ca * 235.0F) << 24 | 0xFFB454;
            drawCentered(extractor, mc, "by 10SecEveryDay", cx, rowY + 16.0F, 1.0F, amber);
         }
         if (elapsed > 64) {
            float ha = Math.min(1.0F, (elapsed - 64) / 10.0F);
            int gray = (int) (a * ha * 200.0F) << 24 | 0xC2D0DA;
            drawCentered(extractor, mc, "press G to ask AI  ·  /client for settings  ·  ✨ Custom spawn on Create World",
                  cx, rowY + 30.0F, 1.0F, gray);
         }
      });
   }

   private static void drawCentered(GuiGraphicsExtractor extractor, Minecraft mc, String text, float cx, float y, float scale, int color) {
      float w = mc.font.width(text) * scale;
      extractor.pose().pushMatrix();
      extractor.pose().translate(cx - w / 2.0F, y);
      extractor.pose().scale(scale, scale);
      extractor.text(mc.font, text, 0, 0, color, true);
      extractor.pose().popMatrix();
   }
}
