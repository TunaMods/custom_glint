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
 * margin around them, while RenderSystem still holds the GUI ortho projection / modelview the icon was drawn
 * under (which {@link GlowOutlineRenderer#drainGui()} replays the captured silhouette under). Empty queue =
 * instant no-op, so the many non-item flushes (text, blits) cost nothing.
 *
 * <p>Dual SRG/named @Inject, require=0 on both — in dev both resolve and fire, which is safe: drainGui clears
 * its queue after draining, so the second call is a no-op.
 */
@Mixin(GuiGraphics.class)
public class GuiGraphicsMixin {

    /** SRG target: drains at RETURN of flush() in obfuscated environments. */
    @Inject(method = "m_280262_", at = @At("RETURN"), require = 0)
    private void cg_drainGuiOutline_srg(CallbackInfo ci) {
        GlowOutlineRenderer.drainGui();
    }

    /** Named target: drains at RETURN of flush() in dev/deobf environments. */
    @Inject(method = "flush()V", at = @At("RETURN"), require = 0, remap = false)
    private void cg_drainGuiOutline_named(CallbackInfo ci) {
        GlowOutlineRenderer.drainGui();
    }
}
