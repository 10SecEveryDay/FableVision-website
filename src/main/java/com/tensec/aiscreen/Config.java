package com.tensec.aiscreen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads/writes config/aiscreen.json — per-provider API keys and models, the active
 * provider, cooldown, and the screen-capture permission preference.
 *
 * There are TWO independent keysets: MAIN (the G-menu "ask AI about my screen" panel)
 * and SPAWN (the Custom-spawn seed wish). Each keeps its own provider, keys, and daily
 * usage counters, so seed wishes never spend the key/quota used for screen questions.
 */
public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Supported AI providers. Each has its own key, model, and endpoint format. */
    public enum Provider {
        GEMINI("Gemini", "aistudio.google.com"),
        OPENAI("GPT (OpenAI)", "platform.openai.com"),
        ANTHROPIC("Claude (Anthropic)", "console.anthropic.com"),
        GROQ("Groq (free & fast)", "console.groq.com");

        public final String label;
        public final String keySite;

        Provider(String label, String keySite) {
            this.label = label;
            this.keySite = keySite;
        }
    }

    /** Which stored keyset a request uses: the G-menu's or the Custom-spawn wish's. */
    public enum Keyset { MAIN, SPAWN }

    public static class Data {
        // Legacy field from 1.x configs — migrated into geminiKey on load, kept so old
        // files parse cleanly.
        public String apiKey = "";

        public String provider = "gemini"; // gemini | openai | anthropic | groq
        public String geminiKey = "";
        public String openaiKey = "";
        public String anthropicKey = "";
        public String groqKey = "";
        public String geminiModel = "gemini-2.5-flash";
        public String openaiModel = "gpt-4o-mini";
        public String anthropicModel = "claude-opus-4-8";
        // Groq's free tier — Llama 4 Scout can see screenshots, so the G-menu works too.
        public String groqModel = "meta-llama/llama-4-scout-17b-16e-instruct";
        public int cooldownSeconds = 10;
        // "ask" = confirm before every screen capture (default); "always" = capture silently.
        public String captureMode = "ask";
        // Remember the conversation (follow-up questions resend the chat = more tokens/requests).
        public boolean rememberConversation = false;

        // The Custom-spawn wish keeps its OWN provider + keys (blank spawnProvider marks a
        // pre-1.30 config — load() migrates by copying the main setup across once).
        public String spawnProvider = "";
        public String spawnGeminiKey = "";
        public String spawnOpenaiKey = "";
        public String spawnAnthropicKey = "";
        public String spawnGroqKey = "";

        // Daily usage counters (reset at local midnight), one set per keyset. Free-tier
        // caps are the provider's and change over time — geminiDailyCap is an editable
        // estimate (the free tier is about 21 requests/day as of mid-2026); GPT/Claude
        // have no free daily tier, so we only show "used today" for them.
        public String usageDate = "";
        public int usedGemini = 0;
        public int usedOpenai = 0;
        public int usedAnthropic = 0;
        public int usedGroq = 0;
        public int usedSpawnGemini = 0;
        public int usedSpawnOpenai = 0;
        public int usedSpawnAnthropic = 0;
        public int usedSpawnGroq = 0;
        public int geminiDailyCap = 21;
    }

    private static Data data = new Data();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("aiscreen.json");
    }

    public static void load() {
        try {
            Path p = file();
            if (Files.exists(p)) {
                Data d = GSON.fromJson(Files.readString(p), Data.class);
                if (d != null) data = d;
                // Migrate a 1.x single-key config: the old apiKey was always a Gemini key.
                if (data.apiKey != null && !data.apiKey.isBlank()
                        && (data.geminiKey == null || data.geminiKey.isBlank())) {
                    data.geminiKey = data.apiKey.trim();
                    data.apiKey = "";
                    save();
                }
                // 1.19.0: the real Gemini free-tier cap turned out to be ~21/day — configs
                // still carrying our old 250 guess move down (a custom value is kept).
                if (data.geminiDailyCap == 250) {
                    data.geminiDailyCap = 21;
                    save();
                }
                // 1.30.0: the Custom-spawn wish got its own keyset. Older configs copy the
                // main setup across ONCE so existing installs keep working; after that the
                // two keysets are fully independent (change either without touching the other).
                if (data.spawnProvider == null || data.spawnProvider.isBlank()) {
                    data.spawnProvider = data.provider == null || data.provider.isBlank() ? "gemini" : data.provider;
                    data.spawnGeminiKey = data.geminiKey;
                    data.spawnOpenaiKey = data.openaiKey;
                    data.spawnAnthropicKey = data.anthropicKey;
                    data.spawnGroqKey = data.groqKey;
                    save();
                }
            } else {
                save(); // create a template file so it's easy to find and edit
            }
            rolloverIfNeeded(); // if it's a new day, zero the counters now — not only when the panel opens
        } catch (Exception e) {
            data = new Data();
        }
    }

    public static void save() {
        try {
            Files.writeString(file(), GSON.toJson(data));
        } catch (Exception ignored) {}
    }

    // ── Provider (per keyset) ───────────────────────────────────────────────
    public static Provider getProvider() { return getProvider(Keyset.MAIN); }

    public static Provider getProvider(Keyset ks) {
        String raw = ks == Keyset.SPAWN ? data.spawnProvider : data.provider;
        String p = raw == null ? "gemini" : raw.toLowerCase();
        if (p.startsWith("openai") || p.startsWith("gpt")) return Provider.OPENAI;
        if (p.startsWith("anthropic") || p.startsWith("claude")) return Provider.ANTHROPIC;
        if (p.startsWith("groq")) return Provider.GROQ;
        return Provider.GEMINI;
    }

    public static void setProvider(Provider p) { setProvider(Keyset.MAIN, p); }

    public static void setProvider(Keyset ks, Provider p) {
        String v = switch (p) {
            case OPENAI -> "openai";
            case ANTHROPIC -> "anthropic";
            case GROQ -> "groq";
            default -> "gemini";
        };
        if (ks == Keyset.SPAWN) {
            data.spawnProvider = v;
        } else {
            data.provider = v;
        }
        save();
    }

    // ── Keys (per keyset + provider; the active provider's key is used) ─────
    public static String getKey() { return getKey(Keyset.MAIN); }

    public static String getKey(Keyset ks) { return getKey(ks, getProvider(ks)); }

    public static String getKey(Provider p) { return getKey(Keyset.MAIN, p); }

    public static String getKey(Keyset ks, Provider p) {
        String k = ks == Keyset.SPAWN
                ? switch (p) {
                    case OPENAI -> data.spawnOpenaiKey;
                    case ANTHROPIC -> data.spawnAnthropicKey;
                    case GROQ -> data.spawnGroqKey;
                    default -> data.spawnGeminiKey;
                }
                : switch (p) {
                    case OPENAI -> data.openaiKey;
                    case ANTHROPIC -> data.anthropicKey;
                    case GROQ -> data.groqKey;
                    default -> data.geminiKey;
                };
        return k == null ? "" : k.trim();
    }

    public static void setKey(String k) { setKey(Keyset.MAIN, k); }

    public static void setKey(Keyset ks, String k) {
        String v = (k == null ? "" : k.trim());
        Provider p = getProvider(ks);
        if (ks == Keyset.SPAWN) {
            switch (p) {
                case OPENAI -> data.spawnOpenaiKey = v;
                case ANTHROPIC -> data.spawnAnthropicKey = v;
                case GROQ -> data.spawnGroqKey = v;
                default -> data.spawnGeminiKey = v;
            }
        } else {
            switch (p) {
                case OPENAI -> data.openaiKey = v;
                case ANTHROPIC -> data.anthropicKey = v;
                case GROQ -> data.groqKey = v;
                default -> data.geminiKey = v;
            }
        }
        save();
    }

    /** One tap of "use my G-menu key here too": copies the MAIN provider and its key into
     *  the spawn keyset. The two stay fully independent afterwards. */
    public static void copyMainKeyToSpawn() {
        Provider p = getProvider(Keyset.MAIN);
        String k = getKey(Keyset.MAIN, p);
        data.spawnProvider = switch (p) {
            case OPENAI -> "openai";
            case ANTHROPIC -> "anthropic";
            case GROQ -> "groq";
            default -> "gemini";
        };
        switch (p) {
            case OPENAI -> data.spawnOpenaiKey = k;
            case ANTHROPIC -> data.spawnAnthropicKey = k;
            case GROQ -> data.spawnGroqKey = k;
            default -> data.spawnGeminiKey = k;
        }
        save();
    }

    // ── Models (shared per provider — both keysets use the same model names) ─
    public static String getModel() { return getModel(Keyset.MAIN); }

    public static String getModel(Keyset ks) {
        return switch (getProvider(ks)) {
            case OPENAI -> orDefault(data.openaiModel, "gpt-4o-mini");
            case ANTHROPIC -> orDefault(data.anthropicModel, "claude-opus-4-8");
            case GROQ -> orDefault(data.groqModel, "meta-llama/llama-4-scout-17b-16e-instruct");
            default -> orDefault(data.geminiModel, "gemini-2.5-flash");
        };
    }

    private static String orDefault(String v, String dflt) {
        return (v == null || v.isBlank()) ? dflt : v.trim();
    }

    // ── Capture permission ──────────────────────────────────────────────────
    public static boolean isCaptureAlwaysAllowed() {
        return "always".equalsIgnoreCase(data.captureMode);
    }

    public static void setCaptureAlwaysAllowed(boolean always) {
        data.captureMode = always ? "always" : "ask";
        save();
    }

    public static int getCooldownSeconds() { return data.cooldownSeconds <= 0 ? 10 : data.cooldownSeconds; }
    public static Path path() { return file(); }

    // ── Conversation memory ─────────────────────────────────────────────────
    public static boolean isRememberConversation() { return data.rememberConversation; }

    public static void setRememberConversation(boolean v) {
        data.rememberConversation = v;
        save();
    }

    // ── Usage counters (per keyset + provider, reset at local midnight) ─────
    private static long lastRolloverProbeMs = 0L;

    /** Tick-driven safety net: re-checks the midnight rollover at most every 30s, so the
     *  counters reset on time even when no AI panel gets opened across midnight. */
    public static void midnightRolloverCheck() {
        long now = System.currentTimeMillis();
        if (now - lastRolloverProbeMs < 30_000L) {
            return;
        }
        lastRolloverProbeMs = now;
        rolloverIfNeeded();
    }

    private static void rolloverIfNeeded() {
        String today = java.time.LocalDate.now().toString(); // YYYY-MM-DD, local
        if (!today.equals(data.usageDate)) {
            data.usageDate = today;
            data.usedGemini = 0;
            data.usedOpenai = 0;
            data.usedAnthropic = 0;
            data.usedGroq = 0;
            data.usedSpawnGemini = 0;
            data.usedSpawnOpenai = 0;
            data.usedSpawnAnthropic = 0;
            data.usedSpawnGroq = 0;
            save();
        }
    }

    /** Call once per AI request actually sent, to advance that keyset's day counter. */
    public static void noteRequest() { noteRequest(Keyset.MAIN); }

    public static void noteRequest(Keyset ks) {
        rolloverIfNeeded();
        Provider p = getProvider(ks);
        if (ks == Keyset.SPAWN) {
            switch (p) {
                case OPENAI -> data.usedSpawnOpenai++;
                case ANTHROPIC -> data.usedSpawnAnthropic++;
                case GROQ -> data.usedSpawnGroq++;
                default -> data.usedSpawnGemini++;
            }
        } else {
            switch (p) {
                case OPENAI -> data.usedOpenai++;
                case ANTHROPIC -> data.usedAnthropic++;
                case GROQ -> data.usedGroq++;
                default -> data.usedGemini++;
            }
        }
        save();
    }

    public static int getUsedToday() { return getUsedToday(Keyset.MAIN); }

    public static int getUsedToday(Keyset ks) {
        rolloverIfNeeded();
        Provider p = getProvider(ks);
        return ks == Keyset.SPAWN
                ? switch (p) {
                    case OPENAI -> data.usedSpawnOpenai;
                    case ANTHROPIC -> data.usedSpawnAnthropic;
                    case GROQ -> data.usedSpawnGroq;
                    default -> data.usedSpawnGemini;
                }
                : switch (p) {
                    case OPENAI -> data.usedOpenai;
                    case ANTHROPIC -> data.usedAnthropic;
                    case GROQ -> data.usedGroq;
                    default -> data.usedGemini;
                };
    }

    /** Free daily cap for that keyset's provider, or -1 if it has no free daily tier. */
    public static int getDailyCap() { return getDailyCap(Keyset.MAIN); }

    public static int getDailyCap(Keyset ks) {
        return getProvider(ks) == Provider.GEMINI ? Math.max(1, data.geminiDailyCap) : -1;
    }

    /** One-line status for the panel, e.g. "Gemini: 12/21 today (9 left)". */
    public static String usageSummary() { return usageSummary(Keyset.MAIN); }

    public static String usageSummary(Keyset ks) {
        int used = getUsedToday(ks);
        int cap = getDailyCap(ks);
        Provider p = getProvider(ks);
        if (cap > 0) {
            int left = Math.max(0, cap - used);
            return p.label + ": " + used + "/" + cap + " today  (" + left + " left · resets at midnight)";
        }
        if (p == Provider.GROQ) {
            return p.label + ": " + used + " used today (generous free tier — resets at midnight)";
        }
        return p.label + ": " + used + " used today (paid tier — resets at midnight)";
    }
}
