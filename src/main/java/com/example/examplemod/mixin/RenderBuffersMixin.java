package com.example.examplemod.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import com.example.examplemod.glint.CustomGlintRenderTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.SortedMap;

/**
 * Registers {@link CustomGlintRenderTypes#FIXED} as a "fixed buffer" inside {@link RenderBuffers}.
 *
 * <h3>Why this is needed</h3>
 * {@link RenderBuffers} maintains two categories of {@link BufferBuilder}s:
 * <ul>
 *   <li><b>fixedBuffers</b> — pre-allocated builders for render types that are used every frame
 *       (translucent terrain, glint, etc.). They persist across draw calls and are flushed in a
 *       defined order at the end of the frame.</li>
 *   <li><b>Dynamic builders</b> — created on-demand for everything else.</li>
 * </ul>
 * Vanilla's enchantment glint types ({@code GLINT}, {@code ENTITY_GLINT_DIRECT}, etc.) are all in
 * {@code fixedBuffers}. When {@code ItemRendererMixin} calls {@code buffer.getBuffer(FIXED)}, the
 * {@link net.minecraft.client.renderer.MultiBufferSource.BufferSource} looks up {@code FIXED} in
 * {@code fixedBuffers}. Without this mixin it would not find it and would create a new (unflushed)
 * dynamic builder instead — causing the glint to never actually render.
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
     * Inserts {@link CustomGlintRenderTypes#FIXED} into the fixed-buffer map immediately after
     * {@link RenderBuffers} finishes constructing its built-in entries.
     *
     * <p>The buffer size is taken from {@code FIXED.bufferSize()} (256), matching vanilla glint types.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void cg_registerGlintBuffer(CallbackInfo ci) {
        this.fixedBuffers.put(CustomGlintRenderTypes.FIXED, new BufferBuilder(CustomGlintRenderTypes.FIXED.bufferSize()));
    }
}
