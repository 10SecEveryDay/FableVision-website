package com.tensec.aiscreen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Sends a screenshot + question to the selected AI provider (Gemini, OpenAI GPT,
 * Anthropic Claude, or Groq) on a background thread, with a shared cooldown.
 *
 * Security invariants (keep these when editing):
 *  - API keys travel in HTTP HEADERS only, never in URLs, so they can't leak through
 *    exception messages or logs that echo the request URL.
 *  - No logging in this class. Error strings shown to the user never contain the key.
 *  - Exactly four endpoints are ever contacted: generativelanguage.googleapis.com,
 *    api.openai.com, api.anthropic.com, api.groq.com.
 */
public class AiVision {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();
    private static long lastRequest = 0L;

    /** Everything about the mod, sent as the system prompt with G-menu questions so the AI
     *  can answer "how does this mod work?" questions correctly. Text-only, no user data. */
    private static final String MOD_GUIDE = """
            You are the in-game assistant inside FableVision, a free client-side Fabric mod for Minecraft 26.1.2 by 10SecEveryDay (MIT license). Besides normal Minecraft help, you can answer questions about the mod itself using this guide. Keep answers short — they show in a small in-game panel.

            THE MOD'S TOOLS:
            1) Vision & PvP quality-of-life (toggled in the /client menu): Low Fire and Low Shield (push the fire overlay / shield out of view), Fullbright with a brightness slider, No Explosion particles, AppleSkin-style hunger+saturation HUD, a 10-second auto-leave timer, and Auto LAN (opens a singleplayer world to LAN; with Geyser installed, Bedrock players on the same Wi-Fi can join too).
            2) Ask AI about your screen (this panel, opened with G): click Capture Screen, type a question, press Enter/Send. The screenshot rides with that ONE question, then detaches. Every question also carries a short text list of the player's current inventory, so inventory questions ("what should I craft?") work even though a closed inventory isn't on the screenshot. ⚙ Key picks the provider — Gemini, GPT (OpenAI), Claude (Anthropic), or Groq (free & fast) — and stores the key ONLY in config/aiscreen.json on this PC; it is sent only as a request header to that provider and never shown on screen. A daily usage counter resets at midnight. Memory ON makes follow-ups remember the chat (uses the daily limit faster); Clear wipes it.
            3) Schematic auto-builder (/build, singleplayer only): drop .schem / .litematic / .schematic / .nbt files into config/fablevision/schematics/, then /build load <name> stages a ghost preview where you stand. Fill ONE chest with materials, look at it and /build chest, then /build start. The panel has Start/Pause, One-at-a-time vs Together, a Loaded list, and a Materials list that turns green when you carry enough. Every placed block is paid from the chest (water/lava need filled buckets); it never breaks existing blocks. Limits: chest contents/sign text aren't copied, entities aren't placed, modded blocks need their mod.
            4) Seed Finder (the "✨ Custom spawn" button on the Create World screen): describe a wish in your own words (the AI only translates words into search settings — the seed itself is found by checking thousands of REAL seeds per second with the game's own world-gen code) or pick a structure + biome from lists with no AI. The found seed is typed into the world settings; you spawn normally, nothing is spawned in or teleported. Exact mode = the find is right where you spawn (it first aims within ~32 blocks and settles for within 64); Fast mode = near spawn with a chosen distance (100/200/400/800). Neither mode ever changes the spawn point. The wish uses its OWN AI key (⚙ Add AI key on the Custom spawn screen), separate from this panel's key. It can also require BIG/HUGE biomes and "X not next to Y". Chest loot, exact terrain shapes, and mob spawns can't be seed-searched. Asking for a specific number of eyes in the stronghold portal: that search releases THIS WEEK — for now it finds the nearest stronghold.

            EVERY COMMAND: /client (settings menu), /client on|off (all vision features), /client lan (open to LAN+Bedrock now), /10sec (timer size), /10sec on|off, /10sec here (bind timer to this world), /10sec any, /build (builder panel), /build load <name>, /build unload <name|all>, /build chest, /build start|pause|resume, /build status, /build ghost (toggle preview), G key (this AI panel).

            PRIVACY FACTS (true, you can state them): the mod contacts ONLY the AI provider the player picked, and only when they press Send or run a seed wish. The seed finder, builder, and all vision features are fully offline. Nothing is logged or collected.
            """;

    /** onResult is called with the answer text (or an "⚠ …" error message). It runs on a
     *  background thread, so the caller must hop back to the main thread for UI updates.
     *  {@code history} is prior turns as {"user"|"ai", text} pairs (empty = single-shot);
     *  history is text-only — only the CURRENT question can carry a screenshot. */
    public static void ask(byte[] pngImage, String question, List<String[]> history, Consumer<String> onResult) {
        ask(pngImage, question, history, false, onResult);
    }

    /** Same, with {@code withModGuide} true for the G-menu so the AI knows the whole mod and
     *  can answer questions about it. The seed-wish translator passes false — its prompt is
     *  a strict JSON job that must not be diluted. */
    public static void ask(byte[] pngImage, String question, List<String[]> history, boolean withModGuide, Consumer<String> onResult) {
        ask(pngImage, question, history, withModGuide, Config.Keyset.MAIN, onResult);
    }

    /** Same, choosing WHICH stored keyset pays for the request: the G-menu's (MAIN) or the
     *  Custom-spawn wish's (SPAWN) — the two are configured and counted separately. */
    public static void ask(byte[] pngImage, String question, List<String[]> history, boolean withModGuide,
                           Config.Keyset keyset, Consumer<String> onResult) {
        Config.Provider provider = Config.getProvider(keyset);
        String key = Config.getKey(keyset);
        if (key.isEmpty()) {
            onResult.accept(keyset == Config.Keyset.SPAWN
                    ? "No " + provider.label + " key set for Custom spawn — click ⚙ Add AI key (it has its own key, separate from the G-menu)."
                    : "No " + provider.label + " API key set. Click ⚙ Key, pick your provider, and paste your key.");
            return;
        }

        long now = System.currentTimeMillis();
        long waitMs = (lastRequest + Config.getCooldownSeconds() * 1000L) - now;
        if (waitMs > 0) { onResult.accept("Cooldown — wait " + ((waitMs / 1000) + 1) + "s, then ask again."); return; }
        lastRequest = now;
        Config.noteRequest(keyset); // advance that keyset's day counter (this request is going out)

        final String q = (question == null || question.isBlank())
                ? "Briefly describe what's on this Minecraft screen and give a helpful tip."
                : question;
        final String model = Config.getModel(keyset);
        final String b64 = (pngImage != null && pngImage.length > 0)
                ? Base64.getEncoder().encodeToString(pngImage) : null;
        final List<String[]> hist = history == null ? List.of() : List.copyOf(history);
        final String system = withModGuide ? MOD_GUIDE : null;

        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest req = switch (provider) {
                    case OPENAI -> buildOpenAi(key, model, q, b64, hist, system);
                    case ANTHROPIC -> buildAnthropic(key, model, q, b64, hist, system);
                    case GROQ -> buildGroq(key, model, q, b64, hist, system);
                    default -> buildGemini(key, model, q, b64, hist, system);
                };
                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                onResult.accept(switch (provider) {
                    case OPENAI -> parseOpenAiStyle("GPT", res.statusCode(), res.body());
                    case ANTHROPIC -> parseAnthropic(res.statusCode(), res.body());
                    case GROQ -> parseOpenAiStyle("Groq", res.statusCode(), res.body());
                    default -> parseGemini(res.statusCode(), res.body());
                });
            } catch (Exception e) {
                onResult.accept("⚠ " + provider.label + " request failed: " + e.getMessage());
            }
        });
    }

    private static HttpRequest.Builder base(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json");
    }

    // ── Gemini ──────────────────────────────────────────────────────────────
    private static HttpRequest buildGemini(String key, String model, String q, String b64, List<String[]> hist, String system) {
        JsonArray contents = new JsonArray();
        for (String[] turn : hist) { // prior turns, text-only
            JsonObject t = new JsonObject();
            t.addProperty("text", turn[1]);
            JsonArray p = new JsonArray();
            p.add(t);
            JsonObject c = new JsonObject();
            c.addProperty("role", "user".equals(turn[0]) ? "user" : "model");
            c.add("parts", p);
            contents.add(c);
        }
        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", q);
        parts.add(textPart);
        if (b64 != null) {
            JsonObject inline = new JsonObject();
            inline.addProperty("mime_type", "image/png");
            inline.addProperty("data", b64);
            JsonObject imgPart = new JsonObject();
            imgPart.add("inline_data", inline);
            parts.add(imgPart);
        }
        JsonObject content = new JsonObject();
        content.addProperty("role", "user");
        content.add("parts", parts);
        contents.add(content);
        JsonObject body = new JsonObject();
        body.add("contents", contents);
        if (system != null) {
            JsonObject sysText = new JsonObject();
            sysText.addProperty("text", system);
            JsonArray sysParts = new JsonArray();
            sysParts.add(sysText);
            JsonObject sys = new JsonObject();
            sys.add("parts", sysParts);
            body.add("system_instruction", sys);
        }

        return base("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
                .header("x-goog-api-key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    private static String parseGemini(int status, String bodyStr) {
        try {
            JsonObject body = JsonParser.parseString(bodyStr).getAsJsonObject();
            if (status != 200) return "⚠ Gemini " + status + ": " + errorMessage(body, status);
            JsonArray candidates = body.getAsJsonArray("candidates");
            if (candidates == null || candidates.isEmpty()) return "(No answer returned.)";
            JsonArray parts = candidates.get(0).getAsJsonObject().getAsJsonObject("content").getAsJsonArray("parts");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                JsonObject p = parts.get(i).getAsJsonObject();
                if (p.has("text")) sb.append(p.get("text").getAsString());
            }
            return nonEmpty(sb.toString());
        } catch (Exception e) {
            return "⚠ Couldn't read Gemini's response: " + e.getMessage();
        }
    }

    // ── OpenAI (GPT) + Groq (same chat-completions shape, different host) ────
    private static HttpRequest buildOpenAi(String key, String model, String q, String b64, List<String[]> hist, String system) {
        return base("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(chatCompletionsBody(model, q, b64, hist, system)))
                .build();
    }

    /** Groq speaks the OpenAI chat-completions format; only the host differs. The default
     *  model (Llama 4 Scout) understands screenshots, so the whole mod works on it. */
    private static HttpRequest buildGroq(String key, String model, String q, String b64, List<String[]> hist, String system) {
        return base("https://api.groq.com/openai/v1/chat/completions")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(chatCompletionsBody(model, q, b64, hist, system)))
                .build();
    }

    private static String chatCompletionsBody(String model, String q, String b64, List<String[]> hist, String system) {
        JsonArray messages = new JsonArray();
        if (system != null) {
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", system);
            messages.add(sys);
        }
        for (String[] turn : hist) { // prior turns, text-only
            JsonObject m = new JsonObject();
            m.addProperty("role", "user".equals(turn[0]) ? "user" : "assistant");
            m.addProperty("content", turn[1]);
            messages.add(m);
        }
        JsonArray content = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", q);
        content.add(textPart);
        if (b64 != null) {
            JsonObject imageUrl = new JsonObject();
            imageUrl.addProperty("url", "data:image/png;base64," + b64);
            JsonObject imgPart = new JsonObject();
            imgPart.addProperty("type", "image_url");
            imgPart.add("image_url", imageUrl);
            content.add(imgPart);
        }
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.add("content", content);
        messages.add(msg);
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", 1024);
        body.add("messages", messages);
        return body.toString();
    }

    private static String parseOpenAiStyle(String label, int status, String bodyStr) {
        try {
            JsonObject body = JsonParser.parseString(bodyStr).getAsJsonObject();
            if (status != 200) return "⚠ " + label + " " + status + ": " + errorMessage(body, status);
            JsonArray choices = body.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) return "(No answer returned.)";
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            String text = message != null && message.has("content") && !message.get("content").isJsonNull()
                    ? message.get("content").getAsString() : "";
            return nonEmpty(text);
        } catch (Exception e) {
            return "⚠ Couldn't read " + label + "'s response: " + e.getMessage();
        }
    }

    // ── Anthropic (Claude) ──────────────────────────────────────────────────
    private static HttpRequest buildAnthropic(String key, String model, String q, String b64, List<String[]> hist, String system) {
        JsonArray messages = new JsonArray();
        for (String[] turn : hist) { // prior turns, text-only
            JsonObject m = new JsonObject();
            m.addProperty("role", "user".equals(turn[0]) ? "user" : "assistant");
            m.addProperty("content", turn[1]);
            messages.add(m);
        }
        JsonArray content = new JsonArray();
        if (b64 != null) {
            JsonObject source = new JsonObject();
            source.addProperty("type", "base64");
            source.addProperty("media_type", "image/png");
            source.addProperty("data", b64);
            JsonObject imgPart = new JsonObject();
            imgPart.addProperty("type", "image");
            imgPart.add("source", source);
            content.add(imgPart); // image before text, per Anthropic's vision guidance
        }
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", q);
        content.add(textPart);
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.add("content", content);
        messages.add(msg);
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", 1024);
        body.add("messages", messages);
        if (system != null) {
            body.addProperty("system", system);
        }

        return base("https://api.anthropic.com/v1/messages")
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    private static String parseAnthropic(int status, String bodyStr) {
        try {
            JsonObject body = JsonParser.parseString(bodyStr).getAsJsonObject();
            if (status != 200) return "⚠ Claude " + status + ": " + errorMessage(body, status);
            // A refusal is a 200 with stop_reason "refusal" and no usable content.
            if (body.has("stop_reason") && !body.get("stop_reason").isJsonNull()
                    && "refusal".equals(body.get("stop_reason").getAsString())) {
                return "⚠ Claude declined to answer this request.";
            }
            JsonArray content = body.getAsJsonArray("content");
            if (content == null || content.isEmpty()) return "(No answer returned.)";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < content.size(); i++) {
                JsonObject block = content.get(i).getAsJsonObject();
                if (block.has("type") && "text".equals(block.get("type").getAsString()) && block.has("text")) {
                    sb.append(block.get("text").getAsString());
                }
            }
            return nonEmpty(sb.toString());
        } catch (Exception e) {
            return "⚠ Couldn't read Claude's response: " + e.getMessage();
        }
    }

    // ── Shared helpers ──────────────────────────────────────────────────────
    private static String errorMessage(JsonObject body, int status) {
        try {
            if (body.has("error")) {
                JsonObject err = body.getAsJsonObject("error");
                if (err.has("message")) return err.get("message").getAsString();
            }
        } catch (Exception ignored) {}
        return "HTTP " + status;
    }

    private static String nonEmpty(String s) {
        String t = s == null ? "" : s.trim();
        return t.isEmpty() ? "(Empty answer.)" : t;
    }
}
