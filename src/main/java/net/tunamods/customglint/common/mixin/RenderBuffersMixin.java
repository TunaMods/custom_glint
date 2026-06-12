package net.tunamods.customglint.common.mixin;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;

import net.tunamods.customglint.common.client.CustomGlintRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the live fixed-buffer map so forGlint() can insert per-config RenderTypes into it.
 *
 * In 1.21 the {@code fixedBuffers} map moved off {@code RenderBuffers} (where it used to be a
 * field) onto the {@link MultiBufferSource.BufferSource} the constructor builds. We grab it from
 * {@code bufferSource()} at construction; the field is publicized via the access transformer.
 */
@Mixin(RenderBuffers.class)
public class RenderBuffersMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cg_registerGlintBuffer(CallbackInfo ci) {
        MultiBufferSource.BufferSource bs = ((RenderBuffers) (Object) this).bufferSource();
        CustomGlintRenderer.fixedBufferRegistry = bs.fixedBuffers;
    }
}
