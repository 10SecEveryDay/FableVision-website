package com.tensec.aiscreen;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Captures the current framebuffer as PNG bytes.
 *
 * 26.1.2 notes: Screenshot.takeScreenshot is now callback-based (Consumer&lt;NativeImage&gt;,
 * returns void) and NativeImage no longer exposes asByteArray(). So we write the captured image
 * to a temp PNG and read the bytes back. The callback can fire off the main thread, so callers
 * should hop back via Minecraft.execute() before touching the game.
 */
public class ScreenCapture {

    /** Captures the current frame and hands PNG bytes (or null on failure) to {@code onPng}. */
    public static void capture(Minecraft mc, Consumer<byte[]> onPng) {
        try {
            RenderTarget framebuffer = mc.getMainRenderTarget();
            Screenshot.takeScreenshot(framebuffer, image -> {
                byte[] png = null;
                try (image) {
                    Path tmp = Files.createTempFile("aiscreen-", ".png");
                    image.writeToFile(tmp);
                    png = Files.readAllBytes(tmp);
                    Files.deleteIfExists(tmp);
                } catch (Throwable t) {
                    png = null; // capture failed — caller falls back to a text-only question
                }
                onPng.accept(png);
            });
        } catch (Throwable t) {
            onPng.accept(null);
        }
    }
}
