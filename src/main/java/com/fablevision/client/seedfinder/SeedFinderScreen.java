package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.List;
import com.fablevision.client.FableVisionClient;
import com.fablevision.client.FableVisionConfig;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;

/**
 * The standalone Seed Finder GUI (/seedfind): pick what the seed must have on the left,
 * browse structures/biomes on the right, Start, watch the live counter, click a result
 * to copy the seed. Search state lives in {@link SeedFinder} (static), so closing this
 * screen never stops or loses a search.
 */
public class SeedFinderScreen extends Screen {
   private enum Panel { TARGETS, BIOMES, RESULTS }

   private static final int LEFT_X = 40;
   private static final int CTRL_W = 180;
   private static final int PANEL_W = 200;
   private static final int PER_PAGE = 9;

   // ── Chosen criteria (static — survives closing the screen) ──────────────
   static final List<SeedCriteria.StructureTarget> picked = new ArrayList<>();
   static Identifier pickedBiome;
   static String pickedBiomeLabel = "";
   static int strongholdRadius = 0;
   static int strongholdEyes = -1; // -1 = off, else require the nearest portal to have ≥N eyes
   static boolean sequentialSeeds = false;

   private static int nextFastDistance() {
      int[] opts = FableVisionConfig.SEED_FAST_DISTANCES;
      for (int i = 0; i < opts.length; i++) {
         if (opts[i] == FableVisionConfig.seedFastDistance) {
            return opts[(i + 1) % opts.length];
         }
      }
      return opts[0];
   }

   // ── Worldgen catalog, built in the background from a LIVE registry source ─
   private static volatile List<SeedCriteria.StructureTarget> catalog;
   private static volatile List<Identifier> overworldBiomes;
   private static volatile String catalogError;
   private static volatile HolderLookup.Provider catalogSource;
   private static volatile boolean catalogBuilding;

   private Panel panel = Panel.TARGETS;
   private int page = 0;
   private boolean builtWithCatalog;
   private String feedback = "";
   private long rateAtMs;
   private long rateAtChecked;
   private long ratePerSec;
   private final List<IconAt> icons = new ArrayList<>();

   private record IconAt(ItemStack stack, int x, int y) {}

   public SeedFinderScreen() {
      super(Component.literal("FableVision — Seed Finder"));
      SeedFinder.Job job = SeedFinder.current();
      if (job != null && (!job.results.isEmpty() || job.running)) {
         panel = Panel.RESULTS;
      }
   }

   /**
    * (Re)builds the catalog off-thread from the given registries — the Create World
    * screen's or your singleplayer world's. Null source (remote server / bare menu)
    * leaves an explanatory error instead.
    */
   static synchronized void ensureCatalog(HolderLookup.Provider source) {
      if (source == null) {
         if (!catalogReady()) {
            catalogError = "Seed finder needs your own world — open one in singleplayer, or use the Create World screen.";
         }
         return;
      }
      if (catalogSource == source && (catalogReady() || catalogBuilding)) {
         return;
      }
      catalogSource = source;
      catalogBuilding = true;
      catalogError = null;
      catalog = null;
      overworldBiomes = null;
      Thread t = new Thread(() -> {
         try {
            WorldgenContext ctx = WorldgenContext.get(source);
            overworldBiomes = SeedCatalog.biomes(ctx);
            catalog = SeedCriteria.catalog(ctx);
         } catch (Throwable e) {
            FableVisionClient.LOGGER.error("Seed finder catalog bootstrap failed", e);
            catalogError = "World-gen data failed to load: " + e.getClass().getSimpleName();
         } finally {
            catalogBuilding = false;
         }
      }, "FableVision-SeedFinder-Bootstrap");
      t.setDaemon(true);
      t.start();
   }

   /** The registry source the standalone screen searches with (your singleplayer world). */
   static HolderLookup.Provider currentSource() {
      return catalogSource != null ? catalogSource : WorldgenContext.findSource(null);
   }

   static boolean catalogReady() {
      return catalog != null && overworldBiomes != null;
   }

   static String catalogError() {
      return catalogError;
   }

   static List<SeedCriteria.StructureTarget> catalogList() {
      return catalog;
   }

   static List<Identifier> biomeList() {
      return overworldBiomes;
   }

   static String prettify(String path) {
      String[] words = path.split("_");
      StringBuilder sb = new StringBuilder();
      for (String w : words) {
         if (!w.isEmpty()) {
            if (sb.length() > 0) {
               sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
         }
      }
      return sb.toString();
   }

   /** The criteria the GUI currently describes (fresh copy — safe to hand to a job). */
   public static SeedCriteria buildCriteria() {
      SeedCriteria c = new SeedCriteria();
      int radius = FableVisionConfig.seedRadius();
      for (SeedCriteria.StructureTarget t : picked) {
         c.structures.add(t.withRadius(radius));
      }
      if (pickedBiome != null) {
         c.biomes.add(new SeedCriteria.BiomeTarget(
               net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, pickedBiome),
               pickedBiomeLabel, SeedCatalog.biomeDim(pickedBiome), radius, 0));
      }
      c.strongholdEyes = strongholdEyes;
      // Eyes always target the NEAREST portal, so radius is irrelevant there — and forcing it to
      // 0 skips the pricey per-seed real-spawn search (measuring from 0,0 instead), which is the
      // single biggest reason an eye search used to crawl.
      c.strongholdRadius = strongholdEyes >= 0 ? 0 : strongholdRadius;
      return c;
   }

   private int panelX() {
      return Math.max(CTRL_W + LEFT_X + 10, this.width - 40 - PANEL_W);
   }

   @Override
   protected void init() {
      ensureCatalog(WorldgenContext.findSource(null));
      builtWithCatalog = catalogReady();
      icons.clear();
      SeedFinder.Job job = SeedFinder.current();
      boolean running = job != null && job.running;

      // ── Left: criteria + controls ─────────────────────────────────────
      int x = LEFT_X;
      int y = 40;
      addRenderableWidget(Button.builder(Component.literal("➕ Add structure"), b -> switchPanel(Panel.TARGETS))
            .bounds(x, y, CTRL_W, 20).build());
      addRenderableWidget(Button.builder(Component.literal(pickedBiome == null ? "🌿 Spawn biome: any" : "🌿 " + pickedBiomeLabel),
            b -> switchPanel(Panel.BIOMES)).bounds(x, y += 22, CTRL_W, 20).build());
      addRenderableWidget(Button.builder(Component.literal(strongholdRadius <= 0 ? "Stronghold: off" : "Stronghold: nearest"), b -> {
         strongholdRadius = strongholdRadius > 0 ? 0 : 8000;
         rebuildWidgets();
      }).bounds(x, y += 22, CTRL_W, 20).build());
      addRenderableWidget(Button.builder(Component.literal(strongholdEyes < 0 ? "Portal eyes: any" : "Portal eyes: ≥" + strongholdEyes), b -> {
         strongholdEyes = strongholdEyes >= 12 ? -1 : strongholdEyes + 1;
         feedback = strongholdEyes >= 10 ? "§c≥" + strongholdEyes + " eyes is astronomically rare — may never finish."
               : strongholdEyes >= 7 ? "§e≥" + strongholdEyes + " eyes is rare — this can take a while."
               : strongholdEyes >= 0 ? "Finds a stronghold whose portal already has ≥" + strongholdEyes + " eyes."
               : "Portal eyes: any.";
         rebuildWidgets();
      }).bounds(x, y += 22, CTRL_W, 20).build());
      addRenderableWidget(Button.builder(Component.literal(sequentialSeeds ? "Seeds: 0, 1, 2, …" : "Seeds: random"), b -> {
         sequentialSeeds = !sequentialSeeds;
         rebuildWidgets();
      }).bounds(x, y += 22, CTRL_W, 20).build());
      addRenderableWidget(Button.builder(Component.literal(FableVisionConfig.seedFastMode
            ? "🎯 Mode: Fast (near spawn)" : "🎯 Mode: Exact (at spawn)"), b -> {
         FableVisionConfig.seedFastMode = !FableVisionConfig.seedFastMode;
         FableVisionConfig.save();
         rebuildWidgets();
      }).bounds(x, y += 22, CTRL_W, 20).build());
      // Distance is mode-driven now: Exact = right at spawn; Fast = adjustable, near spawn.
      if (FableVisionConfig.seedFastMode) {
         addRenderableWidget(Button.builder(Component.literal("⚡ Within " + FableVisionConfig.seedFastDistance + " of spawn"), b -> {
            FableVisionConfig.seedFastDistance = nextFastDistance();
            FableVisionConfig.save();
            rebuildWidgets();
         }).bounds(x, y += 22, CTRL_W, 20).build());
      } else {
         Button atSpawn = Button.builder(Component.literal("🎯 Distance: right at spawn"), b -> {}).bounds(x, y += 22, CTRL_W, 20).build();
         atSpawn.active = false;
         addRenderableWidget(atSpawn);
      }

      // Picked criteria rows: click a row to remove it.
      int rowY = y + 28;
      for (SeedCriteria.StructureTarget t : List.copyOf(picked)) {
         SeedCriteria.StructureTarget target = t;
         addRenderableWidget(Button.builder(Component.literal("✖ " + trim(target.label, 20)), b -> {
            picked.remove(target);
            rebuildWidgets();
         }).bounds(x, rowY, CTRL_W, 20).build());
         rowY += 22;
      }

      addRenderableWidget(Button.builder(Component.literal(running ? "■ Stop" : "🔍 Start search"), b -> {
         if (SeedFinder.current() != null && SeedFinder.current().running) {
            SeedFinder.stop();
         } else {
            SeedCriteria c = buildCriteria();
            if (c.isEmpty()) {
               feedback = "Pick at least one structure/biome first.";
            } else {
               SeedFinder.start(c, currentSource(), sequentialSeeds ? 0L : null, 10, FableVisionConfig.seedFastMode);
               panel = Panel.RESULTS;
               page = 0;
            }
         }
         rebuildWidgets();
      }).bounds(x, this.height - 52, CTRL_W, 20).build());

      // ── Right panel ───────────────────────────────────────────────────
      int px = panelX();
      switch (panel) {
         case TARGETS -> initTargetsPanel(px);
         case BIOMES -> initBiomesPanel(px);
         case RESULTS -> initResultsPanel(px);
      }

      addRenderableWidget(Button.builder(Component.literal("📄 Results"), b -> switchPanel(Panel.RESULTS))
            .bounds(px, this.height - 52, 96, 20).build());
      addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(px + 104, this.height - 52, PANEL_W - 104, 20).build());
   }

   private void initTargetsPanel(int px) {
      if (!catalogReady()) {
         return;
      }
      List<SeedCriteria.StructureTarget> list = catalog;
      int startIdx = page * PER_PAGE;
      int listY = 62;
      for (int i = 0; i < PER_PAGE && startIdx + i < list.size(); i++) {
         SeedCriteria.StructureTarget t = list.get(startIdx + i);
         icons.add(new IconAt(SeedIcons.structure(t.label), px + 1, listY + i * 22 + 2));
         addRenderableWidget(Button.builder(Component.literal(trim(t.label, 24)), b -> {
            if (t.special == SeedCriteria.Special.DUNGEON) {
               feedback = "§e" + t.note;
            } else if (t.special == SeedCriteria.Special.STRONGHOLD) {
               strongholdRadius = strongholdRadius > 0 ? 0 : 8000;
               feedback = strongholdRadius > 0 ? "Stronghold added — finds the nearest one." : "Stronghold cleared.";
            } else if (picked.size() >= 6) {
               feedback = "6 structures max — remove one first.";
            } else {
               picked.add(t.withRadius(FableVisionConfig.seedRadius()));
               feedback = t.label + " added." + (t.note.isEmpty() ? "" : " §e(" + t.note + ")");
            }
            rebuildWidgets();
         }).bounds(px + 20, listY + i * 22, PANEL_W - 20, 20).build());
      }
      pageNav(px, listY, list.size());
   }

   private void initBiomesPanel(int px) {
      if (!catalogReady()) {
         return;
      }
      List<Identifier> list = overworldBiomes;
      int listY = 62;
      if (page == 0) {
         addRenderableWidget(Button.builder(Component.literal("(any biome — clear)"), b -> {
            pickedBiome = null;
            pickedBiomeLabel = "";
            switchPanel(Panel.TARGETS);
         }).bounds(px + 20, listY, PANEL_W - 20, 20).build());
      }
      int offset = page == 0 ? 1 : 0;
      int startIdx = page == 0 ? 0 : page * PER_PAGE - 1;
      for (int i = 0; i + offset < PER_PAGE && startIdx + i < list.size(); i++) {
         Identifier id = list.get(startIdx + i);
         String label = prettify(id.getPath());
         icons.add(new IconAt(SeedIcons.biome(id.getPath()), px + 1, listY + (i + offset) * 22 + 2));
         addRenderableWidget(Button.builder(Component.literal(trim(label, 24)), b -> {
            pickedBiome = id;
            pickedBiomeLabel = label;
            switchPanel(Panel.TARGETS);
         }).bounds(px + 20, listY + (i + offset) * 22, PANEL_W - 20, 20).build());
      }
      pageNav(px, listY + 24, list.size() + 1);
   }

   private void initResultsPanel(int px) {
      SeedFinder.Job job = SeedFinder.current();
      if (job == null) {
         return;
      }
      List<SeedFinder.Result> results = List.copyOf(job.results);
      int listY = 62;
      int rowH = 34;
      int rows = Math.max(1, (this.height - 120 - listY) / rowH);
      int startIdx = page * rows;
      for (int i = 0; i < rows && startIdx + i < results.size(); i++) {
         SeedFinder.Result r = results.get(startIdx + i);
         addRenderableWidget(Button.builder(Component.literal("📋 " + r.seed()), b -> {
            this.minecraft.keyboardHandler.setClipboard(Long.toString(r.seed()));
            feedback = "Seed " + r.seed() + " copied — paste it in Create World → More → Seed.";
         }).bounds(px, listY + i * rowH, PANEL_W, 20).build());
      }
      if (startIdx > 0 || startIdx + rows < results.size()) {
         if (page > 0) {
            addRenderableWidget(Button.builder(Component.literal("◀"), b -> { page--; rebuildWidgets(); })
                  .bounds(px, this.height - 76, 40, 20).build());
         }
         if (startIdx + rows < results.size()) {
            addRenderableWidget(Button.builder(Component.literal("▶"), b -> { page++; rebuildWidgets(); })
                  .bounds(px + PANEL_W - 40, this.height - 76, 40, 20).build());
         }
      }
   }

   private void pageNav(int px, int listY, int total) {
      int navY = listY + PER_PAGE * 22 + 26;
      if (page > 0) {
         addRenderableWidget(Button.builder(Component.literal("◀ Prev"), b -> { page--; rebuildWidgets(); })
               .bounds(px, navY, 60, 20).build());
      }
      if ((page + 1) * PER_PAGE < total) {
         addRenderableWidget(Button.builder(Component.literal("Next ▶"), b -> { page++; rebuildWidgets(); })
               .bounds(px + PANEL_W - 60, navY, 60, 20).build());
      }
   }

   private void switchPanel(Panel target) {
      panel = target;
      page = 0;
      rebuildWidgets();
   }

   private static String trim(String s, int max) {
      return s.length() <= max ? s : s.substring(0, max - 1) + "…";
   }

   private String statusLine() {
      if (catalogError != null) {
         return "§c" + catalogError;
      }
      SeedFinder.Job job = SeedFinder.current();
      if (job == null) {
         return catalogReady() ? "§7Add things on the left, then §fStart search§7."
               : "§7Preparing world-gen data… (one-time, a few seconds)";
      }
      if (job.error != null) {
         return "§c" + job.error;
      }
      long checked = job.checked.get();
      long now = System.currentTimeMillis();
      if (now - rateAtMs >= 1000) {
         ratePerSec = rateAtMs == 0 ? 0 : (checked - rateAtChecked) * 1000 / Math.max(1, now - rateAtMs);
         rateAtMs = now;
         rateAtChecked = checked;
      }
      String base = String.format("checked %,d · %,d/sec · %d bots · found %d/%d",
            checked, ratePerSec, job.threads, job.results.size(), job.wantCount);
      if (job.bootstrapping) {
         return "§7Preparing world-gen data… (one-time, a few seconds)";
      }
      if (job.running) {
         return "§e⏳ Searching " + job.summary + " §7— " + base;
      }
      String total = String.format("searched %,d seeds in %s", checked, SeedFinder.prettyMs(job.elapsedMs()));
      return job.results.isEmpty() ? "§7Stopped — " + total
            : "§a✓ Done — " + total + " · found " + job.results.size() + "§7. Click a result to copy it.";
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
      if (!builtWithCatalog && catalogReady()) {
         builtWithCatalog = true;
         rebuildWidgets();
      }
      super.extractRenderState(g, mouseX, mouseY, partialTick);
      g.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
      g.centeredText(this.font, Component.literal(statusLine()), this.width / 2, 26, 0xFFFFFFFF);

      g.text(this.font, Component.literal("§7Your seed must have:"), LEFT_X, 30 + 98, 0xFFAAAAAA);
      if (picked.isEmpty() && pickedBiome == null && strongholdRadius <= 0) {
         g.text(this.font, Component.literal("§8nothing yet — add structures/biome"), LEFT_X, 30 + 110, 0xFF888888);
      }

      int px = panelX();
      switch (panel) {
         case TARGETS -> g.text(this.font, Component.literal("§7Structures §8(click to add)"), px, 50, 0xFFAAAAAA);
         case BIOMES -> g.text(this.font, Component.literal("§7Spawn biome §8(click to pick · slower)"), px, 50, 0xFFAAAAAA);
         case RESULTS -> {
            g.text(this.font, Component.literal("§7Found seeds §8(click to copy)"), px, 50, 0xFFAAAAAA);
            SeedFinder.Job job = SeedFinder.current();
            if (job != null) {
               List<SeedFinder.Result> results = List.copyOf(job.results);
               int rowH = 34;
               int rows = Math.max(1, (this.height - 120 - 62) / rowH);
               int startIdx = page * rows;
               for (int i = 0; i < rows && startIdx + i < results.size(); i++) {
                  SeedFinder.Result r = results.get(startIdx + i);
                  g.text(this.font, Component.literal("§8" + trim(String.join(" · ", r.matches()), 34)),
                        px, 62 + i * rowH + 22, 0xFF888888);
               }
            }
         }
      }
      if ((panel == Panel.TARGETS || panel == Panel.BIOMES) && !catalogReady() && catalogError == null) {
         g.text(this.font, Component.literal("§8Loading world-gen data…"), px, 64, 0xFF888888);
      }
      for (IconAt icon : icons) {
         g.item(icon.stack(), icon.x(), icon.y());
      }

      g.text(this.font, Component.literal("§7" + feedback), LEFT_X, this.height - 24, 0xFFDDDDDD);
   }

   @Override
   public boolean isPauseScreen() {
      return false; // the search runs on background threads either way
   }

   // ── Persistence: last results survive a restart ──────────────────────────

   private static final Path STORE = FabricLoader.getInstance().getConfigDir().resolve("fablevision").resolve("seedfinder.json");
   private static SeedFinder.Job lastSaved;

   /** Called from the client tick: when a job finishes, remember its results. */
   public static void tickAutosave() {
      SeedFinder.Job job = SeedFinder.current();
      if (job == null || job.running || job == lastSaved || job.results.isEmpty()) {
         return;
      }
      lastSaved = job;
      try {
         Files.createDirectories(STORE.getParent());
         JsonObject root = new JsonObject();
         root.addProperty("criteria", job.summary);
         root.addProperty("stats", String.format("searched %,d seeds in %s", job.checked.get(), SeedFinder.prettyMs(job.elapsedMs())));
         JsonArray arr = new JsonArray();
         for (SeedFinder.Result r : List.copyOf(job.results)) {
            JsonObject o = new JsonObject();
            o.addProperty("seed", r.seed());
            JsonArray m = new JsonArray();
            r.matches().forEach(m::add);
            o.add("matches", m);
            arr.add(o);
         }
         root.add("results", arr);
         Files.writeString(STORE, new GsonBuilder().setPrettyPrinting().create().toJson(root));
      } catch (Exception e) {
         FableVisionClient.LOGGER.warn("Could not save seed finder results", e);
      }
   }

   /** Prints the last saved results (used by /seedfind last). */
   public static String lastSavedSummary() {
      try {
         if (!Files.exists(STORE)) {
            return "No saved results yet.";
         }
         JsonObject root = JsonParser.parseString(Files.readString(STORE)).getAsJsonObject();
         StringBuilder sb = new StringBuilder("Last search: " + root.get("criteria").getAsString());
         if (root.has("stats")) {
            sb.append(" §7(").append(root.get("stats").getAsString()).append(')');
         }
         for (var el : root.getAsJsonArray("results")) {
            JsonObject o = el.getAsJsonObject();
            sb.append("\n§f").append(o.get("seed").getAsLong()).append(" §7— ");
            List<String> m = new ArrayList<>();
            o.getAsJsonArray("matches").forEach(x -> m.add(x.getAsString()));
            sb.append(String.join(" · ", m));
         }
         return sb.toString();
      } catch (Exception e) {
         return "Saved results file could not be read.";
      }
   }
}
