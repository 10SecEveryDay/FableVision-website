package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.fablevision.client.FableVisionConfig;
import com.tensec.aiscreen.AiVision;
import com.tensec.aiscreen.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

/**
 * The "want a custom spawn?" ask before creating a singleplayer world.
 *
 * Describe the wish in your own words (the AI ONLY translates words → search settings) or
 * pick a structure/biome from the icon lists — either way {@link SeedFinder} legit-searches
 * real seeds with the game's own world-gen code. The moment one is found, the seed is typed
 * into the Create World screen and you're taken straight back — just press Create.
 */
public class SpawnWishScreen extends Screen {
   private enum Phase { PICK, SEARCHING }
   private enum Picker { NONE, STRUCTURE, BIOME }

   /** One-shot guard so returning to the Create World screen doesn't re-ask. */
   private static boolean suppressOnce;
   /** Last seed we typed into the Create World screen (for its little ✓ badge). */
   public static volatile String lastAppliedSeed;
   /** Search stats for that seed — full sentence for chat, short form for the badge. */
   public static volatile String lastAppliedStats = "";
   public static volatile String lastAppliedShort = "";
   /** What the search verified ("Spawn @ 120, -80", "Pale Garden right at spawn"). */
   public static volatile List<String> lastAppliedMatches = List.of();
   /** The AI's honest "couldn't check this part" note, if any. */
   public static volatile String lastAppliedNote = "";
   private static boolean announcePending;

   public static boolean consumeSuppress() {
      boolean was = suppressOnce;
      suppressOnce = false;
      return was;
   }

   public static void suppressNext() {
      suppressOnce = true;
   }

   /** One-shot: true when the freshly joined world is the seed we found (chat the stats). */
   public static boolean consumeAnnounce(String worldSeed) {
      if (announcePending && worldSeed != null && worldSeed.equals(lastAppliedSeed)) {
         announcePending = false;
         return true;
      }
      return false;
   }

   private static final int PER_PAGE = 8;

   private final CreateWorldScreen parent;
   private final HolderLookup.Provider registries;
   private EditBox wishBox;
   private String wishDraft = "";
   private int structIdx = -1;
   private int biomeIdx = -1;
   private boolean aiWaiting;
   private String feedback = "";
   private String searchNote = "";
   private Phase builtPhase = Phase.PICK;
   private Picker picker = Picker.NONE;
   private Picker builtPicker = Picker.NONE;
   private int pickerPage;
   private boolean builtWithCatalog;
   private SeedFinder.Job myJob;
   private long searchStartMs;
   private boolean seedApplied;
   private long rateAtMs;
   private long rateAtChecked;
   private long ratePerSec;
   private final List<IconAt> icons = new ArrayList<>();

   private record IconAt(ItemStack stack, int x, int y) {}

   public SpawnWishScreen(CreateWorldScreen parent) {
      super(Component.literal("Custom spawn?"));
      this.parent = parent;
      this.registries = WorldgenContext.findSource(parent);
   }

   private Phase phase() {
      return myJob != null && (myJob.running || myJob.bootstrapping) ? Phase.SEARCHING : Phase.PICK;
   }

   @Override
   protected void init() {
      SeedFinderScreen.ensureCatalog(registries);
      builtWithCatalog = SeedFinderScreen.catalogReady();
      builtPhase = phase();
      builtPicker = picker;
      icons.clear();
      int boxW = Math.min(420, this.width - 40);
      int left = (this.width - boxW) / 2;

      if (builtPhase == Phase.SEARCHING) {
         addRenderableWidget(Button.builder(Component.literal("■ Stop searching"), b -> {
            SeedFinder.stop();
            feedback = String.format("Stopped after %,d seeds. Pick something else, or go on without a custom spawn.",
                  myJob == null ? 0 : myJob.checked.get());
         }).bounds(left + boxW / 2 - 80, 150, 160, 20).build());
      } else if (picker != Picker.NONE) {
         initPicker(left, boxW);
      } else {
         wishBox = new EditBox(this.font, left, 66, boxW - 112, 20, Component.literal("wish"));
         wishBox.setMaxLength(200);
         wishBox.setValue(wishDraft);
         addRenderableWidget(wishBox);
         setInitialFocus(wishBox);

         // Always pressable — if something's missing (key/data), pressing tells you what.
         addRenderableWidget(Button.builder(Component.literal(aiWaiting ? "✨ Thinking…" : "✨ Find with AI"), b -> startAi())
               .bounds(left + boxW - 106, 66, 106, 20).build());

         // The wish has its OWN key, separate from the G-menu's — seed searches never
         // spend the key/quota used for screen questions. ⚙ edits just this one.
         addRenderableWidget(Button.builder(Component.literal(Config.getKey(Config.Keyset.SPAWN).isEmpty() ? "⚙ Add AI key" : "⚙ AI key ✓"), b -> {
            stash();
            this.minecraft.setScreen(new com.tensec.aiscreen.ConfigScreen(this, Config.Keyset.SPAWN));
         }).bounds(left + boxW - 106, 88, 106, 20).build());

         var cat = SeedFinderScreen.catalogList();
         var biomes = SeedFinderScreen.biomeList();
         String structLabel = structIdx >= 0 && cat != null && structIdx < cat.size() ? cat.get(structIdx).label : "none";
         String biomeLabel = biomeIdx >= 0 && biomes != null && biomeIdx < biomes.size()
               ? SeedFinderScreen.prettify(biomes.get(biomeIdx).getPath()) : "any";

         icons.add(new IconAt(structIdx >= 0 && cat != null && structIdx < cat.size()
               ? SeedIcons.structure(cat.get(structIdx).label) : new ItemStack(Items.FILLED_MAP), left + 1, 124));
         addRenderableWidget(Button.builder(Component.literal("Structure: " + trim(structLabel, 13)), b -> {
            stash();
            picker = Picker.STRUCTURE;
            pickerPage = 0;
            rebuildWidgets();
         }).bounds(left + 20, 122, boxW / 2 - 22, 20).build());

         icons.add(new IconAt(biomeIdx >= 0 && biomes != null && biomeIdx < biomes.size()
               ? SeedIcons.biome(biomes.get(biomeIdx).getPath()) : new ItemStack(Items.GRASS_BLOCK), left + boxW / 2 + 3, 124));
         addRenderableWidget(Button.builder(Component.literal("Biome: " + trim(biomeLabel, 15)), b -> {
            stash();
            picker = Picker.BIOME;
            pickerPage = 0;
            rebuildWidgets();
         }).bounds(left + boxW / 2 + 22, 122, boxW / 2 - 22, 20).build());

         // Distance is set by the mode now (no more radius button): Exact = right where you
         // spawn; Fast = as close to spawn as this distance, quicker. Only Fast is adjustable.
         if (FableVisionConfig.seedFastMode) {
            addRenderableWidget(Button.builder(Component.literal("⚡ Within " + FableVisionConfig.seedFastDistance + " of spawn"), b -> {
               FableVisionConfig.seedFastDistance = nextFastDistance();
               FableVisionConfig.save();
               stash();
               rebuildWidgets();
            }).bounds(left, 146, boxW / 2 - 2, 20).build());
         } else {
            Button atSpawn = Button.builder(Component.literal("🎯 Right at spawn"), b -> {}).bounds(left, 146, boxW / 2 - 2, 20).build();
            atSpawn.active = false;
            addRenderableWidget(atSpawn);
         }
         addRenderableWidget(Button.builder(Component.literal("🔍 Find seed"), b -> startSimple())
               .bounds(left + boxW / 2 + 2, 146, boxW / 2 - 2, 20).build());

         // Fast vs Exact — never changes spawn either way, only how distance is measured.
         // Kept a row higher than the bottom buttons so the feedback line (height-56) has its own
         // space and never renders on top of this toggle.
         addRenderableWidget(Button.builder(Component.literal(FableVisionConfig.seedFastMode
               ? "⚡ Fast: near spawn — quickest (click for Exact)" : "🎯 Exact: RIGHT where you spawn — thorough (click for Fast)"), b -> {
            FableVisionConfig.seedFastMode = !FableVisionConfig.seedFastMode;
            FableVisionConfig.save();
            stash();
            rebuildWidgets();
         }).bounds(left, this.height - 88, boxW, 20).build());
      }

      addRenderableWidget(Button.builder(Component.literal("No thanks — normal world"), b -> back())
            .bounds(left, this.height - 40, boxW / 2 - 2, 20).build());
      addRenderableWidget(Button.builder(Component.literal("Ask me: " + (FableVisionConfig.askCustomSpawn ? "§aON" : "§cOFF")), b -> {
         FableVisionConfig.askCustomSpawn = !FableVisionConfig.askCustomSpawn;
         FableVisionConfig.save();
         rebuildWidgets();
      }).bounds(left + boxW / 2 + 2, this.height - 40, boxW / 2 - 2, 20).build());
   }

   /** The small pick-list: one icon + name per row, click to choose. */
   private void initPicker(int left, int boxW) {
      boolean structures = picker == Picker.STRUCTURE;
      var cat = SeedFinderScreen.catalogList();
      var biomes = SeedFinderScreen.biomeList();
      int total = structures ? (cat == null ? 0 : cat.size()) : (biomes == null ? 0 : biomes.size());
      int listY = 56;

      if (pickerPage == 0) {
         icons.add(new IconAt(new ItemStack(Items.BARRIER), left + 1, listY + 2));
         addRenderableWidget(Button.builder(Component.literal(structures ? "(no structure)" : "(any biome)"), b -> {
            if (structures) {
               structIdx = -1;
            } else {
               biomeIdx = -1;
            }
            picker = Picker.NONE;
            rebuildWidgets();
         }).bounds(left + 20, listY, boxW - 20, 20).build());
      }
      int offset = pickerPage == 0 ? 1 : 0;
      int startIdx = pickerPage == 0 ? 0 : pickerPage * PER_PAGE - 1;
      for (int i = 0; i + offset < PER_PAGE && startIdx + i < total; i++) {
         final int idx = startIdx + i;
         int rowY = listY + (i + offset) * 22;
         String label;
         ItemStack icon;
         if (structures) {
            label = cat.get(idx).label;
            icon = SeedIcons.structure(label);
         } else {
            Identifier id = biomes.get(idx);
            label = SeedFinderScreen.prettify(id.getPath());
            icon = SeedIcons.biome(id.getPath());
         }
         icons.add(new IconAt(icon, left + 1, rowY + 2));
         addRenderableWidget(Button.builder(Component.literal(trim(label, 30)), b -> {
            if (structures) {
               structIdx = idx;
            } else {
               biomeIdx = idx;
            }
            picker = Picker.NONE;
            rebuildWidgets();
         }).bounds(left + 20, rowY, boxW - 20, 20).build());
      }
      int navY = listY + PER_PAGE * 22 + 4;
      addRenderableWidget(Button.builder(Component.literal("◀ Back"), b -> {
         picker = Picker.NONE;
         rebuildWidgets();
      }).bounds(left, navY, 70, 20).build());
      if (pickerPage > 0) {
         addRenderableWidget(Button.builder(Component.literal("◀"), b -> { pickerPage--; rebuildWidgets(); })
               .bounds(left + boxW - 110, navY, 50, 20).build());
      }
      if ((pickerPage + 1) * PER_PAGE < total + 1) {
         addRenderableWidget(Button.builder(Component.literal("▶"), b -> { pickerPage++; rebuildWidgets(); })
               .bounds(left + boxW - 54, navY, 50, 20).build());
      }
   }

   private void stash() {
      if (wishBox != null) {
         wishDraft = wishBox.getValue();
      }
   }

   private void startAi() {
      stash();
      if (aiWaiting) {
         return;
      }
      if (SeedFinderScreen.catalogError() != null) {
         feedback = "§c" + SeedFinderScreen.catalogError();
         return;
      }
      if (!SeedFinderScreen.catalogReady()) {
         feedback = "World-gen data is still loading — try again in a second.";
         return;
      }
      if (Config.getKey(Config.Keyset.SPAWN).isEmpty()) {
         feedback = "§eNo AI key set — click §f⚙ Add AI key§e above. Or pick below, no AI needed.";
         return;
      }
      String wish = wishDraft.trim();
      if (wish.isEmpty()) {
         feedback = "Type what you want first — like \"village next to a jungle\".";
         return;
      }
      // Did they ask about portal eyes? That search isn't ready yet — we still find the
      // nearest stronghold and show a friendly "coming this week" note either way.
      final boolean eyesWish = wish.toLowerCase(Locale.ROOT).contains("eye");
      aiWaiting = true;
      feedback = "Asking the AI to turn that into a search…";
      rebuildWidgets();
      AiVision.ask(null, WishParser.buildPrompt(wish), List.of(), false, Config.Keyset.SPAWN, reply ->
            Minecraft.getInstance().execute(() -> {
               aiWaiting = false;
               WishParser.Outcome out = WishParser.parse(reply);
               // A hard failure (bad key / network / cooldown) is always shown, even for an
               // eyes wish — the player needs to fix it. A soft "nothing searchable" on an
               // eyes wish still falls through to a nearest-stronghold search below.
               boolean hardFail = out.error() != null
                     && (out.error().startsWith("⚠") || out.error().startsWith("No ") || out.error().startsWith("Cooldown"));
               if (hardFail || (out.error() != null && !eyesWish)) {
                  feedback = "§c" + friendly(out.error());
               } else {
                  feedback = "";
                  SeedCriteria criteria = out.criteria() != null ? out.criteria() : new SeedCriteria();
                  if (eyesWish) {
                     if (criteria.strongholdRadius <= 0) {
                        criteria.strongholdRadius = 8000; // strongholds are far — find the nearest
                     }
                     searchNote = WishParser.EYES_SOON;
                  } else {
                     searchNote = out.note() == null ? "" : out.note();
                     if (criteria.strongholdRadius > 0 && searchNote.isEmpty()) {
                        searchNote = WishParser.STRONGHOLD_FAR; // strongholds are never AT spawn
                     }
                  }
                  startSearch(criteria);
               }
               rebuildWidgets();
            }));
   }

   private void startSimple() {
      stash();
      if (!SeedFinderScreen.catalogReady()) {
         feedback = SeedFinderScreen.catalogError() != null ? "§c" + SeedFinderScreen.catalogError()
               : "World-gen data is still loading — one second.";
         return;
      }
      SeedCriteria criteria = new SeedCriteria();
      var cat = SeedFinderScreen.catalogList();
      var biomes = SeedFinderScreen.biomeList();
      searchNote = "";
      int radius = FableVisionConfig.seedRadius();
      if (structIdx >= 0 && structIdx < cat.size()) {
         SeedCriteria.StructureTarget t = cat.get(structIdx);
         if (t.special == SeedCriteria.Special.DUNGEON) {
            feedback = "§e" + t.note;
            return;
         } else if (t.special == SeedCriteria.Special.STRONGHOLD) {
            criteria.strongholdRadius = 8000; // strongholds are far by nature — just find the nearest
            searchNote = WishParser.STRONGHOLD_FAR; // and say so honestly, up front
         } else {
            criteria.structures.add(t.withRadius(radius));
            if (!t.note.isEmpty()) {
               searchNote = t.note;
            }
         }
      }
      if (biomeIdx >= 0 && biomeIdx < biomes.size()) {
         Identifier id = biomes.get(biomeIdx);
         criteria.biomes.add(new SeedCriteria.BiomeTarget(
               net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, id),
               SeedFinderScreen.prettify(id.getPath()), SeedCatalog.biomeDim(id), radius, 0));
      }
      if (criteria.isEmpty()) {
         feedback = "Pick a structure or a biome first (or use the AI box).";
         return;
      }
      startSearch(criteria);
   }

   private void startSearch(SeedCriteria criteria) {
      seedApplied = false;
      if (searchNote.isEmpty() && !criteria.notes().isEmpty()) {
         searchNote = criteria.notes();
      }
      myJob = SeedFinder.start(criteria, registries, null, 1, FableVisionConfig.seedFastMode);
      searchStartMs = System.currentTimeMillis();
      rateAtMs = 0;
      rebuildWidgets();
   }

   private void back() {
      stash();
      SeedFinder.stop();
      suppressNext();
      this.minecraft.setScreen(parent);
   }

   @Override
   public void onClose() {
      back();
   }

   @Override
   public boolean keyPressed(KeyEvent event) {
      if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)
            && builtPhase == Phase.PICK && picker == Picker.NONE && wishBox != null && wishBox.isFocused()) {
         startAi();
         return true;
      }
      return super.keyPressed(event);
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
      // Found one? Type the seed into the Create World screen and go STRAIGHT back to it.
      if (myJob != null && myJob.done() && !myJob.results.isEmpty() && !seedApplied) {
         seedApplied = true;
         SeedFinder.Result found = myJob.results.get(0);
         String seed = Long.toString(found.seed());
         parent.getUiState().setSeed(seed);
         lastAppliedSeed = seed;
         long checked = myJob.checked.get();
         String took = SeedFinder.prettyMs(myJob.elapsedMs());
         lastAppliedStats = String.format("searched %,d seeds in %s", checked, took);
         lastAppliedShort = shortCount(checked) + " seeds · " + took;
         lastAppliedMatches = found.matches();
         lastAppliedNote = searchNote;
         announcePending = true;
         back();
         return;
      }
      if (myJob != null && myJob.done() && myJob.error != null && !myJob.error.equals(feedback)) {
         feedback = "§c" + myJob.error;
      }
      if (phase() != builtPhase || picker != builtPicker || (!builtWithCatalog && SeedFinderScreen.catalogReady())) {
         stash();
         rebuildWidgets();
      }

      super.extractRenderState(g, mouseX, mouseY, partialTick);
      int boxW = Math.min(420, this.width - 40);
      int left = (this.width - boxW) / 2;
      g.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
      g.centeredText(this.font, Component.literal("§7Real seeds from the game's own world-gen — you spawn naturally, nothing is moved or spawned in."),
            this.width / 2, 28, 0xFFFFFFFF);

      if (builtPhase == Phase.SEARCHING) {
         long checked = myJob == null ? 0 : myJob.checked.get();
         long now = System.currentTimeMillis();
         if (now - rateAtMs >= 1000) {
            ratePerSec = rateAtMs == 0 ? 0 : (checked - rateAtChecked) * 1000 / Math.max(1, now - rateAtMs);
            rateAtMs = now;
            rateAtChecked = checked;
         }
         g.centeredText(this.font, Component.literal("§e⏳ Searching: §f" + (myJob == null ? "" : myJob.summary)), this.width / 2, 100, 0xFFFFFFFF);
         g.centeredText(this.font, Component.literal(String.format("§7checked %,d · %,d/sec · %d bots working",
               checked, ratePerSec, myJob == null ? 0 : myJob.threads)), this.width / 2, 116, 0xFFFFFFFF);
         SeedFinder.Result parked = myJob == null ? null : myJob.backup;
         if (parked != null && !myJob.done()) {
            // Exact-mode tiering: a ≤64 hit is in hand, still hunting a RIGHT-at-spawn one.
            g.centeredText(this.font, Component.literal("§a✓ found one " + myJob.backupWorst
                  + " blocks out — few more seconds hunting one RIGHT at spawn…"), this.width / 2, 130, 0xFFFFFFFF);
         } else {
            g.centeredText(this.font, Component.literal("§8the moment one is found, it's put into your world settings"), this.width / 2, 130, 0xFFFFFFFF);
         }
         if (!searchNote.isEmpty()) {
            if (searchNote.equals(WishParser.EYES_SOON) || searchNote.equals(WishParser.STRONGHOLD_FAR)) {
               g.centeredText(this.font, Component.literal("§e" + searchNote), this.width / 2, 174, 0xFFFFFFFF);
            } else {
               g.centeredText(this.font, Component.literal("§6Can't check: §f" + trim(searchNote, 60) + " §6— searching the rest."),
                     this.width / 2, 174, 0xFFFFFFFF);
            }
         }
         if (System.currentTimeMillis() - searchStartMs > 60_000) {
            g.centeredText(this.font, Component.literal("§8Taking long — this combo may be super rare or impossible."), this.width / 2, 184, 0xFFFFFFFF);
            g.centeredText(this.font, Component.literal("§8Stop and try a bigger distance or a simpler wish."), this.width / 2, 196, 0xFFFFFFFF);
         }
      } else if (picker != Picker.NONE) {
         g.text(this.font, Component.literal(picker == Picker.STRUCTURE ? "§7Pick a structure:" : "§7Pick a spawn biome:"), left, 44, 0xFFAAAAAA);
         if (!SeedFinderScreen.catalogReady()) {
            g.text(this.font, Component.literal("§8Loading world-gen data…"), left, 60, 0xFF888888);
         }
      } else {
         g.text(this.font, Component.literal("§7Say it in your own words:"), left, 54, 0xFFAAAAAA);
         // Example wish lives INSIDE the empty box (offset past the cursor), and the line
         // under it is wrapped to the space LEFT of the ⚙ key button — nothing can overlap it.
         if (wishBox != null && wishBox.getValue().isEmpty()) {
            g.text(this.font, Component.literal("§8village next to a jungle, portal close…"), left + 16, 72, 0xFF666666);
         }
         if (Config.getKey(Config.Keyset.SPAWN).isEmpty()) {
            java.util.List<net.minecraft.util.FormattedCharSequence> klines = this.font.split(
                  Component.literal("§8No AI key for wishes yet — click §7⚙ Add AI key §8(own key, separate from the G-menu). Or pick below, no AI."),
                  boxW - 112);
            int ky = 90;
            for (net.minecraft.util.FormattedCharSequence kl : klines) {
               if (ky > 100) {
                  break; // two lines max — keep clear of the row below
               }
               g.text(this.font, kl, left, ky, 0xFF888888);
               ky += 9;
            }
         } else {
            g.text(this.font, Component.literal("§8Enter = find · 1 AI request"), left, 94, 0xFF888888);
         }
         g.text(this.font, Component.literal("§7— or pick it yourself (no AI) —"), left, 110, 0xFFAAAAAA);
         if (!SeedFinderScreen.catalogReady() && SeedFinderScreen.catalogError() == null) {
            g.text(this.font, Component.literal("§8Loading world-gen data… (a few seconds)"), left, 172, 0xFF888888);
         }
         if (SeedFinderScreen.catalogError() != null) {
            g.text(this.font, Component.literal("§c" + SeedFinderScreen.catalogError()), left, 172, 0xFFFF8888);
         }
      }
      for (IconAt icon : icons) {
         g.item(icon.stack(), icon.x(), icon.y());
      }
      if (!feedback.isEmpty()) {
         // Wrap so a long error (e.g. an AI daily-limit message) is fully readable, not cut off
         // at the screen edge. Lines stack upward from just above the bottom buttons.
         java.util.List<net.minecraft.util.FormattedCharSequence> flines = this.font.split(Component.literal(feedback), boxW);
         int fy = this.height - 44 - flines.size() * 10;
         for (net.minecraft.util.FormattedCharSequence fl : flines) {
            g.text(this.font, fl, left, fy, 0xFFDDDDDD);
            fy += 10;
         }
      }
   }

   private static int nextFastDistance() {
      int[] opts = FableVisionConfig.SEED_FAST_DISTANCES;
      for (int i = 0; i < opts.length; i++) {
         if (opts[i] == FableVisionConfig.seedFastDistance) {
            return opts[(i + 1) % opts.length];
         }
      }
      return opts[0];
   }

   private static String shortCount(long n) {
      if (n >= 1_000_000) {
         return String.format("%.1fM", n / 1_000_000.0);
      }
      if (n >= 10_000) {
         return Math.round(n / 1000.0) + "k";
      }
      return String.format("%,d", n);
   }

   private static String trim(String s, int max) {
      return s.length() <= max ? s : s.substring(0, max - 1) + "…";
   }

   /** Shorten a long provider error (especially a quota/limit one) into something that fits
    *  the panel. Other errors pass through and are wrapped by the renderer. */
   private static String friendly(String raw) {
      if (raw == null) {
         return "";
      }
      String low = raw.toLowerCase(Locale.ROOT);
      if (low.contains("429") || low.contains("quota") || low.contains("rate limit")
            || low.contains("rate_limit") || low.contains("resource_exhausted")
            || low.contains("exceeded") || low.contains("insufficient")) {
         return "You've hit today's AI limit (resets at midnight). Pick a structure or biome below — no AI needed.";
      }
      return raw;
   }
}
