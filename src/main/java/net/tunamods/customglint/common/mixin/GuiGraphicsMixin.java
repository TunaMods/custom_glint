package net.tunamods.customglint.common.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drains queued GUI / inventory / HUD glow-outline rings at the RETURN of {@link GuiGraphics#flush()}.
 *
 * <p>{@code ItemRendererMixin} captures a glowing GUI icon's silhouette (in GUI screen space) at the
 * {@code ItemRenderer.render} RETURN and queues it. {@code GuiGraphics.renderItem} flushes its batch
 * immediately after rendering each icon, so hooking that flush is where the icon (and its already-buffered
 * slot background) have just been drawn to the main target — the ring then composites cleanly into the
 * margin around them. RenderSystem still holds the GUI ortho projection / modelview at this point, which is
 * exactly what {@link GlowOutlineRenderer#drainGui()} replays the captured silhouette under. Empty queue =
 * instant no-op, so the many non-item flushes (text, blits) cost nothing.
 */
@Mixin(GuiGraphics.class)
public class GuiGraphicsMixin {

    @Inject(method = "flush", at = @At("RETURN"), require = 0, remap = false)
    private void cg_drainGuiOutline(CallbackInfo ci) {
        GlowOutlineRenderer.drainGui();
        // Chromatic GUI icons can't draw in-phase under a shader pack; drain their post-Iris overlay here,
        // while the GUI ortho matrices are still live. No-op off-pack / when nothing was captured.
        GlowOutlineRenderer.drainChromaticGui();
    }
}
