package net.tunamods.customglint.common.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drains the batched HUD glint at the end of the hotbar/HUD render.
 *
 * <p>Vanilla {@code GuiGraphics.renderItem} flushes after every icon, so inline glint draws one flush per
 * hotbar item and same-design icons never batch. {@code ItemRendererMixin} instead routes a HUD icon's glint
 * into a private buffer source ({@link CustomGlintRenderer#guiGlintBuffer}) that the per-item flush leaves
 * alone; this drains it once here, so all same-config glint draws in a single batch. It fires while the GUI
 * ortho projection and the icons' committed depth are still live (forGlint EQUAL-depth-tests against them).
 * Empty source (no glinted HUD item, or a screen open → inline path) = instant no-op.
 */
@Mixin(Gui.class)
public class GuiMixin {

    @Inject(method = "render", at = @At("RETURN"), require = 0, remap = false)
    private void cg_drainGuiGlint(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        CustomGlintRenderer.drainGuiGlint();
    }
}
