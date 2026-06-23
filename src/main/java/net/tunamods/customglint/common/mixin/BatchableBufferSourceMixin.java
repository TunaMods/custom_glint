package net.tunamods.customglint.common.mixin;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Soft-compat with ImmediatelyFast's {@code enhanced_batching}. IF's {@code BatchableBufferSource.getBuffer}
 * batches every render type whose {@link RenderType#canConsolidateConsecutiveGeometry()} is true (i.e. every
 * {@code QUADS} type, all our glint/glow render types) into a reordered, deferred flush, toggling Iris's
 * vertex-format state per type mid-flush. Against the 26.1 {@code GpuDevice} program cache plus Iris that
 * leaves the bound GL program at 0 for our custom render types, so the sampler-uniform upload in
 * {@code GlCommandEncoder.trySetup} fires {@code GL_INVALID_OPERATION: No active program} once per batched
 * draw, nonstop spam whenever glint/glow is on screen. (Confirmed via the GlDebugProbe stack trace.)
 *
 * <p>The fix is to disable batching for OUR render types only: we redirect IF's
 * {@code canConsolidateConsecutiveGeometry()} check to return {@code false} for any render type we own, so IF
 * routes ours through its direct (sequential, non-batched) path, exactly as if IF weren't installed, while
 * every other mod's render types keep full {@code enhanced_batching}. {@code @Pseudo} + a soft string target
 * with {@code require = 0}, so this no-ops when IF is absent or its internals move.
 */
@Pseudo
@Mixin(targets = "net.raphimc.immediatelyfast.feature.core.BatchableBufferSource", remap = false)
public class BatchableBufferSourceMixin {

    @Redirect(
            method = "getBuffer",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/rendertype/RenderType;canConsolidateConsecutiveGeometry()Z"),
            require = 0)
    private boolean customglint$dontBatchOurRenderTypes(RenderType rt) {
        if (CustomGlintRenderer.isOwnedRenderType(rt)) return false; // → IF's direct path, no program-cache desync
        return rt.canConsolidateConsecutiveGeometry();
    }
}
