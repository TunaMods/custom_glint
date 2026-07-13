package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;

import net.tunamods.customglint.common.client.CustomGlintRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.SortedMap;

/**
 * Captures the live fixedBuffers map from RenderBuffers so forGlint() can insert per-config RenderTypes into it.
 * Shadow must be SortedMap - vanilla declares it that way; Map causes a runtime field-lookup mismatch.
 */
@Mixin(RenderBuffers.class)
public class RenderBuffersMixin {

    @Shadow(aliases = {"f_110093_"}) public SortedMap<RenderType, BufferBuilder> fixedBuffers;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cg_registerGlintBuffer(CallbackInfo ci) {
        CustomGlintRenderer.fixedBufferRegistry = this.fixedBuffers;
    }
}
