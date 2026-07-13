package net.tunamods.customglint.module.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Client-only input queries. Kept out of server-reachable item classes so they never hard-reference
 * {@link Minecraft} / {@link InputConstants} in their bytecode. Only reached from client-only screens
 * and tooltip rendering, so this class (and its client imports) never loads on a dedicated server.
 *
 * <p>26.1.2 removed the static {@code Screen.hasShiftDown()}; query the window directly.
 */
public final class ClientInput {
    private ClientInput() {}

    public static boolean hasShiftDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
            || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }
}
