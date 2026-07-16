package com.tensec.aiscreen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Permission prompt shown before the mod captures the screen (unless the user chose
 * "Always allow" earlier). The capture only happens after an explicit yes.
 */
public class CaptureConsentScreen extends Screen {
    private final AiScreenScreen parent;

    public CaptureConsentScreen(AiScreenScreen parent) {
        super(Component.literal("Allow screen capture?"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int w = Math.min(340, this.width - 40);
        int left = (this.width - w) / 2;
        int y = this.height / 2 + 6;

        addRenderableWidget(Button.builder(Component.literal("Allow once"), b -> {
            this.minecraft.setScreen(parent);
            parent.beginCapture();
        }).bounds(left, y, w, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Always allow (don't ask again)"), b -> {
            Config.setCaptureAlwaysAllowed(true);
            this.minecraft.setScreen(parent);
            parent.beginCapture();
        }).bounds(left, y + 24, w, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.minecraft.setScreen(parent))
                .bounds(left, y + 48, w, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.height / 2 - 58;
        g.centeredText(this.font, this.title, cx, y, 0xFFFFFFFF);
        g.centeredText(this.font, Component.literal("§7The mod wants to take a screenshot of your game to send"), cx, y + 18, 0xFFAAAAAA);
        g.centeredText(this.font, Component.literal("§7to your chosen AI provider when you click Send."), cx, y + 30, 0xFFAAAAAA);
        g.centeredText(this.font, Component.literal("§8The shot can include chat, coordinates and player names."), cx, y + 44, 0xFF888888);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
