package com.example.examplemod.glint;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;


/**
 * Manages per-glint-config {@link RenderType} instances for custom animated glint rendering.
 *
 * <h3>Architecture: per-config RenderTypes</h3>
 * Each unique glint configuration (design + colors + speed + interpolate) gets its own
 * {@link RenderType} instance, created lazily by {@link #forGlint} and cached in {@link #BY_GLINT}.
 * Each instance has its own dedicated {@link com.mojang.blaze3d.vertex.BufferBuilder} registered
 * into {@link net.minecraft.client.renderer.RenderBuffers}'s {@code fixedBuffers} map (via
 * {@link #fixedBufferRegistry}), so items with different glints write into separate buffers.
 * At flush time each buffer's {@code setupRenderState()} fires independently, reading the correct
 * texture and color for that specific config from a closed-over {@code float[]} holder.
 *
 * <h3>Color holder, not ThreadLocal</h3>
 * Each cached {@link RenderType}'s {@link TextureStateShard} closes over a {@code float[4]} from
 * {@link #GLINT_COLORS} keyed on the same config string. {@link #forGlint} updates that array before
 * returning, so by flush time it holds the correct animated color for that config's buffer.
 * Items sharing the same config produce the same animated color in the same frame, so there is no
 * conflict when multiple items share one buffer.
 *
 * <h3>Extends RenderStateShard</h3>
 * Extending {@link RenderStateShard} is the only way to access the protected static shard constants
 * ({@code RENDERTYPE_GLINT_SHADER}, {@code EQUAL_DEPTH_TEST}, {@code GLINT_TRANSPARENCY}, etc.)
 * that are needed to match vanilla's enchantment glint look. The class is otherwise not used as a
 * shard itself.
 */
public final class CustomGlintRenderTypes extends RenderStateShard {

    public static SortedMap<RenderType, BufferBuilder> fixedBufferRegistry;

    private static final Map<String, float[]> GLINT_COLORS = new HashMap<>();
    private static final Map<String, RenderType> BY_GLINT = new HashMap<>();

    public static RenderType forGlint(CustomGlintData glint, float[] frameColor) {
        String key = glint.design() + "|" + Arrays.toString(glint.colors()) + "|" + glint.speed() + "|" + glint.interpolate();
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        return BY_GLINT.computeIfAbsent(key, k -> {
            ResourceLocation tex = glint.design();
            RenderType rt = RenderType.create(
                "examplemod:custom_glint",
                DefaultVertexFormat.POSITION_TEX,
                VertexFormat.Mode.QUADS,
                256,
                false,
                false,
                RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_GLINT_SHADER)
                    .setTextureState(new TextureStateShard(tex, false, false) {
                        @Override public void setupRenderState() {
                            RenderSystem.setShaderTexture(0, GlintTextureCache.get(tex));
                            RenderSystem.setShaderColor(holder[0], holder[1], holder[2], holder[3]);
                        }
                        @Override public void clearRenderState() {
                            super.clearRenderState();
                            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                        }
                    })
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(EQUAL_DEPTH_TEST)
                    .setTransparencyState(GLINT_TRANSPARENCY)
                    .setTexturingState(GLINT_TEXTURING)
                    .createCompositeState(false));
            if (fixedBufferRegistry != null) {
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            }
            return rt;
        });
    }

    /** Not instantiated — exists only to inherit protected {@link RenderStateShard} shard constants. */
    private CustomGlintRenderTypes() {
        super("", () -> {}, () -> {});
    }
}
