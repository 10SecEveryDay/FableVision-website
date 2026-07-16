package com.fablevision.client;

import com.fablevision.client.seedfinder.SeedFinder;
import com.fablevision.client.seedfinder.SeedFinderScreen;
import com.fablevision.client.seedfinder.SpawnWishScreen;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.lang.ref.WeakReference;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndTick;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FableVisionClient implements ClientModInitializer {
   public static final String MOD_ID = "fablevision";
   public static final Logger LOGGER = LoggerFactory.getLogger("fablevision");
   private static final int TIMER_START_TICKS = 200;
   private static Screen queuedScreen;
   private static int timerTicks = -1;
   private static String lastWorldKey;
   private static int lanDelayTicks = -1;
   private static WeakReference<Screen> askedCreateScreen = new WeakReference<>(null);
   public static final int WELCOME_TICKS = 110; // ~5.5s one-time FABLEVISION splash (typewriter + hold + fade)

   /** Our bolted-on Create World buttons, tracked per screen so a re-init (window resize /
    *  maximize can re-fire init on the SAME screen without clearing widgets) REPLACES them
    *  instead of stacking a duplicate at the old width's coordinates. */
   private record SpawnButtons(Button spawn, Button badge, Button stats) {}
   private static final java.util.Map<Screen, SpawnButtons> SPAWN_BUTTONS =
         java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
   private static int welcomeTicks = 0;

   /** Opens a screen on the next client tick (safe from command handlers). */
   public static void queueScreen(Screen screen) {
      queuedScreen = screen;
   }

   public static int timerTicksLeft() {
      return timerTicks;
   }

   public static int welcomeTicksLeft() {
      return welcomeTicks;
   }

   public static void restartTimerCheck() {
      lastWorldKey = null;
   }

   @Override
   public void onInitializeClient() {
      FableVisionConfig.load();
      FoodTooltips.register();
      TimerHud.register();
      WelcomeHud.register();
      com.fablevision.client.builder.BuilderManager.register();
      com.fablevision.client.builder.BuilderCommands.register();
      ClientCommandRegistrationCallback.EVENT
         .register(
            (ClientCommandRegistrationCallback)(dispatcher, registryAccess) -> {
               dispatcher.register(
                  (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)ClientCommands.literal("client").executes(context -> {
                           queuedScreen = new FableVisionScreen();
                           return 1;
                        }))
                        .then(
                           ClientCommands.literal("on")
                              .executes(
                                 context -> {
                                    FableVisionConfig.setAll(true);
                                    FableVisionConfig.save();
                                    ((FabricClientCommandSource)context.getSource())
                                       .sendFeedback(
                                          Component.literal("FableVision: all features ")
                                             .append(Component.literal("ON").withStyle(ChatFormatting.GREEN))
                                             .append(Component.literal(" (10s timer is separate - /10sec on)").withStyle(ChatFormatting.GRAY))
                                       );
                                    return 1;
                                 }
                              )
                        ))
                     .then(
                        ClientCommands.literal("off")
                           .executes(
                              context -> {
                                 FableVisionConfig.setAll(false);
                                 FableVisionConfig.save();
                                 ((FabricClientCommandSource)context.getSource())
                                    .sendFeedback(
                                       Component.literal("FableVision: all features ").append(Component.literal("OFF").withStyle(ChatFormatting.RED))
                                    );
                                 return 1;
                              }
                           )
                     )
                     .then(
                        ClientCommands.literal("lan")
                           .executes(
                              context -> {
                                 LanBedrock.openToLan(((FabricClientCommandSource)context.getSource()).getClient());
                                 return 1;
                              }
                           )
                     )
               );
               dispatcher.register(
                  (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)ClientCommands.literal(
                                    "10sec"
                                 )
                                 .executes(
                                    context -> {
                                       FableVisionConfig.tenSecBig = !FableVisionConfig.tenSecBig;
                                       FableVisionConfig.save();
                                       ((FabricClientCommandSource)context.getSource())
                                          .sendFeedback(
                                             Component.literal("10s timer size: ")
                                                .append(Component.literal(FableVisionConfig.tenSecBig ? "BIG" : "SMALL").withStyle(ChatFormatting.YELLOW))
                                          );
                                       return 1;
                                    }
                                 ))
                              .then(
                                 ClientCommands.literal("here")
                                    .executes(
                                       context -> {
                                          String key = currentWorldKey(((FabricClientCommandSource)context.getSource()).getClient());
                                          if (key == null) {
                                             ((FabricClientCommandSource)context.getSource())
                                                .sendError(Component.literal("Join a world first, then run /10sec here"));
                                             return 0;
                                          } else {
                                             FableVisionConfig.tenSecWorld = key;
                                             FableVisionConfig.tenSecTimer = true;
                                             FableVisionConfig.save();
                                             restartTimerCheck();
                                             ((FabricClientCommandSource)context.getSource())
                                                .sendFeedback(
                                                   Component.literal("10s timer bound to ")
                                                      .append(Component.literal(key).withStyle(ChatFormatting.YELLOW))
                                                      .append(Component.literal(" and enabled"))
                                                );
                                             return 1;
                                          }
                                       }
                                    )
                              ))
                           .then(
                              ClientCommands.literal("any")
                                 .executes(
                                    context -> {
                                       FableVisionConfig.tenSecWorld = "";
                                       FableVisionConfig.save();
                                       restartTimerCheck();
                                       ((FabricClientCommandSource)context.getSource())
                                          .sendFeedback(
                                             Component.literal("10s timer now active in ")
                                                .append(Component.literal("every world").withStyle(ChatFormatting.YELLOW))
                                          );
                                       return 1;
                                    }
                                 )
                           ))
                        .then(
                           ClientCommands.literal("on")
                              .executes(
                                 context -> {
                                    FableVisionConfig.tenSecTimer = true;
                                    FableVisionConfig.save();
                                    restartTimerCheck();
                                    ((FabricClientCommandSource)context.getSource())
                                       .sendFeedback(Component.literal("10s timer ").append(Component.literal("ON").withStyle(ChatFormatting.GREEN)));
                                    return 1;
                                 }
                              )
                        ))
                     .then(
                        ClientCommands.literal("off")
                           .executes(
                              context -> {
                                 FableVisionConfig.tenSecTimer = false;
                                 FableVisionConfig.save();
                                 ((FabricClientCommandSource)context.getSource())
                                    .sendFeedback(Component.literal("10s timer ").append(Component.literal("OFF").withStyle(ChatFormatting.RED)));
                                 return 1;
                              }
                           )
                     )
               );
               // Standalone /seedfind removed by request — the seed finder is offered right on the
               // Create World screen ("✨ Custom spawn"), which is where it's actually used.
            }
         );
      // Before you create a singleplayer world: offer a custom spawn (structure/biome AT
      // spawn via a legit found seed). "No thanks" and a Don't-ask toggle are right there.
      ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
         if (screen instanceof CreateWorldScreen createScreen) {
            // Resizes are messy here: some paths reposition the layout WITHOUT re-init (our
            // widgets keep stale coordinates and "drift" to the top-middle), others re-fire
            // init on the SAME screen instance WITHOUT clearing bolted-on widgets (a
            // DUPLICATE appears). Cure both: remove whatever we added before, add fresh,
            // and let one per-screen tick handler re-anchor the current set to live width.
            SpawnButtons prev = SPAWN_BUTTONS.get(screen);
            if (prev != null) {
               Screens.getWidgets(screen).remove(prev.spawn());
               if (prev.badge() != null) {
                  Screens.getWidgets(screen).remove(prev.badge());
               }
               if (prev.stats() != null) {
                  Screens.getWidgets(screen).remove(prev.stats());
               }
            }
            Button spawnBtn = Button.builder(Component.literal("✨ Custom spawn"),
                  b -> client.setScreen(new SpawnWishScreen(createScreen)))
                  .bounds(scaledWidth - 108, 6, 102, 20).build();
            Screens.getWidgets(screen).add(spawnBtn);
            // Little green badge once a found seed is sitting in the world settings.
            Button badge = null;
            Button stats = null;
            String applied = SpawnWishScreen.lastAppliedSeed;
            if (applied != null && applied.equals(createScreen.getUiState().getSeed())) {
               badge = Button.builder(Component.literal("§a✓ custom spawn set"), b -> {})
                     .bounds(scaledWidth - 108, 28, 102, 20).build();
               badge.active = false;
               Screens.getWidgets(screen).add(badge);
               if (!SpawnWishScreen.lastAppliedShort.isEmpty()) {
                  stats = Button.builder(Component.literal("§7" + SpawnWishScreen.lastAppliedShort), b -> {})
                        .bounds(scaledWidth - 158, 50, 152, 20).build();
                  stats.active = false;
                  Screens.getWidgets(screen).add(stats);
               }
            }
            SPAWN_BUTTONS.put(screen, new SpawnButtons(spawnBtn, badge, stats));
            if (prev == null) {
               // Register the re-anchor ONCE per screen; it always reads the CURRENT set.
               ScreenEvents.afterTick(screen).register(s -> {
                  SpawnButtons cur = SPAWN_BUTTONS.get(s);
                  if (cur == null) {
                     return;
                  }
                  cur.spawn().setX(s.width - 108);
                  cur.spawn().setY(6);
                  if (cur.badge() != null) {
                     cur.badge().setX(s.width - 108);
                     cur.badge().setY(28);
                  }
                  if (cur.stats() != null) {
                     cur.stats().setX(s.width - 158);
                     cur.stats().setY(50);
                  }
               });
            }
            boolean suppressed = SpawnWishScreen.consumeSuppress();
            if (FableVisionConfig.askCustomSpawn && !suppressed && askedCreateScreen.get() != createScreen) {
               askedCreateScreen = new WeakReference<>(createScreen);
               client.execute(() -> {
                  if (client.screen == createScreen) {
                     client.setScreen(new SpawnWishScreen(createScreen));
                  }
               });
            }
         }
      });
      ClientTickEvents.END_CLIENT_TICK.register((EndTick)client -> {
         SeedFinderScreen.tickAutosave();
         // Safety net: the AI usage counter must reset at local midnight even if no AI
         // panel is ever opened that day (the check itself is throttled to every 30s).
         com.tensec.aiscreen.Config.midnightRolloverCheck();
         if (queuedScreen != null) {
            Screen screen = queuedScreen;
            queuedScreen = null;
            client.setScreen(screen);
         }

         if (client.level == null) {
            lastWorldKey = null;
            timerTicks = -1;
            lanDelayTicks = -1;
            welcomeTicks = 0;
         } else {
            String key = currentWorldKey(client);
            if (key != null && !key.equals(lastWorldKey)) {
               lastWorldKey = key;
               timerTicks = FableVisionConfig.tenSecTimer && worldMatches(key) ? 200 : -1;
               // Auto LAN + Bedrock: shortly after joining a singleplayer world, open it to LAN
               // (the ~2s delay lets the world finish loading so the chat messages are visible).
               lanDelayTicks = FableVisionConfig.autoLan
                     && client.isLocalServer()
                     && client.getSingleplayerServer() != null
                     && !client.getSingleplayerServer().isPublished()
                  ? 40
                  : -1;
               maybeWelcome(client);
               maybeAnnounceFoundSeed(client);
            }

            if (timerTicks > 0 && !client.isPaused()) {
               timerTicks--;
               if (timerTicks == 0) {
                  timerTicks = -1;
                  LOGGER.info("10 seconds up - leaving world '{}'", key);
                  client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
               }
            }

            if (lanDelayTicks > 0 && !client.isPaused()) {
               lanDelayTicks--;
               if (lanDelayTicks == 0) {
                  lanDelayTicks = -1;
                  LanBedrock.openToLan(client);
               }
            }

            if (welcomeTicks > 0 && !client.isPaused()) {
               welcomeTicks--;
            }
         }
      });
      LOGGER.info("FableVision loaded - type /client in-game to configure");
   }

   /** One-time welcome that points a new user at the full guide, then never shows again. */
   private static void maybeWelcome(Minecraft client) {
      if (client.player == null) {
         return;
      }
      // First-ever run: point the player at the full guide (once).
      if (!FableVisionConfig.helpShown) {
         FableVisionConfig.helpShown = true;
         FableVisionConfig.save();
         client.player.sendSystemMessage(Component.literal(
            "§b[FableVision] §fThanks for installing! §7Press §fG §7to ask AI about your screen, §f/build §7to auto-build schematics (singleplayer), §f/client §7for settings."));
         client.player.sendSystemMessage(Component.literal(
            "§7New here? Open §f/client §7and click §f❓ How it works §7for a full guide."));
      }
      // Play the WELCOME splash once. The 1.31 "finished product" animation uses a NEW flag
      // on purpose, so every install — updated or fresh — gets to see it exactly once.
      if (!FableVisionConfig.welcomeAnim2Shown) {
         FableVisionConfig.welcomeAnim2Shown = true;
         FableVisionConfig.save();
         welcomeTicks = WELCOME_TICKS;
      }
   }

   /** First join of a world made from a found seed: chat the search stats + what's there. */
   private static void maybeAnnounceFoundSeed(Minecraft client) {
      if (client.player == null || !client.isLocalServer() || client.getSingleplayerServer() == null) {
         return;
      }
      String worldSeed = Long.toString(client.getSingleplayerServer().overworld().getSeed());
      if (!SpawnWishScreen.consumeAnnounce(worldSeed)) {
         return;
      }
      client.player.sendSystemMessage(Component.literal(
         "§b[FableVision] §a✓ This world came from your custom-spawn search — §f" + SpawnWishScreen.lastAppliedStats + "§a."));
      for (String line : SpawnWishScreen.lastAppliedMatches) {
         client.player.sendSystemMessage(Component.literal("§7   • " + line));
      }
      if (!SpawnWishScreen.lastAppliedNote.isEmpty()) {
         String note = SpawnWishScreen.lastAppliedNote;
         boolean plainNote = note.equals(com.fablevision.client.seedfinder.WishParser.EYES_SOON)
               || note.equals(com.fablevision.client.seedfinder.WishParser.STRONGHOLD_FAR);
         client.player.sendSystemMessage(Component.literal(
               plainNote ? "§e   " + note : "§6   couldn't check: " + note));
      }
   }

   private static String currentWorldKey(Minecraft client) {
      if (client.isLocalServer()) {
         return client.getSingleplayerServer() != null ? client.getSingleplayerServer().getWorldData().getLevelName() : null;
      }

      ServerData server = client.getCurrentServer();
      return server != null ? server.ip : null;
   }

   private static boolean worldMatches(String key) {
      return FableVisionConfig.tenSecWorld.isEmpty() || FableVisionConfig.tenSecWorld.equalsIgnoreCase(key);
   }
}
