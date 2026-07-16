package com.tensec.aiscreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The "ask AI" panel. Opened with G; ESC closes it (default Screen behaviour).
 *
 * Flow: click "Capture Screen" (permission prompt unless Always-allowed) → the panel
 * hides for a moment so the screenshot shows the world, not this panel → type a
 * question → Send. Nothing is captured or sent without those explicit clicks.
 *
 * The answer/conversation state is STATIC so closing and reopening the panel never
 * loses anything: a request in flight still shows "Thinking…", the last answer is
 * still there, and (with Memory on) the whole conversation scrolls in a fixed-height
 * area — the page never grows past the input row.
 */
public class AiScreenScreen extends Screen {
    // ── Shared conversation state (survives closing/reopening the panel) ────
    private static final List<String[]> HISTORY = new ArrayList<>(); // {"user"|"ai", text}
    private static final int MAX_TURNS = 24; // keep the last 12 exchanges (bounds tokens too)
    private static final String INTRO =
            "Click Capture Screen to attach a screenshot, then type a question and hit Send.\nI also get a list of what's in your inventory, so ask about that too.\nUse ⚙ Key to pick your AI provider (Gemini / GPT / Claude / Groq) and paste your key.";
    private static String answer = INTRO;
    private static boolean loading = false;
    private static long thinkingSince = 0L;
    private static String draft = ""; // typed-but-unsent text survives reopening too
    private static int chatScroll = 0;
    private static boolean stickBottom = true; // follow the newest message until the user scrolls up
    private static int chatMaxScroll = 0;

    // ── Per-open state ───────────────────────────────────────────────────────
    private byte[] screenshot; // set by Capture Screen; null = text-only question
    private EditBox input;
    private Button captureButton;
    private boolean capturing = false;

    public AiScreenScreen() {
        super(Component.literal("Ask AI About My Screen"));
    }

    @Override
    protected void init() {
        int boxW = Math.min(440, this.width - 40);
        int left = (this.width - boxW) / 2;
        int inputY = this.height - 56;

        input = new EditBox(this.font, left, inputY, boxW - 80, 20, Component.literal("question"));
        input.setMaxLength(500);
        input.setValue(draft);
        addRenderableWidget(input);
        setInitialFocus(input);

        addRenderableWidget(Button.builder(Component.literal("Send"), b -> send())
                .bounds(left + boxW - 74, inputY - 1, 74, 22).build());

        addRenderableWidget(Button.builder(Component.literal("⚙ Key"), b -> {
            stashDraft();
            this.minecraft.setScreen(new ConfigScreen(this));
        }).bounds(left, inputY - 30, 70, 20).build());

        captureButton = Button.builder(Component.literal(screenshot == null ? "Capture Screen" : "Recapture"), b -> onCaptureClicked())
                .bounds(left + 76, inputY - 30, 110, 20).build();
        addRenderableWidget(captureButton);

        addRenderableWidget(Button.builder(Component.literal("Clear"), b -> {
            HISTORY.clear();
            answer = "";
            chatScroll = 0;
            stickBottom = true;
        }).bounds(left + 192, inputY - 30, 56, 20).build());

        // Conversation memory toggle — follow-ups resend the chat, so it costs more usage.
        addRenderableWidget(Button.builder(memoryLabel(), b -> {
            boolean on = !Config.isRememberConversation();
            Config.setRememberConversation(on);
            if (!on) HISTORY.clear();
            stickBottom = true;
            b.setMessage(memoryLabel());
        }).bounds(left + 254, inputY - 30, boxW - 254, 20).build());
    }

    private static Component memoryLabel() {
        return Component.literal("Memory: " + (Config.isRememberConversation() ? "§aON" : "§cOFF"));
    }

    private void stashDraft() {
        if (input != null) draft = input.getValue();
    }

    private void onCaptureClicked() {
        if (capturing || loading) return;
        stashDraft();
        if (Config.isCaptureAlwaysAllowed()) {
            beginCapture();
        } else {
            this.minecraft.setScreen(new CaptureConsentScreen(this));
        }
    }

    /** Starts the actual capture (after permission). The panel hides for a couple of
     *  ticks so the screenshot shows the world, then reopens with the draft intact. */
    public void beginCapture() {
        if (capturing) return;
        stashDraft();
        capturing = true;
        AiScreenClient.requestCapture(this);
    }

    /** Called on the main thread once the capture finishes (png may be null on failure). */
    public void onCaptured(byte[] png) {
        capturing = false;
        this.screenshot = png;
        if (captureButton != null) {
            captureButton.setMessage(Component.literal(png == null ? "Capture Screen" : "Recapture"));
        }
    }

    private void send() {
        if (loading || capturing) return;
        String q = input.getValue().trim();
        // Nothing typed AND nothing captured — don't burn a request on an accidental Enter.
        if (q.isEmpty() && screenshot == null) {
            answer = "Type a question first — or click Capture Screen and I'll describe what's on it.";
            return;
        }
        input.setValue("");
        draft = "";
        loading = true;
        thinkingSince = System.currentTimeMillis();
        stickBottom = true;
        final boolean remember = Config.isRememberConversation();
        final List<String[]> hist = remember ? List.copyOf(HISTORY) : List.of();
        final String asked = q.isEmpty() ? "(describe my screenshot)" : q;
        final Minecraft mc = this.minecraft;
        // withModGuide: the AI gets the built-in FableVision guide, so questions about the
        // mod itself ("how do I use /build?") get real answers, not guesses.
        // The screenshot can't show a CLOSED inventory, so a short text list of what the
        // player is carrying rides along with every question — inventory questions just work.
        final String inv = inventoryContext(mc);
        final String outQ = (q.isEmpty() ? "Briefly describe what's on this Minecraft screen and give a helpful tip." : q)
                + (inv.isEmpty() ? "" : "\n\n" + inv);
        AiVision.ask(screenshot, outQ, hist, true, reply -> mc.execute(() -> {
            loading = false;
            answer = reply == null ? "" : reply;
            boolean ok = reply != null && !reply.isEmpty() && !reply.startsWith("⚠");
            if (remember && ok) {
                HISTORY.add(new String[]{"user", asked});
                HISTORY.add(new String[]{"ai", reply});
                while (HISTORY.size() > MAX_TURNS) HISTORY.remove(0);
            }
            stickBottom = true;
            // Also drop it into the in-game chat so it stays after you close this panel.
            if (mc.player != null && ok) {
                mc.player.sendSystemMessage(Component.literal("§b[AI]§r " + reply));
            }
        }));
        // The screenshot rides along with THIS question only — detach it so follow-ups
        // are text-only until you Capture again (no silent re-sending, no wasted quota).
        screenshot = null;
        if (captureButton != null) {
            captureButton.setMessage(Component.literal("Capture Screen"));
        }
    }

    /** A short text list of what the player is carrying — hotbar/main slots plus worn
     *  armor and offhand (deduped by stack identity in case the container covers them
     *  already). Sent with the question, never shown in the chat area. */
    private static String inventoryContext(Minecraft mc) {
        if (mc == null || mc.player == null) return "";
        try {
            java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            java.util.Set<net.minecraft.world.item.ItemStack> seen =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            var inv = mc.player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && seen.add(s)) {
                    counts.merge(s.getHoverName().getString(), s.getCount(), Integer::sum);
                }
            }
            for (net.minecraft.world.entity.EquipmentSlot es : new net.minecraft.world.entity.EquipmentSlot[]{
                    net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                    net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET,
                    net.minecraft.world.entity.EquipmentSlot.OFFHAND}) {
                net.minecraft.world.item.ItemStack s = mc.player.getItemBySlot(es);
                if (!s.isEmpty() && seen.add(s)) {
                    counts.merge(s.getHoverName().getString() + " (worn)", s.getCount(), Integer::sum);
                }
            }
            if (counts.isEmpty()) {
                return "[Context: the player's inventory is currently empty.]";
            }
            StringBuilder sb = new StringBuilder("[Context — the player's inventory right now: ");
            boolean first = true;
            for (var e : counts.entrySet()) {
                String part = (e.getValue() > 1 ? e.getValue() + "× " : "") + e.getKey();
                if (sb.length() + part.length() > 900) {
                    sb.append(", …");
                    break;
                }
                if (!first) sb.append(", ");
                sb.append(part);
                first = false;
            }
            return sb.append(".]").toString();
        } catch (Throwable t) {
            return ""; // never let context building break the ask
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Press Enter to send (so you don't have to reach for the Send button).
        if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) && !loading && !capturing) {
            send();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (chatMaxScroll > 0) {
            chatScroll = Math.max(0, Math.min(chatMaxScroll, chatScroll - (int) Math.signum(scrollY) * 3));
            stickBottom = chatScroll >= chatMaxScroll; // scrolled back down → follow new messages again
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** The lines shown in the chat area: the conversation (Memory on) or just the latest
     *  answer, plus any error and the animated Thinking line while a request is out. */
    private List<FormattedCharSequence> chatLines(int boxW) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        boolean remember = Config.isRememberConversation();
        if (remember) {
            for (String[] t : HISTORY) {
                String head = "user".equals(t[0]) ? "§b➤ You: §f" : "§a➤ AI: §f";
                lines.addAll(this.font.split(Component.literal(head + t[1]), boxW));
                lines.add(FormattedCharSequence.EMPTY);
            }
            boolean isError = answer != null && answer.startsWith("⚠");
            if (HISTORY.isEmpty() && !loading && answer != null && !answer.isEmpty() && !isError) {
                lines.addAll(this.font.split(Component.literal(answer), boxW));
            } else if (isError) {
                lines.addAll(this.font.split(Component.literal("§c" + answer), boxW));
                lines.addAll(this.font.split(Component.literal("§7Check your key/provider with ⚙ Key, then try again."), boxW));
            }
        } else if (answer != null && !answer.isEmpty()) {
            boolean isError = answer.startsWith("⚠");
            lines.addAll(this.font.split(Component.literal(isError ? "§c" + answer : answer), boxW));
            if (isError) {
                lines.addAll(this.font.split(Component.literal("§7Check your key/provider with ⚙ Key, then try again."), boxW));
            }
        }
        if (loading) {
            int dots = (int) ((System.currentTimeMillis() / 400) % 4);
            long seconds = (System.currentTimeMillis() - thinkingSince) / 1000;
            lines.add(FormattedCharSequence.EMPTY);
            lines.addAll(this.font.split(Component.literal(
                    "§bThinking" + ".".repeat(dots) + "   (" + seconds + "s) §8— asking " + Config.getProvider().label), boxW));
        }
        return lines;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // super draws the dimmed background + our widgets (input box, buttons).
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        g.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);

        // Capture status — always visible so you know exactly what will be sent.
        String status;
        int statusColor;
        if (capturing) {
            status = "Capturing screen…";
            statusColor = 0xFFFFFF55;
        } else if (screenshot != null) {
            status = "Screenshot attached — Send will include it (" + Config.getProvider().label + ")";
            statusColor = 0xFF7CFC7C;
        } else {
            status = "No screenshot attached — questions are text-only (" + Config.getProvider().label + ")";
            statusColor = 0xFFAAAAAA;
        }
        g.centeredText(this.font, Component.literal(status), this.width / 2, 30, statusColor);

        // Usage counter — GEMINI ONLY. It's the one provider with a known free daily cap
        // worth tracking; GPT/Claude/Groq report their own limits in their error messages.
        if (Config.getProvider() == Config.Provider.GEMINI) {
            int used = Config.getUsedToday();
            int cap = Config.getDailyCap();
            boolean low = cap > 0 && (cap - used) <= 5;
            g.centeredText(this.font, Component.literal("§8" + Config.usageSummary()),
                    this.width / 2, 41, low ? 0xFFFF9955 : 0xFF888888);
        }

        // ── Fixed-height, scrollable chat area (never grows past the input row) ──
        int boxW = Math.min(460, this.width - 40);
        int left = (this.width - boxW) / 2;
        int top = 56;
        int bottom = (this.height - 56) - 44; // stays clear of the ⚙/Capture/Clear/Memory row
        int rows = Math.max(1, (bottom - top) / 12);

        List<FormattedCharSequence> lines = chatLines(boxW);
        chatMaxScroll = Math.max(0, lines.size() - rows);
        if (stickBottom) chatScroll = chatMaxScroll;
        if (chatScroll > chatMaxScroll) chatScroll = chatMaxScroll;

        int y = top;
        for (int i = chatScroll; i < lines.size() && i < chatScroll + rows; i++) {
            g.text(this.font, lines.get(i), left, y, 0xFFE8E8E8);
            y += 12;
        }

        // Bottom hint line: memory warning and/or scroll hint.
        String hint = "";
        if (Config.isRememberConversation()) {
            hint = "§6Memory ON §7— follow-ups resend the chat, so it uses your daily limit faster.";
        }
        if (chatMaxScroll > 0) {
            hint += (hint.isEmpty() ? "" : "  ") + "§8(scroll ▲▼ to read older messages)";
        }
        if (!hint.isEmpty()) {
            g.text(this.font, Component.literal(hint), left, bottom + 4, 0xFFAAAAAA);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
