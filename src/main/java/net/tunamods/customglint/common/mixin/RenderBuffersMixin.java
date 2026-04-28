package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;

import net.tunamods.customglint.common.glint.CustomGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.SortedMap;

/**
 * Exposes the live {@code fixedBuffers} map from {@link RenderBuffers} so that
 * {@link CustomGlintRenderTypes#forGlint} can register new per-config {@link RenderType}s into it
 * at render time.
 *
 * <h3>Why fixedBuffers matters</h3>
 * {@link net.minecraft.client.renderer.MultiBufferSource.BufferSource} has one shared
 * {@link BufferBuilder} for all non-fixed render types. Switching to any other render type while
 * a non-fixed type is active terminates its batch — before any vertices are written to it.
 * Types in {@code fixedBuffers} each have their own dedicated builder and are unaffected by this.
 * Every per-config glint type must therefore be in {@code fixedBuffers}.
 *
 * <h3>Shadow type must be SortedMap</h3>
 * {@code fixedBuffers} is declared as {@code SortedMap} in vanilla. If the shadow is typed as
 * plain {@code Map}, Mixin's field lookup will fail with a type mismatch at runtime.
 */
@Mixin(RenderBuffers.class)
public class RenderBuffersMixin {

    /** Direct access to the vanilla fixed-buffer map so we can insert our render type. */
    @Shadow private SortedMap<RenderType, BufferBuilder> fixedBuffers;

    /**
     * Captures the live {@code fixedBuffers} map reference into
     * {@link CustomGlintRenderTypes#fixedBufferRegistry} immediately after {@link RenderBuffers}
     * finishes constructing its built-in entries.
     *
     * <p>Per-config {@link RenderType} instances are created lazily and inserted into this map
     * by {@link CustomGlintRenderTypes#forGlint} at render time, not here.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void cg_registerGlintBuffer(CallbackInfo ci) {
        CustomGlint.fixedBufferRegistry = this.fixedBuffers;
    }
}
