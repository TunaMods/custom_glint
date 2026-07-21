package net.tunamods.customglint.module.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.tunamods.customglint.module.gui.GlintEditorScreen;

/**
 * Client-side trampoline for the Glint Wand. {@code GlintWandItem} runs on both sides, so it can't reference
 * a Screen directly; it calls this class by name from a client-only branch instead.
 */
public final class GlintWandClientHandler {
    private GlintWandClientHandler() {}

    public static void openEditor(InteractionHand hand) {
        Minecraft.getInstance().setScreen(new GlintEditorScreen(hand));
    }
}
