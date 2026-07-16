package com.fablevision.client.builder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Owns the builder feature: a LIST of staged schematics (each with its own world origin),
 * the shared material chest, the build mode (one-at-a-time vs all-together), the live
 * BuildTasks, the ghost/outline preview, and per-world persistence so staged builds and
 * in-progress work survive world exits. Fully AI-free — never touches the Gemini/AI system.
 *
 * Threading: building runs on the SERVER thread (integrated singleplayer server) via
 * ServerTickEvents; player messages go through ServerPlayer.sendSystemMessage. The preview
 * runs on the CLIENT tick, and the ghost models render on the RENDER thread — so the loaded
 * list is a CopyOnWriteArrayList (snapshot iteration, no locks, no ConcurrentModification).
 */
public final class BuilderManager {
   private BuilderManager() {
   }

   /** One staged schematic: its blocks, where its corner sits in the world, and (once it
    *  starts) its build task. Origin is captured where you stand when you /build load it. */
   public static final class Loaded {
      public final Schematic schem;
      public final ResourceKey<Level> dimension;
      public volatile BlockPos origin;
      public volatile BuildTask task; // null until this one starts building

      Loaded(Schematic schem, ResourceKey<Level> dimension, BlockPos origin) {
         this.schem = schem;
         this.dimension = dimension;
         this.origin = origin;
      }

      public boolean isDone() {
         return task != null && task.state == BuildTask.State.DONE;
      }

      public boolean isBuilding() { // includes paused/waiting — anything not finished/cancelled
         return task != null && task.isActive();
      }
   }

   public static final List<Loaded> loadedList = new CopyOnWriteArrayList<>();
   public static volatile boolean buildTogether = false; // false = one at a time, true = all at once
   public static volatile BlockPos chestPos;              // shared material chest (auto-picked if null)
   public static volatile boolean showGhost = true;       // toggled by /build ghost

   private static volatile boolean sessionActive = false; // true while a build session is running
   private static volatile boolean announceRestored = false;
   private static boolean serverNoticeShown = false;

   public static final int blocksPerTick = 2; // fixed, gentle build pace per active task

   public static void register() {
      ServerLifecycleEvents.SERVER_STARTED.register(BuilderManager::onServerStarted);
      ServerLifecycleEvents.SERVER_STOPPED.register(BuilderManager::onServerStopped);
      ServerTickEvents.END_SERVER_TICK.register(BuilderManager::onServerTick);
      ClientTickEvents.END_CLIENT_TICK.register(BuilderManager::renderPreview);
      GhostRenderer.register(); // per-block textured ghosts in the world render pass
   }

   /** True when we're NOT in our own singleplayer world — the feature is disabled there. */
   public static boolean onRemoteOrNoWorld() {
      Minecraft mc = Minecraft.getInstance();
      return mc.level == null || !mc.isLocalServer();
   }

   /** Shows the one-time server explanation, then stays quiet. Returns true if blocked. */
   public static boolean blockedOnServer() {
      if (!onRemoteOrNoWorld()) return false;
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null) {
         if (!serverNoticeShown) {
            serverNoticeShown = true;
            mc.player.sendSystemMessage(Component.literal(
                  "§6[FableVision] The auto-builder only works in your own single-player world."));
            mc.player.sendSystemMessage(Component.literal(
                  "§7It's disabled on servers because auto-building would be flagged as cheating. §bWe don't wanna get you banned o7"));
         } else {
            mc.player.sendSystemMessage(Component.literal("§8Auto-builder is disabled on servers."));
         }
      }
      return true;
   }

   // ── Loading / unloading (a list you can grow and prune) ──────────────────
   /** Loads a schematic file and stages it at the player's current position. Returns a status line. */
   public static String addLoadedFromFile(Path file) {
      Minecraft mc = Minecraft.getInstance();
      if (onRemoteOrNoWorld()) return "§cSingle-player only.";
      if (mc.player == null) return "§cJoin a world first.";
      try {
         Schematic s = Schematic.load(file);
         Loaded l = new Loaded(s, mc.player.level().dimension(), mc.player.blockPosition());
         loadedList.add(l);
         save();
         String msg = "§aLoaded §f" + s.name + "§a (" + s.placements.size() + " blocks) where you stand — §f"
               + loadedList.size() + "§a staged. §7Fill a chest nearby, then §f/build start§7.";
         return s.note != null ? msg + "\n§e⚠ " + s.note : msg;
      } catch (Exception e) {
         return "§cCouldn't read that schematic: " + e.getMessage();
      }
   }

   /** Re-anchors a staged schematic to where you're standing (replaces the old Set Origin step). */
   public static String moveHere(Loaded l) {
      Minecraft mc = Minecraft.getInstance();
      if (l == null || mc.player == null) return "§cNothing to move.";
      if (l.isBuilding()) return "§eThat one is mid-build — unload it (✖) and load it again to move it.";
      l.origin = mc.player.blockPosition();
      save();
      return "§aMoved §f" + l.schem.name + "§a to your position.";
   }

   public static String unload(Loaded l) {
      if (l == null || !loadedList.contains(l)) return "§cNothing to unload.";
      if (l.task != null) l.task.state = BuildTask.State.CANCELLED;
      loadedList.remove(l);
      if (loadedList.isEmpty()) { sessionActive = false; deleteSave(); } else save();
      return "§7Unloaded §f" + l.schem.name + "§7. §f" + loadedList.size() + "§7 left.";
   }

   public static String unloadAll() {
      if (loadedList.isEmpty()) return "§cNothing is loaded.";
      int n = loadedList.size();
      for (Loaded l : loadedList) if (l.task != null) l.task.state = BuildTask.State.CANCELLED;
      loadedList.clear();
      sessionActive = false;
      deleteSave();
      return "§7Unloaded all §f" + n + "§7 schematic(s).";
   }

   // ── Chest selection ─────────────────────────────────────────────────────
   public static String setChestLookingAt() {
      Minecraft mc = Minecraft.getInstance();
      HitResult hit = mc.hitResult;
      if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
         return "§cLook at a chest first (aim at it before opening this screen), or use §f/build chest§c.";
      }
      chestPos = ((BlockHitResult) hit).getBlockPos();
      // ONE chest feeds every build — so point every in-flight task at the new one too,
      // otherwise a restored/stuck task would keep paying from the OLD chest position.
      for (Loaded l : loadedList) {
         BuildTask t = l.task;
         if (t != null && t.isActive()) t.retargetChest(chestPos);
      }
      save();
      return "§aMaterial chest set §7(" + chestPos.getX() + " " + chestPos.getY() + " " + chestPos.getZ()
            + ") §a— every build pulls from this one chest.";
   }

   /** Finds the closest container (chest/barrel/etc.) within 8 blocks of a point, or null. */
   public static BlockPos autoChestNear(BlockPos center) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.level == null || center == null) return null;
      int r = 8;
      BlockPos best = null;
      double bestD = Double.MAX_VALUE;
      BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
      for (int dx = -r; dx <= r; dx++) {
         for (int dy = -r; dy <= r; dy++) {
            for (int dz = -r; dz <= r; dz++) {
               m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
               if (mc.level.getBlockEntity(m) instanceof Container) {
                  double d = center.distSqr(m);
                  if (d < bestD) { bestD = d; best = m.immutable(); }
               }
            }
         }
      }
      return best;
   }

   // ── Build control ───────────────────────────────────────────────────────
   /** Starts a build session. In "together" mode every staged schematic starts at once; in
    *  "one at a time" mode only the first starts and the rest follow as each finishes. */
   public static synchronized String startBuild() {
      Minecraft mc = Minecraft.getInstance();
      if (onRemoteOrNoWorld()) return "§cSingle-player only.";
      if (loadedList.isEmpty()) return "§cLoad a schematic first — §f/build load <name>§c.";
      if (anyBuilding()) {
         // If everything active is merely paused (e.g. just restored), Start means "go" — resume.
         boolean anyRunning = false;
         for (Loaded l : loadedList) {
            BuildTask t = l.task;
            if (t != null && t.isActive() && t.state != BuildTask.State.PAUSED) { anyRunning = true; break; }
         }
         if (!anyRunning) return resumeBuild();
         // Already building: pressing Start again just pulls any newly staged ones in.
         if (buildTogether) {
            int added = 0;
            for (Loaded l : loadedList) if (l.task == null && !l.isDone()) { l.task = newTask(l); added++; }
            if (added > 0) { save(); return "§aAdded §f" + added + "§a more build(s) to the running session."; }
            return "§7Already building — everything staged is in progress.";
         }
         return "§7Already building this one — when it finishes, everything stops and §f/build resume§7 starts the next.";
      }
      if (mc.player == null) return "§cNo player.";

      boolean autoChest = false;
      if (chestPos == null) {
         chestPos = autoChestNear(mc.player.blockPosition());
         autoChest = chestPos != null;
      }
      if (chestPos == null) {
         return "§cNo material chest within 8 blocks. Stand next to your chest and Start again, or aim at it and use §f/build chest§c.";
      }

      int started = 0;
      if (buildTogether) {
         for (Loaded l : loadedList) if (!l.isDone()) { l.task = newTask(l); started++; }
      } else {
         Loaded first = firstQueued();
         if (first != null) { first.task = newTask(first); started++; }
      }
      if (started == 0) return "§eNothing to build — every staged schematic is already complete.";
      sessionActive = true;
      save();
      return "§aBuilding §f" + loadedList.size() + "§a schematic(s) §7[" + (buildTogether ? "all together" : "one at a time")
            + "]" + (autoChest ? " §7(chest auto-picked nearby)" : "") + ".";
   }

   private static BuildTask newTask(Loaded l) {
      return new BuildTask(l.schem, l.dimension, l.origin, chestPos, 0, 0, 0, 0);
   }

   private static Loaded firstQueued() {
      for (Loaded l : loadedList) if (l.task == null && !l.isDone()) return l;
      return null;
   }

   private static boolean anyBuilding() {
      for (Loaded l : loadedList) if (l.isBuilding()) return true;
      return false;
   }

   public static String pauseBuild() {
      int n = 0;
      for (Loaded l : loadedList) {
         BuildTask t = l.task;
         if (t != null && t.isActive() && t.state != BuildTask.State.PAUSED) { t.state = BuildTask.State.PAUSED; n++; }
      }
      if (n == 0) return "§cNo build is running.";
      save();
      return "§ePaused §f" + n + "§e build(s). §f/build resume§e to continue.";
   }

   public static String resumeBuild() {
      int n = 0;
      for (Loaded l : loadedList) {
         BuildTask t = l.task;
         if (t != null && t.state == BuildTask.State.PAUSED) { t.state = BuildTask.State.RUNNING; n++; }
      }
      if (n == 0) {
         // Nothing paused — but in one-at-a-time mode "resume" also means "start the next
         // queued build" (the session stops between builds on purpose).
         if (!anyBuilding() && firstQueued() != null) return startBuild();
         return "§cNo paused build to resume.";
      }
      sessionActive = true;
      save();
      return "§aResumed §f" + n + "§a build(s).";
   }

   /** One button for both: pauses running builds, resumes paused ones. */
   public static String togglePauseResume() {
      boolean anyPaused = false, anyRunning = false;
      for (Loaded l : loadedList) {
         BuildTask t = l.task;
         if (t != null && t.isActive()) {
            if (t.state == BuildTask.State.PAUSED) anyPaused = true; else anyRunning = true;
         }
      }
      if (!anyPaused && !anyRunning) return "§cNo build to pause or resume.";
      return anyPaused ? resumeBuild() : pauseBuild();
   }

   /** Resolves "/build unload <arg>" — "all", a 1-based number (as /build status lists them),
    *  or a schematic name (case-insensitive, extension optional). */
   public static String unloadByArg(String arg) {
      String a = arg == null ? "" : arg.trim();
      if (a.isEmpty()) {
         if (loadedList.isEmpty()) return "§cNothing is loaded.";
         if (loadedList.size() == 1) return unload(loadedList.get(0));
         return "§c" + loadedList.size() + " schematics are loaded — say which: §f/build unload §7(press §fTab§7) or §f/build unload all§c.";
      }
      if (a.equalsIgnoreCase("all")) return unloadAll();
      try {
         int idx = Integer.parseInt(a);
         if (idx >= 1 && idx <= loadedList.size()) return unload(loadedList.get(idx - 1));
      } catch (NumberFormatException ignored) {
      }
      String want = a.toLowerCase(java.util.Locale.ROOT);
      Loaded match = null;
      for (Loaded l : loadedList) {
         String n = l.schem.name.toLowerCase(java.util.Locale.ROOT);
         String base = n.lastIndexOf('.') > 0 ? n.substring(0, n.lastIndexOf('.')) : n;
         if (n.equals(want) || base.equals(want)) { match = l; break; }
         if (match == null && n.contains(want)) match = l; // fallback: first partial match
      }
      if (match == null) return "§cNothing loaded called '" + a + "'. §7Press §fTab§7 after /build unload to list them.";
      return unload(match);
   }

   public static String toggleGhost() {
      showGhost = !showGhost;
      return "§aGhost preview: §f" + (showGhost ? "ON" : "OFF");
   }

   public static String toggleBuildMode() {
      if (anyBuilding()) return "§cCan't switch build mode while building — pause or unload first.";
      buildTogether = !buildTogether;
      save();
      return "§aBuild mode: §f" + (buildTogether ? "all together" : "one at a time");
   }

   private static int totalPlaced() {
      int p = 0;
      for (Loaded l : loadedList) if (l.task != null) p += l.task.placed;
      return p;
   }

   /** One-line status of the whole staged set for the GUI + /build status. */
   public static String statusLine() {
      if (loadedList.isEmpty()) return "§7Idle — nothing loaded. §f/build load <name>§7 to stage one.";
      int building = 0, done = 0, waiting = 0;
      for (Loaded l : loadedList) {
         BuildTask t = l.task;
         if (t == null) continue;
         if (t.state == BuildTask.State.DONE) done++;
         else if (t.isActive()) { building++; if (t.state == BuildTask.State.WAITING_MATERIALS) waiting++; }
      }
      StringBuilder sb = new StringBuilder("§f" + loadedList.size() + "§7 loaded · builds §f"
            + (buildTogether ? "together" : "one at a time") + "§7 · one chest feeds all §8("
            + (chestPos == null ? "auto-picks nearby" : "set") + ")");
      if (building > 0) sb.append(" §7· §abuilding ").append(building);
      if (waiting > 0) sb.append(" §7· §ewaiting mats ").append(waiting);
      if (done > 0) sb.append(" §7· §adone ").append(done);
      return sb.toString();
   }

   // ── Server lifecycle: restore staged builds when the world loads ─────────
   private static void onServerStarted(MinecraftServer server) {
      try {
         load(server);
      } catch (Throwable ignored) {
      }
   }

   private static void onServerStopped(MinecraftServer server) {
      try {
         saveTo(server); // persist the staged set for next time
      } catch (Throwable ignored) {
      }
      loadedList.clear();
      sessionActive = false;
      announceRestored = false;
   }

   private static void onServerTick(MinecraftServer server) {
      List<ServerPlayer> players = server.getPlayerList().getPlayers();
      ServerPlayer player = players.isEmpty() ? null : players.get(0);

      if (announceRestored && player != null && !loadedList.isEmpty()) {
         announceRestored = false;
         int paused = 0;
         for (Loaded l : loadedList) if (l.task != null && l.task.isActive()) paused++;
         player.sendSystemMessage(Component.literal("§b[FableVision] Restored §f" + loadedList.size()
               + "§b staged schematic(s)" + (paused > 0 ? " (§f" + paused + "§b paused mid-build)" : "")
               + ". §f/build§b to manage" + (paused > 0 ? ", §f/build resume§b (or §f/build start§b) to continue." : ".")));
         if (paused > 0) {
            player.sendSystemMessage(Component.literal(
                  "§7Tip: if it doesn't move, re-aim at your material chest and run §f/build chest §7— that re-links the chest to the build."));
         }
      }

      if (loadedList.isEmpty() || !sessionActive) return;

      boolean progressed = false;
      if (buildTogether) {
         for (Loaded l : loadedList) progressed |= tickEntry(server, l, player);
         if (!anyBuilding()) onSessionComplete(player);
      } else {
         Loaded current = null;
         for (Loaded l : loadedList) if (l.isBuilding()) { current = l; break; }
         if (current != null) {
            progressed = tickEntry(server, current, player);
            if (!current.isBuilding()) {
               // One-at-a-time STOPS between builds: announce, then wait for Resume/Start.
               Loaded next = firstQueued();
               if (next == null) {
                  onSessionComplete(player);
               } else {
                  sessionActive = false;
                  save();
                  if (player != null) {
                     player.sendSystemMessage(Component.literal("§a[FableVision] §f" + current.schem.name
                           + "§a is done — stopping here (one-at-a-time). §7Next up: §f" + next.schem.name
                           + "§7 — §f/build resume §7(or Start) when you're ready."));
                  }
               }
            }
         } else {
            // Session flagged active but nothing is running (e.g. right after a restore):
            // don't auto-start anything — one-at-a-time waits for the player.
            if (firstQueued() == null) onSessionComplete(player);
            else sessionActive = false;
         }
      }

      if (progressed && (totalPlaced() & 63) == 0) save(); // checkpoint every ~64 placed blocks
   }

   /** Ticks one entry's build; returns true if a block was placed (worth checkpointing). */
   private static boolean tickEntry(MinecraftServer server, Loaded l, ServerPlayer player) {
      BuildTask t = l.task;
      if (t == null || !t.isActive()) return false;
      ServerLevel level = server.getLevel(t.dimension);
      if (level == null || player == null) return false;
      int before = t.placed;
      try {
         String msg = t.tick(level, blocksPerTick);
         if (msg != null) player.sendSystemMessage(Component.literal("§8[" + l.schem.name + "] §r" + msg));
      } catch (Throwable ex) {
         t.state = BuildTask.State.PAUSED;
         save();
         player.sendSystemMessage(Component.literal("§cBuild of §f" + l.schem.name
               + "§c hit an error and was paused (nothing broken). §7/build resume or /build unload."));
         return false;
      }
      return t.placed > before;
   }

   private static void onSessionComplete(ServerPlayer player) {
      sessionActive = false;
      if (player != null) player.sendSystemMessage(Component.literal("§a[FableVision] All builds complete."));
      save();
   }

   // ── Preview: bounding-box outline (particles) per staged schematic ───────
   // Per-block textured "ghost blocks" are drawn by GhostRenderer in the world render pass;
   // showGhost toggles those. This is just the cheap bounding-box particle outline.
   private static int previewTick = 0;

   private static void renderPreview(Minecraft mc) {
      if (mc.level == null || mc.player == null || onRemoteOrNoWorld()) return;
      if (loadedList.isEmpty()) return;
      if (previewTick++ % 4 != 0) return; // ~5x/sec
      for (Loaded l : loadedList) {
         if (!l.dimension.equals(mc.level.dimension())) continue;
         if (mc.player.blockPosition().distSqr(l.origin) > 128 * 128) continue; // skip far ones
         drawOutline(mc, l);
      }
   }

   private static void drawOutline(Minecraft mc, Loaded l) {
      BlockPos o = l.origin;
      int sx = l.schem.sizeX, sy = l.schem.sizeY, sz = l.schem.sizeZ;
      boolean active = l.isBuilding() && l.task.state != BuildTask.State.PAUSED;
      outlineEdge(mc, o, 0, 0, 0, 1, 0, 0, sx, active);
      outlineEdge(mc, o, 0, 0, 0, 0, 1, 0, sy, active);
      outlineEdge(mc, o, 0, 0, 0, 0, 0, 1, sz, active);
      outlineEdge(mc, o, sx, 0, 0, 0, 1, 0, sy, active);
      outlineEdge(mc, o, sx, 0, 0, 0, 0, 1, sz, active);
      outlineEdge(mc, o, 0, sy, 0, 1, 0, 0, sx, active);
      outlineEdge(mc, o, 0, sy, 0, 0, 0, 1, sz, active);
      outlineEdge(mc, o, 0, 0, sz, 1, 0, 0, sx, active);
      outlineEdge(mc, o, 0, 0, sz, 0, 1, 0, sy, active);
      outlineEdge(mc, o, sx, sy, 0, 0, 0, 1, sz, active);
      outlineEdge(mc, o, sx, 0, sz, 0, 1, 0, sy, active);
      outlineEdge(mc, o, 0, sy, sz, 1, 0, 0, sx, active);
   }

   private static void outlineEdge(Minecraft mc, BlockPos origin, int ox, int oy, int oz,
                                   int dx, int dy, int dz, int len, boolean active) {
      for (int i = 0; i <= len; i += 2) {
         double x = origin.getX() + ox + dx * i + 0.5;
         double y = origin.getY() + oy + dy * i + 0.5;
         double z = origin.getZ() + oz + dz * i + 0.5;
         mc.level.addParticle(active ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.END_ROD, x, y, z, 0, 0, 0);
      }
   }

   // ── Persistence: one file per world under the save folder ────────────────
   private static Path saveFile(MinecraftServer server) {
      return server.getWorldPath(LevelResource.ROOT).resolve("fablevision-build.dat");
   }

   private static void save() {
      MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
      if (server != null) saveTo(server);
   }

   // save/load/delete are synchronized: the GUI saves from the client thread while the
   // build checkpoints from the server thread — never write the same file concurrently.
   private static synchronized void saveTo(MinecraftServer server) {
      if (loadedList.isEmpty()) { deleteSave(); return; }
      try {
         CompoundTag root = new CompoundTag();
         root.putInt("together", buildTogether ? 1 : 0);
         if (chestPos != null) root.putLong("chest", chestPos.asLong());
         ListTag entries = new ListTag();
         for (Loaded l : loadedList) {
            CompoundTag e = new CompoundTag();
            e.putString("schematic", l.schem.name);
            e.putString("dimension", l.dimension.identifier().toString());
            e.putLong("origin", l.origin.asLong());
            BuildTask t = l.task;
            if (t != null) {
               e.putInt("building", 1);
               e.putInt("index", t.index);
               e.putInt("placed", t.placed);
               e.putInt("skippedOccupied", t.skippedOccupied);
               e.putInt("skippedUnobtainable", t.skippedUnobtainable);
               e.putInt("done", t.state == BuildTask.State.DONE ? 1 : 0);
            }
            entries.add(e);
         }
         root.put("entries", entries);
         NbtIo.writeCompressed(root, saveFile(server));
      } catch (Throwable ignored) {
      }
   }

   private static synchronized void load(MinecraftServer server) throws Exception {
      loadedList.clear();
      sessionActive = false;
      Path f = saveFile(server);
      if (!Files.exists(f)) return;
      CompoundTag root = NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap());
      buildTogether = root.getIntOr("together", 0) == 1;
      chestPos = root.contains("chest") ? BlockPos.of(root.getLongOr("chest", 0L)) : null;
      ListTag entries = root.getListOrEmpty("entries");
      for (int i = 0; i < entries.size(); i++) {
         CompoundTag e = entries.getCompoundOrEmpty(i);
         String name = e.getString("schematic").orElse("");
         Path schemFile = schematicDir().resolve(name);
         if (name.isEmpty() || !Files.exists(schemFile)) continue; // schematic gone — drop it
         Schematic schem;
         try {
            schem = Schematic.load(schemFile);
         } catch (Exception ex) {
            continue;
         }
         ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION,
               Identifier.parse(e.getString("dimension").orElse("minecraft:overworld")));
         BlockPos origin = BlockPos.of(e.getLongOr("origin", 0L));
         Loaded l = new Loaded(schem, dim, origin);
         if (e.getIntOr("building", 0) == 1) {
            // Restore even with no chest saved — progress is kept and the task simply
            // waits (WAITING_CHEST) until /build chest points it at one.
            BuildTask t = new BuildTask(schem, dim, origin, chestPos,
                  e.getInt("index").orElse(0), e.getInt("placed").orElse(0),
                  e.getInt("skippedOccupied").orElse(0), e.getInt("skippedUnobtainable").orElse(0));
            t.state = e.getIntOr("done", 0) == 1 ? BuildTask.State.DONE : BuildTask.State.PAUSED; // never auto-run
            l.task = t;
         }
         loadedList.add(l);
      }
      if (!loadedList.isEmpty()) announceRestored = true;
   }

   private static synchronized void deleteSave() {
      MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
      if (server == null) return;
      try {
         Files.deleteIfExists(saveFile(server));
      } catch (Throwable ignored) {
      }
   }

   /**
    * Global schematics folder: config/fablevision/schematics/. Drop downloaded .schem /
    * .litematic / .schematic files here. Shared across all worlds; created on demand.
    */
   public static Path schematicDir() {
      Path dir = FabricLoader.getInstance().getConfigDir().resolve("fablevision").resolve("schematics");
      try {
         Files.createDirectories(dir);
      } catch (Exception ignored) {
      }
      return dir;
   }
}
