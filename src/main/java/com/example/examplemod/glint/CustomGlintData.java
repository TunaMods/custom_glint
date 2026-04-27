package com.example.examplemod.glint;

import net.minecraft.resources.ResourceLocation;

/**
 * Immutable data class holding the per-item custom glint configuration.
 *
 * <p>Instances are produced by {@link CustomGlintNbt#read} from the {@code custom_glint}
 * compound tag on an {@link net.minecraft.world.item.ItemStack}. They are consumed by
 * {@code ItemRendererMixin.applyGlint()} each render frame.
 *
 * @param design      ResourceLocation of the design PNG (e.g. {@code examplemod:textures/glint/wave.png}).
 *                    The raw texture is loaded and converted to grayscale by {@link GlintTextureCache};
 *                    color is applied at runtime via {@code RenderSystem.setShaderColor}.
 * @param colors      Array of packed ARGB color ints. The alpha channel is ignored — the shader
 *                    always renders at full opacity. A single-element array skips all animation math.
 *                    Multi-element arrays cycle or lerp over time (see {@code speed}/{@code interpolate}).
 * @param speed       Animation speed multiplier. {@code 1.0} = 20 ticks per color step.
 *                    {@code 2.0} = 10 ticks/color (faster). {@code 0.5} = 40 ticks/color (slower).
 *                    Values ≤ 0 are clamped to {@code 1.0} by {@link CustomGlintNbt#read}.
 * @param interpolate When {@code true}, RGB channels are linearly interpolated between adjacent colors
 *                    using the fractional tick position. When {@code false}, colors hard-switch.
 */
public record CustomGlintData(ResourceLocation design, int[] colors, float speed, boolean interpolate) {}
