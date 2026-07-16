package com.fablevision.client;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class FableVisionScreen extends Screen {
   private static final int ROW = 24;
   private static final int COL_WIDTH = 150;
   private static final int COL_GAP = 10;

   public FableVisionScreen() {
      super(Component.literal("FableVision"));
   }

   @Override
   protected void init() {
      int left = this.width / 2 - 150 - 5;
      int right = this.width / 2 + 5;
      int y = this.height / 6 + 10;
      this.addToggle(
         left,
         y,
         "Low Fire",
         "Moves the first-person fire overlay down so it doesn't block your view while burning.",
         () -> FableVisionConfig.lowFire,
         value -> FableVisionConfig.lowFire = value
      );
      this.addRenderableWidget(
         new FableVisionScreen.OffsetSlider(right, y, "Fire offset", FableVisionConfig.fireOffset, 0.6F, false, value -> FableVisionConfig.fireOffset = value)
      );
      this.addToggle(
         left,
         y + 24,
         "Low Shield",
         "Lowers the shield while you're actively blocking so it doesn't cover half the screen.",
         () -> FableVisionConfig.lowShield,
         value -> FableVisionConfig.lowShield = value
      );
      this.addRenderableWidget(
         new FableVisionScreen.OffsetSlider(
            right, y + 24, "Shield offset", FableVisionConfig.shieldOffset, 1.0F, false, value -> FableVisionConfig.shieldOffset = value
         )
      );
      this.addToggle(
         left,
         y + 48,
         "Fullbright",
         "Boosts the lightmap like permanent Night Vision. Use the Brightness slider to go from subtle to fully bright.",
         () -> FableVisionConfig.fullbright,
         value -> FableVisionConfig.fullbright = value
      );
      this.addRenderableWidget(
         new FableVisionScreen.OffsetSlider(
            right, y + 48, "Brightness", FableVisionConfig.fullbrightIntensity, 1.0F, true, value -> FableVisionConfig.fullbrightIntensity = value
         )
      );
      this.addToggle(
         left,
         y + 72,
         "No Explosions",
         "Hides explosion particles (end crystals, TNT) so you can actually see during crystal PvP.",
         () -> FableVisionConfig.noExplosionParticles,
         value -> FableVisionConfig.noExplosionParticles = value
      );
      this.addToggle(
         right,
         y + 72,
         "AppleSkin HUD",
         "AppleSkin-style food info: gold saturation overlay on the hunger bar and hunger/saturation tooltips on food.",
         () -> FableVisionConfig.appleSkin,
         value -> FableVisionConfig.appleSkin = value
      );
      this.addToggle(
         left,
         y + 96,
         "10s Timer",
         "Counts down from 10 when you join the bound world, then auto-leaves it. Bind a world with /10sec here; /10sec toggles the size.",
         () -> FableVisionConfig.tenSecTimer,
         value -> {
            FableVisionConfig.tenSecTimer = value;
            FableVisionClient.restartTimerCheck();
         }
      );
      Button sizeButton = Button.builder(timerSizeLabel(), b -> {
         FableVisionConfig.tenSecBig = !FableVisionConfig.tenSecBig;
         b.setMessage(timerSizeLabel());
      }).bounds(right, y + 96, 150, 20).build();
      sizeButton.setTooltip(Tooltip.create(Component.literal("How large the countdown is drawn in the corner. Also toggled by /10sec.")));
      this.addRenderableWidget(sizeButton);
      Button lanToggle = this.addToggle(
         left,
         y + 120,
         "Auto LAN + Bedrock",
         "Automatically opens every singleplayer world to LAN when you join it, so friends on your Wi-Fi can hop in. With Geyser-Fabric loaded, Bedrock players can join too.",
         () -> FableVisionConfig.autoLan,
         value -> FableVisionConfig.autoLan = value
      );
      Button lanNowButton = Button.builder(Component.literal("Open to LAN now"), b -> {
         this.onClose();
         LanBedrock.openToLan(this.minecraft);
      }).bounds(right, y + 120, 150, 20).build();
      lanNowButton.setTooltip(
         Tooltip.create(
            Component.literal(
               LanBedrock.geyserInstalled()
                  ? "Opens this world to LAN right now and prints the addresses Java + Bedrock players use to join. Also: /client lan"
                  : "Opens this world to LAN right now (Java players only). Geyser is NOT loaded - re-enable Geyser-Fabric in your mods folder for Bedrock players."
            )
         )
      );
      this.addRenderableWidget(lanNowButton);
      // LAN sharing only applies to worlds YOU host — grey the whole row out on servers.
      if (this.minecraft != null && LanBedrock.onRemoteServer(this.minecraft)) {
         Component whyDisabled = Component.literal(
            "LAN sharing only works in your own single-player world. It's disabled on servers to protect your connection and prevent exposing your network.");
         lanToggle.active = false;
         lanToggle.setTooltip(Tooltip.create(whyDisabled));
         lanNowButton.active = false;
         lanNowButton.setTooltip(Tooltip.create(whyDisabled));
      }
      int bottomY = y + 144 + 12;
      // The seed finder now lives only on the Create World screen ("✨ Custom spawn"), so there's
      // no standalone button here anymore — "How it works" explains where to find it.
      this.addRenderableWidget(Button.builder(Component.literal("❓ How it works"), b -> this.minecraft.setScreen(new HelpScreen(this)))
         .bounds(this.width / 2 - 75, bottomY, 150, 20).build());
      this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).bounds(this.width / 2 - 75, bottomY + 24, 150, 20).build());
   }

   private Button addToggle(int x, int y, String name, String tooltip, BooleanSupplier getter, Consumer<Boolean> setter) {
      Button button = Button.builder(toggleLabel(name, getter.getAsBoolean()), b -> {
         boolean newValue = !getter.getAsBoolean();
         setter.accept(newValue);
         b.setMessage(toggleLabel(name, newValue));
      }).bounds(x, y, 150, 20).build();
      button.setTooltip(Tooltip.create(Component.literal(tooltip)));
      this.addRenderableWidget(button);
      return button;
   }

   private static Component timerSizeLabel() {
      return Component.literal("Timer Size: ").append(Component.literal(FableVisionConfig.tenSecBig ? "BIG" : "Small").withStyle(ChatFormatting.YELLOW));
   }

   private static Component toggleLabel(String name, boolean on) {
      return Component.literal(name + ": ")
         .append(on ? Component.literal("ON").withStyle(ChatFormatting.GREEN) : Component.literal("OFF").withStyle(ChatFormatting.RED));
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(extractor, mouseX, mouseY, partialTick);
      extractor.centeredText(this.font, this.title, this.width / 2, this.height / 6 - 14, -1);
   }

   @Override
   public void onClose() {
      FableVisionConfig.save();
      super.onClose();
   }

   private static class OffsetSlider extends AbstractSliderButton {
      private final String name;
      private final float max;
      private final boolean percent;
      private final Consumer<Float> setter;

      OffsetSlider(int x, int y, String name, float current, float max, boolean percent, Consumer<Float> setter) {
         super(x, y, 150, 20, CommonComponents.EMPTY, current / max);
         this.name = name;
         this.max = max;
         this.percent = percent;
         this.setter = setter;
         this.updateMessage();
      }

      @Override
      protected void updateMessage() {
         float actual = (float)(this.value * this.max);
         this.setMessage(
            Component.literal(
               this.percent
                  ? String.format(Locale.ROOT, "%s: %d%%", this.name, Math.round(actual * 100.0F))
                  : String.format(Locale.ROOT, "%s: %.2f", this.name, actual)
            )
         );
      }

      @Override
      protected void applyValue() {
         this.setter.accept((float)(this.value * this.max));
      }
   }
}
