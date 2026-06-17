package net.tunamods.customglint.module.compat.sophisticatedbackpacks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Standalone-only compat: Sophisticated Backpacks' BEWLR draws its model passes directly through
 * {@code MultiBufferSource.getBuffer(RenderType)}, bypassing the foil hook, so we re-rendered the
 * baked model with our glint render types.
 *
 * <p><b>TODO(26.1) — neutralized to a green-compile no-op.</b> The 1.21.5 item-model rework deleted
 * {@code BlockEntityWithoutLevelRenderer} (BEWLR), {@code ItemRenderer}, {@code BakedModel}, and
 * {@code ItemRenderer.getModel}/{@code renderModelLists}. Items now render through
 * {@code ItemStackRenderState}/{@code ItemModel}/special model renderers, and SB's renderer for 26.1
 * (whatever its new shape) must be re-hooked against that system — the old
 * {@code getModel(...)} + {@code renderModelLists(...)} re-render path is gone. The 1.21.1 logic is in
 * git history (working-1.21.1 branch).
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedbackpacks.client.render.BackpackItemStackRenderer", remap = false)
public class BackpackItemStackRendererMixin {

    @Inject(method = "renderByItem", at = @At("RETURN"), require = 0, remap = false)
    private void cg_apply(CallbackInfo ci) {
        // TODO(26.1): re-render SB backpack glint onto the new ItemStackRenderState/ItemModel system.
    }
}
