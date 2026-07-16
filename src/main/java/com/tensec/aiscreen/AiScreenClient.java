package com.tensec.aiscreen;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class AiScreenClient implements ClientModInitializer {
    public static final String MOD_ID = "aiscreen";
    private static KeyMapping openKey;

    /** Our own Controls section — the binding shows under "FableVision", not "Misc". */
    private static final KeyMapping.Category FABLEVISION_CATEGORY = KeyMapping.Category.register(
        net.minecraft.resources.Identifier.fromNamespaceAndPath("fablevision", "fablevision"));

    // Pending manual capture: the panel that asked, and a tick countdown that lets the
    // game render a couple of frames WITHOUT the panel so the screenshot shows the world.
    private static AiScreenScreen pendingCapture;
    private static int captureDelayTicks = -1;

    @Override
    public void onInitializeClient() {
        Config.load();

        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.aiscreen.open",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            FABLEVISION_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                // Manual-capture design: G only opens the panel. Nothing is captured
                // until the user clicks Capture Screen (with permission).
                client.setScreen(new AiScreenScreen());
            }

            if (captureDelayTicks > 0) {
                captureDelayTicks--;
                if (captureDelayTicks == 0) {
                    captureDelayTicks = -1;
                    AiScreenScreen panel = pendingCapture;
                    pendingCapture = null;
                    if (panel != null) {
                        ScreenCapture.capture(client, png -> client.execute(() -> {
                            panel.onCaptured(png);
                            client.setScreen(panel); // bring the panel back, draft intact
                        }));
                    }
                }
            }
        });
    }

    /** Hides the panel for ~2 ticks, captures the framebuffer, then reopens the panel. */
    public static void requestCapture(AiScreenScreen panel) {
        Minecraft client = Minecraft.getInstance();
        pendingCapture = panel;
        captureDelayTicks = 2;
        client.setScreen(null); // hide the panel so it isn't in the screenshot
    }
}
