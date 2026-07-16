package com.fablevision.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * A plain-language guide to everything FableVision does. Opened from the /client menu
 * ("❓ How it works") and pointed to by the first-launch welcome message, so a new user
 * can understand the whole mod in one place. Scroll with the mouse wheel.
 */
public class HelpScreen extends Screen {
   private final Screen parent;
   private int scroll = 0;
   private int maxScroll = 0;

   private static final String[] PARAGRAPHS = {
      "§bFableVision §7— one mod, four tools. It's all client-side, and anything that could affect other players is single-player only.",
      "",
      "§e▶ Vision & quality-of-life §7(flip these in this menu)",
      "§7• §fLow Fire / Low Shield §7— push the fire overlay and the shield out of your view during crystal PvP.",
      "§7• §fFullbright §7— permanent night-vision; set how bright with the Brightness slider.",
      "§7• §fNo Explosions §7— hides crystal/TNT particles so you can see the fight.",
      "§7• §fAppleSkin HUD §7— saturation on the hunger bar, plus hunger/saturation on food tooltips.",
      "§7• §f10s Timer §7— counts down from 10 and auto-leaves a world. Bind one with §f/10sec here§7.",
      "§7• §fAuto LAN + Bedrock §7— opens your world to LAN so friends can join; with Geyser, Bedrock players too.",
      "",
      "§e▶ Ask AI about your screen §7(press §fG§7)",
      "§7Click §fCapture Screen§7, type a question, then press §fEnter §7or §fSend§7. The screenshot goes with §fthat one question §7and then detaches — hit Capture again for the next one. Use §f⚙ Key §7to pick §fGemini§7, §fGPT§7, §fClaude §7or §fGroq §7(free & fast) and paste your key.",
      "§7Your key stays on your PC, travels in a header, and is §fnever shown on screen§7. The counter shows requests used today (Gemini's free tier is about §f21/day§7) and resets at midnight. §fMemory ON §7makes follow-ups remember the chat (uses your limit faster; scroll §f▲▼ §7to reread); §fClear §7wipes it.",
      "",
      "§e▶ Schematic auto-builder §7(§f/build§7, single-player only)",
      "§71. Drop §f.schem / .litematic / .schematic / .nbt §7files into §fconfig/fablevision/schematics/§7.",
      "§72. §f/build load <name> §7(press §fTab §7to list your files) stages it where you stand and shows a ghost of every block. Load as many as you like.",
      "§73. Fill §fone chest §7with the materials, aim at it and §f/build chest§7, then §f/build start§7.",
      "§7Open §f/build §7for the panel: Start / Pause, §fOne-at-a-time vs Together§7, the §fLoaded §7list, and a §fMaterials §7list that turns §agreen §7when you're carrying enough. Every block is paid from that chest (water/lava need a filled bucket). It never breaks your existing blocks and is disabled on servers.",
      "",
      "§e▶ Seed Finder §7(offered when you create a world)",
      "§7Want a §fvillage at spawn§7? A §fpale garden§7? A §fmansion nearby§7? Click §fCreate New World §7and pick §f✨ Custom spawn§7. Either §fdescribe it in your own words §7(your AI key turns words into a search — the AI never picks the seed, it only translates; 1 request) or §fpick a structure + biome yourself, no AI§7.",
      "§7It checks §fthousands of real seeds a second §7with the game's own world-gen code, then types the winning seed into the world settings — press Create and you spawn §fnormally§7, nothing is spawned in. Your first join chats how many seeds were searched and what was found.",
      "§7Two modes (§fneither ever changes your spawn§7): §f🎯 Exact §7puts it §fright where you spawn §7— in view the moment you load in. Want it a certain distance out instead? Use §f⚡ Fast §7and pick §f100/200/400/800 §7blocks (quicker too). Say things like §f\"plains next to a cherry grove\" §7or §f\"pale garden NOT next to a forest\"§7, or ask for a §fbig/huge §7biome.",
      "§8Notes: chest loot and exact terrain can't be searched. Asking the AI for a certain number of §fportal eyes §8releases this week — for now it finds the nearest stronghold.",
      "",
      "§e▶ Every command §7(type in chat)",
      "§7• §f/client §7— open this settings menu.",
      "§7• §f/client on §7/ §foff §7— turn all the vision features on or off at once.",
      "§7• §f/client lan §7— open your world to LAN + Bedrock right now.",
      "§7• §f/10sec §7— switch the timer size (BIG / small).",
      "§7• §f/10sec on §7/ §foff §7— turn the 10-second auto-leave timer on or off.",
      "§7• §f/10sec here §7— bind the timer to §fthis §7world and turn it on.",
      "§7• §f/10sec any §7— let the timer run in every world.",
      "§7• §f/build §7— open the builder panel.",
      "§7• §f/build load <name> §7— stage a schematic where you stand (§fTab §7lists your files).",
      "§7• §f/build unload <name|all> §7— remove a staged build (§fTab §7lists them).",
      "§7• §f/build chest §7— use the chest you're looking at for materials.",
      "§7• §f/build start §7/ §fpause §7/ §fresume §7— control the build.",
      "§7• §f/build status §7— list every loaded build and its progress.",
      "§7• §f/build ghost §7— toggle the ghost-block preview.",
      "§7• §fPress G §7— open the Ask-AI panel."
   };

   public HelpScreen(Screen parent) {
      super(Component.literal("FableVision — How it works"));
      this.parent = parent;
   }

   @Override
   protected void init() {
      addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.minecraft.setScreen(parent))
            .bounds(this.width / 2 - 60, this.height - 28, 120, 20).build());
   }

   private int contentWidth() {
      return Math.min(520, this.width - 60);
   }

   private List<FormattedCharSequence> layout() {
      int w = contentWidth();
      List<FormattedCharSequence> lines = new ArrayList<>();
      for (String p : PARAGRAPHS) {
         if (p.isEmpty()) {
            lines.add(FormattedCharSequence.EMPTY);
         } else {
            lines.addAll(this.font.split(Component.literal(p), w));
         }
      }
      return lines;
   }

   @Override
   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      if (maxScroll > 0) {
         scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(scrollY)));
         return true;
      }
      return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(g, mouseX, mouseY, partialTick);
      g.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);

      List<FormattedCharSequence> lines = layout();
      int top = 38;
      int bottom = this.height - 36;
      int rows = Math.max(1, (bottom - top) / 11);
      maxScroll = Math.max(0, lines.size() - rows);
      if (scroll > maxScroll) scroll = maxScroll;
      if (maxScroll > 0) {
         g.centeredText(this.font, Component.literal("§8(scroll to read more)"), this.width / 2, 26, 0xFF888888);
      }
      int left = (this.width - contentWidth()) / 2;
      int y = top;
      for (int i = scroll; i < lines.size() && i < scroll + rows; i++) {
         g.text(this.font, lines.get(i), left, y, 0xFFFFFFFF);
         y += 11;
      }
   }

   @Override
   public boolean isPauseScreen() {
      return false;
   }
}
