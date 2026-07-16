# FableVision

Ask AI about your screen, auto-build any schematic, and find the exact seed you want — a client-side Fabric mod for Minecraft 26.1.2, by **10SecEveryDay**.

🌐 **Website, downloads & guides:** https://10seceveryday.com
🔑 **Free AI key in 2 minutes (Gemini / Groq):** https://10seceveryday.com/#/keys

## What's inside

- 🤖 **Ask AI about your screen** — press G, ask anything. Sees your screen and inventory. Works with Gemini, GPT, Claude or Groq — always on **your own key**.
- 🏗 **Schematic auto-builder** — `/build` loads `.schem` / `.litematic` / `.schematic` / `.nbt`, shows a textured ghost preview, and builds block-by-block paid from a real chest. Survival-fair; disables itself on servers.
- ✨ **Custom Spawn** — on the Create World screen, describe your dream spawn (or pick from lists) and it searches thousands of real seeds per second with the game's own world-gen. You spawn 100% vanilla.
- ⚔ **PvP vision & QoL** — `/client`: low fire & shield, fullbright, no explosion particles, food HUD, one-click LAN hosting that Bedrock friends can join.

## Building from source

1. Install **JDK 25** (Eclipse Temurin works great).
2. Run `./gradlew build` (Windows: `gradlew.bat build`).
3. The mod jar lands in `build/libs/`.

Requires nothing else — Gradle fetches Fabric Loom and dependencies itself.

## Privacy by design

- **No key lives in this repo or in the jar.** Your AI key is stored only in your local `config/aiscreen.json`, sent only in a request header to the one provider you picked, and never shown on screen or logged.
- The **only network calls in the entire mod** are the four AI provider endpoints in [`AiVision.java`](src/main/java/com/tensec/aiscreen/AiVision.java) — easy to audit. The seed finder, builder, and vision tools are fully offline.
- No telemetry, no accounts, no server of ours.

## License

**MIT** — see [LICENSE](LICENSE). Free to use, read, and modify. Just keep the credit.
