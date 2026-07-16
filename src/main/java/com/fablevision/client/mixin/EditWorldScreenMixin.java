package com.fablevision.client.mixin;

import com.fablevision.client.FableVisionClient;
import com.fablevision.client.WorldTools;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.util.Locale;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EditWorldScreen.class)
public abstract class EditWorldScreenMixin extends Screen {
   @Shadow
   @Final
   private LevelStorageAccess levelAccess;
   @Shadow
   @Final
   private BooleanConsumer callback;
   @Unique
   private Button fablevision$duplicateButton;
   @Unique
   private Button fablevision$gamemodeButton;
   @Unique
   private GameType fablevision$gameType;
   @Unique
   private volatile boolean fablevision$duplicating;

   protected EditWorldScreenMixin(Component title) {
      super(title);
   }

   @Inject(method = "init", at = @At("TAIL"))
   private void fablevision$addWorldTools(CallbackInfo ci) {
      this.fablevision$gameType = WorldTools.readGameType(this.levelAccess);
      this.fablevision$duplicateButton = Button.builder(Component.literal("Duplicate World"), button -> this.fablevision$duplicate())
         .bounds(0, 0, 130, 20)
         .tooltip(
            Tooltip.create(
               Component.literal("True copy: the whole world folder with all builds, items, position and progress. Shows up in the list right away.")
            )
         )
         .build();
      this.fablevision$gamemodeButton = Button.builder(this.fablevision$gamemodeLabel(), button -> this.fablevision$askGamemode())
         .bounds(0, 0, 130, 20)
         .tooltip(Tooltip.create(Component.literal("Changes this world's gamemode. Applies the next time you open the world.")))
         .build();
      this.fablevision$position();
      this.addRenderableWidget(this.fablevision$duplicateButton);
      this.addRenderableWidget(this.fablevision$gamemodeButton);
   }

   @Inject(method = "repositionElements", at = @At("TAIL"))
   private void fablevision$reposition(CallbackInfo ci) {
      this.fablevision$position();
   }

   @Unique
   private void fablevision$position() {
      if (this.fablevision$duplicateButton != null) {
         int x = this.width / 2 + 106;
         int y = this.height / 4;
         this.fablevision$duplicateButton.setPosition(x, y);
         this.fablevision$gamemodeButton.setPosition(x, y + 24);
      }
   }

   @Unique
   private void fablevision$duplicate() {
      if (!this.fablevision$duplicating) {
         this.fablevision$duplicating = true;
         this.fablevision$duplicateButton.active = false;
         this.fablevision$duplicateButton.setMessage(Component.literal("Duplicating..."));
         Thread worker = new Thread(() -> {
            try {
               String newId = WorldTools.duplicate(this.levelAccess);
               FableVisionClient.LOGGER.info("Duplicated world '{}' -> '{}'", this.levelAccess.getLevelId(), newId);
               this.minecraft.execute(() -> this.callback.accept(true));
            } catch (Exception e) {
               FableVisionClient.LOGGER.error("World duplication failed", e);
               this.minecraft.execute(() -> {
                  this.fablevision$duplicating = false;
                  this.fablevision$duplicateButton.active = true;
                  this.fablevision$duplicateButton.setMessage(Component.literal("Duplicate failed (see log)"));
               });
            }
         }, "FableVision-WorldDuplicate");
         worker.setDaemon(true);
         worker.start();
      }
   }

   @Unique
   private void fablevision$askGamemode() {
      GameType next = GameType.byId((this.fablevision$gameType.getId() + 1) % 4);
      Screen self = this;
      this.minecraft
         .setScreen(
            new ConfirmScreen(
               confirmed -> {
                  if (confirmed) {
                     try {
                        WorldTools.writeGameType(this.levelAccess, next);
                        this.fablevision$gameType = next;
                        this.fablevision$gamemodeButton.setMessage(this.fablevision$gamemodeLabel());
                     } catch (Exception e) {
                        FableVisionClient.LOGGER.error("Could not change gamemode", e);
                        this.fablevision$gamemodeButton.setMessage(Component.literal("Gamemode change failed"));
                     }
                  }

                  this.minecraft.setScreen(self);
               },
               Component.literal("Change world gamemode?"),
               Component.literal(
                  "Set \""
                     + this.levelAccess.getLevelId()
                     + "\" to "
                     + fablevision$gamemodeName(next)
                     + "? This also changes your player's gamemode and applies the next time you open the world."
               )
            )
         );
   }

   @Unique
   private Component fablevision$gamemodeLabel() {
      return Component.literal("Gamemode: " + fablevision$gamemodeName(this.fablevision$gameType));
   }

   @Unique
   private static String fablevision$gamemodeName(GameType type) {
      String name = type.getName();
      return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
   }
}
