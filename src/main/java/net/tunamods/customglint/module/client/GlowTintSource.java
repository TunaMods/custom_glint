package net.tunamods.customglint.module.client;

import com.mojang.serialization.MapCodec;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;

import javax.annotation.Nullable;

/**
 * Animated per-color tint for the Glow Trim / glowing Glint Trim inventory icons, the white outline of the
 * trim texture is recoloured to the item's animated glow colour, cycling through its stored glow colours
 * exactly like the in-world glow ring. The outline is split into its own model layer ({@code layer1},
 * tintindex 1, texture {@code glow_glint_trim_edge}) so only the pure-white edge tints; the grey body
 * ({@code layer0}, tintindex 0, texture {@code glow_glint_trim_body}) carries a constant-white (untinted)
 * tint. A single multiply over the whole sprite would have darkened the grey body toward the glow colour.
 *
 * <p>26.1 replaced the old {@code RegisterColorHandlersEvent.Item} {@code ItemColor} lambda with a
 * data-driven {@link ItemTintSource}: this type is registered under {@code customglint:glow} (see
 * {@link TrimItemColors}) and referenced from the trim item-model JSON via a {@code tints} entry
 * ({@code {"type":"customglint:glow"}}). Stateless, so a single shared instance + {@code MapCodec.unit}.
 */
public final class GlowTintSource implements ItemTintSource {
    public static final GlowTintSource INSTANCE = new GlowTintSource();
    public static final MapCodec<GlowTintSource> MAP_CODEC = MapCodec.unit(INSTANCE);

    private static final int[] EMPTY = new int[0];

    private GlowTintSource() {}

    @Override
    public int calculate(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity owner) {
        int[] colors = resolveGlowColors(stack);
        if (colors.length == 0) return 0xFFFFFFFF; // no glow colours → leave the texture white (untinted)
        // Honour the trim's stored glow-cycle speed + interpolation (set in the Glint Table); default 1.0/true.
        return 0xFF000000 | (CustomGlintRenderer.computeAnimatedGlowColor(colors,
                CustomGlint.getGlowSpeed(stack), CustomGlint.getGlowInterpolate(stack)) & 0xFFFFFF);
    }

    @Override
    public MapCodec<? extends ItemTintSource> type() {
        return MAP_CODEC;
    }

    /** Glow colours for whichever trim this tint is applied to: a Glow Trim's own colours, or a glowing
     *  Glint Trim's glint-data layer 0 colours. Editing another glint layer in the Glint Table won't change
     *  the tint. Falls back to the trim's config colours if it somehow carries no glint data. */
    private static int[] resolveGlowColors(ItemStack stack) {
        // The authoritative glow colours (the glowColors component) win: every build path writes them, the
        // Glow Trim print/recipe AND the Glint Table live preview (CustomGlint.setGlowColors), where the
        // trim's own colour tag isn't set. Fall back to the trim's own storage so a bare trim still tints.
        if (stack.getItem() instanceof GlowTrimItem) {
            int[] explicit = CustomGlint.getGlowColors(stack);
            return explicit.length > 0 ? explicit : GlowTrimItem.getColors(stack);
        }
        if (stack.getItem() instanceof GlintTrimItem) {
            if (!GlintTrimItem.isGlowing(stack)) return EMPTY;
            // Explicit (manual) glow colours win; otherwise the glow is "auto" and follows glint layer 0.
            int[] explicit = CustomGlint.getGlowColors(stack);
            if (explicit.length > 0) return explicit;
            CustomGlint.Data d = CustomGlint.read(stack);
            if (d != null && d.layers().length > 0) return d.layers()[0].colors();
            return GlintTrimItem.getColors(stack);
        }
        return EMPTY;
    }
}
