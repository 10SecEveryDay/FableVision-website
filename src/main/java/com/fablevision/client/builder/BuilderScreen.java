package com.fablevision.client.builder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * FableVision's own builder GUI (no Litematica/WorldEdit UI). Controls live down the LEFT;
 * the RIGHT panel switches between three views: the schematics folder (click to stage one at
 * your position), the Loaded list (unload / move any staged build), and the combined Materials
 * shopping list. Open with /build. All logic lives in BuilderManager, shared with /build commands.
 */
public class BuilderScreen extends Screen {
   private static final String[] EXTS = {".schem", ".litematic", ".schematic", ".nbt"};
   private static final int PER_PAGE = 7;
   private static final int PER_ROWS = 8; // visible rows in the Loaded panel

   private enum Panel { FILES, LOADED, MATERIALS }

   // Layout: left controls column + right panel. Kept off the screen edges (margins), not cornered.
   private static final int LEFT_X = 40;
   private static final int CTRL_W = 170;
   private static final int PANEL_W = 190;

   private final List<Path> files = new ArrayList<>();
   private int page = 0;
   private Panel panel = Panel.FILES;
   private int matScroll = 0;
   private int matMaxScroll = 0;
   private int loadedScroll = 0; // first visible row of the Loaded panel
   private String feedback = "Click a schematic on the right to stage it where you stand. Load as many as you like.";

   public BuilderScreen() {
      super(Component.literal("FableVision — Schematic Builder"));
   }

   private int panelX() {
      return Math.max(CTRL_W + LEFT_X + 10, this.width - 40 - PANEL_W);
   }

   @Override
   protected void init() {
      loadFileList();

      // ── Left: controls ────────────────────────────────────────────────
      int x = LEFT_X;
      int y = 50;
      int step = 20;
      ctrl(x, y, "▶ Start build", BuilderManager::startBuild);
      ctrlToggle(x, y += step, "⏸ Pause / ▶ Resume", BuilderManager::togglePauseResume);
      ctrlToggle(x, y += step, "Build: " + (BuilderManager.buildTogether ? "Together" : "One at a time"),
            BuilderManager::toggleBuildMode);
      ctrlToggle(x, y += step, "Ghost blocks: " + (BuilderManager.showGhost ? "ON" : "OFF"),
            BuilderManager::toggleGhost);
      ctrl(x, y += step, "Set chest — feeds all builds", BuilderManager::setChestLookingAt);
      panelButton(x, y += step, "🗂 Loaded (" + BuilderManager.loadedList.size() + ")", Panel.LOADED);
      panelButton(x, y += step, "📋 Materials list", Panel.MATERIALS);

      // ── Right: the active panel ───────────────────────────────────────
      int px = panelX();
      if (panel == Panel.FILES) {
         initFilesPanel(px);
      } else {
         addRenderableWidget(Button.builder(Component.literal("◀ Back to schematics"), b -> switchPanel(Panel.FILES))
               .bounds(px, 50, PANEL_W, 20).build());
         if (panel == Panel.LOADED) initLoadedPanel(px);
      }

      addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(this.width / 2 - 40, this.height - 26, 80, 20).build());
   }

   private void initFilesPanel(int px) {
      int listY = 74;
      int startIdx = page * PER_PAGE;
      for (int i = 0; i < PER_PAGE && startIdx + i < files.size(); i++) {
         Path f = files.get(startIdx + i);
         addRenderableWidget(Button.builder(Component.literal(trim(f.getFileName().toString(), 24)),
               b -> { feedback = BuilderManager.addLoadedFromFile(f); rebuildWidgets(); })
               .bounds(px, listY + i * 22, PANEL_W, 20).build());
      }
      int navY = listY + PER_PAGE * 22 + 2;
      if (page > 0) {
         addRenderableWidget(Button.builder(Component.literal("◀ Prev"), b -> { page--; rebuildWidgets(); })
               .bounds(px, navY, 60, 20).build());
      }
      if (startIdx + PER_PAGE < files.size()) {
         addRenderableWidget(Button.builder(Component.literal("Next ▶"), b -> { page++; rebuildWidgets(); })
               .bounds(px + 65, navY, 60, 20).build());
      }
      addRenderableWidget(Button.builder(Component.literal("⟳ Refresh"), b -> { page = 0; rebuildWidgets(); })
            .bounds(px + PANEL_W - 60, navY, 60, 20).build());
   }

   private void initLoadedPanel(int px) {
      List<BuilderManager.Loaded> list = BuilderManager.loadedList;
      loadedScroll = Math.max(0, Math.min(loadedScroll, list.size() - PER_ROWS)); // clamp after unloads
      int rowY = 78;
      int shown = Math.min(list.size() - loadedScroll, PER_ROWS);
      for (int i = 0; i < shown; i++) {
         BuilderManager.Loaded l = list.get(loadedScroll + i);
         int ry = rowY + i * 22;
         // ⇱ move-to-me, then ✖ unload — both anchored to the panel's right edge.
         addRenderableWidget(Button.builder(Component.literal("⇱"), b -> { feedback = BuilderManager.moveHere(l); rebuildWidgets(); })
               .bounds(px + PANEL_W - 44, ry, 20, 20).build());
         addRenderableWidget(Button.builder(Component.literal("✖"), b -> { feedback = BuilderManager.unload(l); rebuildWidgets(); })
               .bounds(px + PANEL_W - 22, ry, 20, 20).build());
      }
      if (!list.isEmpty()) {
         addRenderableWidget(Button.builder(Component.literal("Unload all"), b -> { feedback = BuilderManager.unloadAll(); rebuildWidgets(); })
               .bounds(px, rowY + Math.max(shown, 0) * 22 + 4, PANEL_W, 20).build());
      }
   }

   // ── Button helpers ───────────────────────────────────────────────────
   private void ctrl(int x, int y, String label, Supplier<String> op) {
      addRenderableWidget(Button.builder(Component.literal(label), b -> feedback = op.get())
            .bounds(x, y, CTRL_W, 20).build());
   }

   private void ctrlToggle(int x, int y, String label, Supplier<String> op) {
      addRenderableWidget(Button.builder(Component.literal(label), b -> { feedback = op.get(); rebuildWidgets(); })
            .bounds(x, y, CTRL_W, 20).build());
   }

   private void panelButton(int x, int y, String label, Panel target) {
      addRenderableWidget(Button.builder(Component.literal(label), b -> switchPanel(target))
            .bounds(x, y, CTRL_W, 20).build());
   }

   private void switchPanel(Panel target) {
      panel = target;
      page = 0;
      matScroll = 0;
      rebuildWidgets();
   }

   // ── Combined materials (across every staged schematic) ────────────────
   private List<Schematic.ItemNeed> combinedMaterials() {
      Map<String, Integer> counts = new HashMap<>();
      Map<String, Item> icons = new HashMap<>();
      for (BuilderManager.Loaded l : BuilderManager.loadedList) {
         for (Schematic.ItemNeed n : l.schem.materials()) {
            counts.merge(n.label(), n.count(), Integer::sum);
            icons.putIfAbsent(n.label(), n.item());
         }
      }
      List<Schematic.ItemNeed> list = new ArrayList<>(counts.size());
      for (Map.Entry<String, Integer> e : counts.entrySet()) {
         list.add(new Schematic.ItemNeed(icons.get(e.getKey()), e.getKey(), e.getValue()));
      }
      list.sort(Comparator.comparingInt(Schematic.ItemNeed::count).reversed().thenComparing(Schematic.ItemNeed::label));
      return list;
   }

   /** How many of {@code item} the player currently carries (used to green-check the list). */
   private static int inventoryCount(Item item) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player == null || item == Items.AIR) return 0;
      int c = 0;
      var inv = mc.player.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         if (!s.isEmpty() && s.getItem() == item) c += s.getCount();
      }
      return c;
   }

   private void drawMaterials(GuiGraphicsExtractor g, int px) {
      List<Schematic.ItemNeed> mats = combinedMaterials();
      int totalBlocks = 0;
      boolean haveAll = true;
      for (Schematic.ItemNeed e : mats) {
         totalBlocks += e.count();
         if (e.item() != Items.AIR && inventoryCount(e.item()) < e.count()) haveAll = false;
      }
      g.text(this.font, Component.literal("§7Materials §8(" + mats.size() + " types · " + totalBlocks
            + " blocks · all loaded)"), px, 76, 0xFFAAAAAA);
      if (mats.isEmpty()) {
         g.text(this.font, Component.literal("§8Nothing loaded — stage a schematic first."), px, 90, 0xFF888888);
         matMaxScroll = 0;
         return;
      }
      // Overall header goes green the moment your inventory covers everything.
      g.text(this.font, Component.literal(haveAll
            ? "§a✓ You have every item you need." : "§7Green = you already have enough."), px, 88, 0xFFFFFFFF);

      int top = 104;
      int rowH = 18; // room for the 16px item icon
      int rows = Math.max(1, (this.height - 40 - top) / rowH);
      matMaxScroll = Math.max(0, mats.size() - rows);
      if (matScroll > matMaxScroll) matScroll = matMaxScroll;
      for (int i = matScroll; i < mats.size() && i < matScroll + rows; i++) {
         Schematic.ItemNeed e = mats.get(i);
         int y = top + (i - matScroll) * rowH;
         if (e.item() != Items.AIR) g.item(new ItemStack(e.item()), px, y);
         boolean ok = e.item() != Items.AIR && inventoryCount(e.item()) >= e.count();
         String line = ok
               ? "§a" + e.count() + "§7× §a" + e.label() + " §a✓"
               : "§f" + e.count() + "§7× §f" + e.label();
         g.text(this.font, Component.literal(line), px + 20, y + 4, 0xFFFFFFFF);
      }
      if (matMaxScroll > 0) {
         g.text(this.font, Component.literal("§8scroll ▲▼ · " + (matScroll + 1) + "–"
               + Math.min(mats.size(), matScroll + rows) + " of " + mats.size()), px, this.height - 34, 0xFF888888);
      }
   }

   private void drawLoaded(GuiGraphicsExtractor g, int px) {
      List<BuilderManager.Loaded> list = BuilderManager.loadedList;
      g.text(this.font, Component.literal("§7Loaded §8(⇱ move here · ✖ unload"
            + (list.size() > PER_ROWS ? " · scroll ▲▼" : "") + ")"), px, 74, 0xFFAAAAAA);
      if (list.isEmpty()) {
         g.text(this.font, Component.literal("§8None staged — pick one from the schematics list."), px, 88, 0xFF888888);
         return;
      }
      int shown = Math.min(list.size() - loadedScroll, PER_ROWS);
      for (int i = 0; i < shown; i++) {
         BuilderManager.Loaded l = list.get(loadedScroll + i);
         String state = l.task == null ? "§8staged"
               : switch (l.task.state) {
                  case DONE -> "§adone";
                  case RUNNING -> "§a" + l.task.percent() + "%";
                  case WAITING_MATERIALS -> "§eneeds " + l.task.waitingNeed + " " + l.task.waitingFor;
                  case WAITING_CHUNKS -> "§emove closer";
                  case WAITING_CHEST -> "§cno chest";
                  case PAUSED -> "§7paused " + l.task.percent() + "%";
                  case CANCELLED -> "§8stopped";
               };
         String label = "§f" + trim(l.schem.name, 16) + " " + state;
         g.text(this.font, Component.literal(label), px, 82 + i * 22, 0xFFFFFFFF);
      }
      if (list.size() > PER_ROWS) {
         g.text(this.font, Component.literal("§8" + (loadedScroll + 1) + "–" + (loadedScroll + shown)
               + " of " + list.size() + " · scroll ▲▼"), px, 82 + shown * 22 + 28, 0xFF888888);
      }
   }

   private void loadFileList() {
      files.clear();
      Path dir = BuilderManager.schematicDir();
      try (Stream<Path> s = Files.list(dir)) {
         s.filter(Files::isRegularFile).filter(BuilderScreen::supported).sorted().forEach(files::add);
      } catch (Exception ignored) {
      }
   }

   private static boolean supported(Path p) {
      String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
      for (String e : EXTS) if (n.endsWith(e)) return true;
      return false;
   }

   private static String trim(String s, int max) {
      return s.length() <= max ? s : s.substring(0, max - 1) + "…";
   }

   @Override
   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      int dir = (int) Math.signum(scrollY);
      switch (panel) {
         case MATERIALS -> {
            if (matMaxScroll > 0) {
               matScroll = Math.max(0, Math.min(matMaxScroll, matScroll - dir));
               return true;
            }
         }
         case LOADED -> { // scroll through the staged list, same as the materials view
            int max = Math.max(0, BuilderManager.loadedList.size() - PER_ROWS);
            if (max > 0) {
               loadedScroll = Math.max(0, Math.min(max, loadedScroll - dir));
               rebuildWidgets(); // the ⇱/✖ buttons move with the rows
               return true;
            }
         }
         case FILES -> { // wheel flips pages
            int pages = (files.size() + PER_PAGE - 1) / PER_PAGE;
            if (pages > 1) {
               int p = Math.max(0, Math.min(pages - 1, page - dir));
               if (p != page) { page = p; rebuildWidgets(); }
               return true;
            }
         }
      }
      return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(g, mouseX, mouseY, partialTick); // dim background + widgets
      g.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
      g.centeredText(this.font, Component.literal(BuilderManager.statusLine()), this.width / 2, 26, 0xFFFFFFFF);

      g.text(this.font, Component.literal("§7Controls"), LEFT_X, 38, 0xFFAAAAAA);

      int px = panelX();
      switch (panel) {
         case FILES -> {
            g.text(this.font, Component.literal("§7Schematics §8(click to stage here)"), px, 62, 0xFFAAAAAA);
            if (files.isEmpty()) {
               g.text(this.font, Component.literal("§8Empty — drop .schem/.litematic files in"), px, 76, 0xFF888888);
               g.text(this.font, Component.literal("§8config/fablevision/schematics/, then ⟳"), px, 88, 0xFF888888);
            }
         }
         case LOADED -> drawLoaded(g, px);
         case MATERIALS -> drawMaterials(g, px);
      }

      String fb = feedback == null ? "" : feedback.replace("\n", "   ");
      g.text(this.font, Component.literal(fb), LEFT_X, this.height - 22, 0xFFDDDDDD);
   }

   @Override
   public boolean isPauseScreen() {
      return false; // keep the world (and any running build) ticking behind the GUI
   }
}
