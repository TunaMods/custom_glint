package net.tunamods.customglint.common.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drains the batched glint for container screens (inventory, creative, chests, the glint table).
 *
 * <p>{@code ItemRendererMixin} routes a container-screen icon's glint into the private batched source
 * ({@link CustomGlintRenderer#guiGlintBuffer}) instead of drawing it inline per item, so the creative tab's
 * ~45 distinct-design icons no longer pay one {@code GuiGraphics.flush()} apiece. This drains it in one batch
 * right before the screen's tooltip, so the glint lands on top of every slot icon (the items are all drawn by
 * then) but still under the hovered tooltip. A RETURN fallback covers the case where the pre-tooltip injection
 * point can't bind, so the glint always drains (worst case it draws over the tooltip rather than vanishing).
 */
@Mixin(AbstractContainerScreen.class)
public class AbstractContainerScreenMixin {

    @Inject(
        method = "render",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;renderTooltip(Lnet/minecraft/client/gui/GuiGraphics;II)V"),
        require = 0, remap = false
    )
    private void cg_drainBeforeTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        CustomGlintRenderer.drainGuiGlint();
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0, remap = false)
    private void cg_drainFallback(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        CustomGlintRenderer.drainGuiGlint();
    }
}
