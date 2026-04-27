package com.example.examplemod.glint;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Defines the single {@link RenderType} used to render all custom glint layers, and exposes the
 * {@link ThreadLocal}s that carry per-item texture/color data into that render type's state setup.
 *
 * <h3>Architecture: why ThreadLocals?</h3>
 * A {@link RenderType} is a static, immutable descriptor — it cannot hold per-frame mutable state.
 * But glint rendering needs to bind a different texture and color for every item. The solution is a
 * "side channel": {@code ItemRendererMixin} writes the current item's texture and color into
 * {@link #CURRENT_TEXTURE} and {@link #CURRENT_COLOR} immediately before calling
 * {@code buffer.getBuffer(FIXED)}, and the custom {@link TextureStateShard} reads those ThreadLocals
 * inside {@link TextureStateShard#setupRenderState()} when the buffer is flushed and drawn.
 *
 * <h3>Important limitation — single shared buffer</h3>
 * Because {@code FIXED} is one {@link RenderType}, all custom-glint items in a frame share one
 * {@link com.mojang.blaze3d.vertex.BufferBuilder} (registered by {@code RenderBuffersMixin}).
 * Their geometry is all batched together. When the buffer is finally flushed, {@code setupRenderState}
 * is called exactly once — reading whichever ThreadLocal values are current at that moment. This means
 * only one texture and one color are active for the entire glint pass. Items rendered later in the
 * frame will overwrite the ThreadLocals set by earlier items. This is an intentional trade-off for
 * simplicity; a per-item render type map would be required to support distinct glints per item.
 *
 * <h3>Extends RenderStateShard</h3>
 * Extending {@link RenderStateShard} is the only way to access the protected static shard constants
 * ({@code RENDERTYPE_GLINT_SHADER}, {@code EQUAL_DEPTH_TEST}, {@code GLINT_TRANSPARENCY}, etc.)
 * that are needed to match vanilla's enchantment glint look. The class is otherwise not used as a
 * shard itself.
 */
public final class CustomGlintRenderTypes extends RenderStateShard {

    /**
     * Set by {@code ItemRendererMixin.applyGlint()} before {@code buffer.getBuffer(FIXED)} is called.
     * Read by {@link #DYNAMIC_TEXTURE_SHARD} in {@code setupRenderState()} at flush time.
     */
    public static final ThreadLocal<ResourceLocation> CURRENT_TEXTURE = new ThreadLocal<>();

    /**
     * float[4] RGBA, set by {@code ItemRendererMixin.applyGlint()} alongside {@link #CURRENT_TEXTURE}.
     * Alpha is always {@code 1.0f}; the array is reused (not reallocated) each frame via a ThreadLocal buffer.
     */
    public static final ThreadLocal<float[]> CURRENT_COLOR = new ThreadLocal<>();

    /**
     * Custom {@link TextureStateShard} that reads the current texture and color from ThreadLocals
     * instead of having a fixed texture baked in at construction time.
     *
     * <p>The dummy ResourceLocation passed to the super constructor is never actually bound;
     * {@code setupRenderState()} unconditionally overwrites the texture binding from {@link #CURRENT_TEXTURE}.
     */
    private static final TextureStateShard DYNAMIC_TEXTURE_SHARD =
        new TextureStateShard(new ResourceLocation("examplemod", "glint/dummy"), false, false) {
            @Override
            public void setupRenderState() {
                // Bind whichever grayscale texture the current item's glint uses.
                ResourceLocation tex = CURRENT_TEXTURE.get();
                if (tex != null) RenderSystem.setShaderTexture(0, tex);
                // Apply the animated color as a shader tint (multiplied against the grayscale texture).
                float[] color = CURRENT_COLOR.get();
                if (color != null) RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            }

            @Override
            public void clearRenderState() {
                // Let the parent clear its texture state, then reset the shader color to white
                // so subsequent render types are not affected by the tint.
                super.clearRenderState();
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            }
        };

    /**
     * The single {@link RenderType} for all custom glint layers.
     *
     * <p>State mirrors vanilla's {@code GLINT} render type exactly, except the texture shard is
     * replaced with {@link #DYNAMIC_TEXTURE_SHARD} so the design and tint can vary at flush time.
     *
     * <ul>
     *   <li>{@code RENDERTYPE_GLINT_SHADER} — the same vertex shader vanilla uses for enchantment glint</li>
     *   <li>{@code EQUAL_DEPTH_TEST} — only renders where the item surface already exists (depth=EQUAL)</li>
     *   <li>{@code GLINT_TRANSPARENCY} — additive blending so the glint brightens without fully covering the item</li>
     *   <li>{@code GLINT_TEXTURING} — UV scrolling matrix applied by the shader to animate the pattern</li>
     *   <li>{@code NO_CULL} — backfaces are rendered so glint appears on all sides</li>
     *   <li>{@code COLOR_WRITE} — writes to color buffer only, not depth</li>
     * </ul>
     *
     * <p>Buffer size 256 matches vanilla glint types. {@code sortOnUpload=false}, {@code affectsCrumbling=false}.
     */
    public static final RenderType FIXED = RenderType.create(
        "examplemod:custom_glint",
        DefaultVertexFormat.POSITION_TEX,
        VertexFormat.Mode.QUADS,
        256,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(RENDERTYPE_GLINT_SHADER)
            .setTextureState(DYNAMIC_TEXTURE_SHARD)
            .setWriteMaskState(COLOR_WRITE)
            .setCullState(NO_CULL)
            .setDepthTestState(EQUAL_DEPTH_TEST)
            .setTransparencyState(GLINT_TRANSPARENCY)
            .setTexturingState(GLINT_TEXTURING)
            .createCompositeState(false)
    );

    /** Not instantiated — exists only to inherit protected {@link RenderStateShard} shard constants. */
    private CustomGlintRenderTypes() {
        super("", () -> {}, () -> {});
    }
}
