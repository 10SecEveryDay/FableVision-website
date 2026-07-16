package com.fablevision.client.builder;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * All /build subcommands. Client-side; guarded so nothing runs on a multiplayer server.
 * Bare /build opens the GUI. /build load <name> stages a schematic where you stand (Tab
 * lists the files you have) — you can load as many as you like, then /build start.
 */
public final class BuilderCommands {
   private BuilderCommands() {
   }

   public static void register() {
      ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
         dispatcher.register(ClientCommands.literal("build")
            .executes(c -> gui(c.getSource())) // bare /build opens the panel — simplest entry point
            .then(ClientCommands.literal("load")
               .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                  .suggests(BuilderCommands::suggestSchematics)
                  .executes(c -> load(c.getSource(), StringArgumentType.getString(c, "name")))))
            .then(ClientCommands.literal("unload")
               .executes(c -> send(c.getSource(), BuilderManager.unloadByArg("")))
               .then(ClientCommands.argument("which", StringArgumentType.greedyString())
                  .suggests(BuilderCommands::suggestLoaded)
                  .executes(c -> send(c.getSource(), BuilderManager.unloadByArg(StringArgumentType.getString(c, "which"))))))
            .then(ClientCommands.literal("chest").executes(c -> chest(c.getSource())))
            .then(ClientCommands.literal("start").executes(c -> start(c.getSource())))
            .then(ClientCommands.literal("pause").executes(c -> send(c.getSource(), BuilderManager.pauseBuild())))
            .then(ClientCommands.literal("resume").executes(c -> send(c.getSource(), BuilderManager.resumeBuild())))
            .then(ClientCommands.literal("status").executes(c -> status(c.getSource())))
            .then(ClientCommands.literal("ghost").executes(c -> ghost(c.getSource())))));
   }

   private static boolean guard(FabricClientCommandSource src) {
      return BuilderManager.blockedOnServer(); // shows the one-time notice itself
   }

   /** Sends a manager status line as feedback, or as an error if it's a red (§c) message. */
   private static int send(FabricClientCommandSource src, String msg) {
      if (guard(src)) return 0;
      if (msg.startsWith("§c")) src.sendError(Component.literal(msg));
      else src.sendFeedback(Component.literal(msg));
      return 1;
   }

   /** Supported schematic file extensions, in preference order for name resolution. */
   private static final String[] EXTS = {".schem", ".litematic", ".schematic", ".nbt"};

   private static boolean supported(Path p) {
      String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
      for (String e : EXTS) if (n.endsWith(e)) return true;
      return false;
   }

   /** Tab-completion for /build load — offers the schematic files you actually have. */
   private static CompletableFuture<Suggestions> suggestSchematics(CommandContext<FabricClientCommandSource> ctx,
                                                                   SuggestionsBuilder builder) {
      String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
      Path dir = BuilderManager.schematicDir();
      try (Stream<Path> s = Files.list(dir)) {
         s.filter(Files::isRegularFile).filter(BuilderCommands::supported)
               .map(p -> p.getFileName().toString())
               .sorted()
               .forEach(name -> {
                  if (name.toLowerCase(Locale.ROOT).contains(typed)) builder.suggest(name);
               });
      } catch (Exception ignored) {
      }
      return builder.buildFuture();
   }

   private static int load(FabricClientCommandSource src, String name) {
      if (guard(src)) return 0;
      Path file = resolve(BuilderManager.schematicDir(), name.trim());
      if (file == null) {
         src.sendError(Component.literal("No schematic '" + name.trim() + "'. Type §f/build load §cand press §fTab§c to list yours."));
         return 0;
      }
      // addLoadedFromFile stages it at the player's position (no separate /build here needed).
      for (String line : BuilderManager.addLoadedFromFile(file).split("\n")) {
         if (line.startsWith("§c")) src.sendError(Component.literal(line));
         else src.sendFeedback(Component.literal(line));
      }
      return 1;
   }

   /** Resolves a user-typed name to a file: exact match, then name+extension, then a
    *  case-insensitive match on the full name or the base name (extension stripped). */
   private static Path resolve(Path dir, String arg) {
      Path exact = dir.resolve(arg);
      if (Files.isRegularFile(exact)) return exact;
      for (String e : EXTS) {
         Path cand = dir.resolve(arg + e);
         if (Files.isRegularFile(cand)) return cand;
      }
      String want = arg.toLowerCase(Locale.ROOT);
      try (Stream<Path> s = Files.list(dir)) {
         return s.filter(Files::isRegularFile).filter(BuilderCommands::supported)
               .filter(p -> {
                  String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                  return n.equals(want) || stripExt(n).equals(want);
               })
               .findFirst().orElse(null);
      } catch (Exception e) {
         return null;
      }
   }

   private static String stripExt(String n) {
      for (String e : EXTS) if (n.endsWith(e)) return n.substring(0, n.length() - e.length());
      return n;
   }

   /** Tab-completion for /build unload — every loaded schematic by name, plus "all". If the
    *  same file is staged more than once, the entry NUMBERS (from /build status) are offered
    *  too so each copy stays individually unloadable. */
   private static CompletableFuture<Suggestions> suggestLoaded(CommandContext<FabricClientCommandSource> ctx,
                                                              SuggestionsBuilder builder) {
      java.util.List<BuilderManager.Loaded> list = BuilderManager.loadedList;
      if (list.isEmpty()) return builder.buildFuture(); // nothing loaded → nothing to offer
      String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
      if ("all".contains(typed)) builder.suggest("all");
      java.util.Set<String> seen = new java.util.HashSet<>();
      boolean dupes = false;
      for (BuilderManager.Loaded l : list) {
         if (!seen.add(l.schem.name)) dupes = true;
      }
      seen.clear();
      for (int i = 0; i < list.size(); i++) {
         String name = list.get(i).schem.name;
         if (name.toLowerCase(Locale.ROOT).contains(typed) && seen.add(name)) builder.suggest(name);
         if (dupes) { // duplicates exist → numbers pick an exact copy
            String num = String.valueOf(i + 1);
            if (typed.isEmpty() || num.startsWith(typed)) builder.suggest(num);
         }
      }
      return builder.buildFuture();
   }

   private static int chest(FabricClientCommandSource src) {
      return send(src, BuilderManager.setChestLookingAt());
   }

   private static int start(FabricClientCommandSource src) {
      return send(src, BuilderManager.startBuild());
   }

   private static int ghost(FabricClientCommandSource src) {
      if (guard(src)) return 0;
      src.sendFeedback(Component.literal(BuilderManager.toggleGhost()
            + " §7— the real block, shown ghosted with a thin outline, where it needs to go."));
      return 1;
   }

   private static int gui(FabricClientCommandSource src) {
      if (guard(src)) return 0;
      Minecraft mc = src.getClient();
      // Defer to the client thread so it opens after the chat screen closes.
      mc.execute(() -> mc.setScreen(new BuilderScreen()));
      return 1;
   }

   private static int status(FabricClientCommandSource src) {
      if (guard(src)) return 0;
      src.sendFeedback(Component.literal(BuilderManager.statusLine()));
      if (BuilderManager.loadedList.isEmpty()) {
         src.sendFeedback(Component.literal("§8Steps: §f/build load <name>§8 (stages where you stand) → §f/build start"));
         return 1;
      }
      int i = 1;
      for (BuilderManager.Loaded l : BuilderManager.loadedList) {
         String st = l.task == null ? "§8staged"
               : switch (l.task.state) {
                  case RUNNING -> "§abuilding " + l.task.percent() + "%";
                  case WAITING_MATERIALS -> "§eout of " + l.task.waitingFor + " §7(needs " + l.task.waitingNeed + " more)";
                  case WAITING_CHUNKS -> "§ewaiting for chunks (move closer)";
                  case WAITING_CHEST -> "§cno chest — aim at one, /build chest";
                  case PAUSED -> "§epaused " + l.task.percent() + "%";
                  case DONE -> "§adone";
                  case CANCELLED -> "§7cancelled";
               };
         BlockPos o = l.origin;
         src.sendFeedback(Component.literal("§7" + (i++) + ". §f" + l.schem.name + " §7@ "
               + o.getX() + " " + o.getY() + " " + o.getZ() + " — " + st));
      }
      return 1;
   }
}
