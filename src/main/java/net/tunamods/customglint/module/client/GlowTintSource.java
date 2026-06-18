package net.tunamods.customglint.module.client;

import com.mojang.serialization.MapCodec;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;

import javax.annotation.Nullable;

/**
 * Animated per-color tint for the Glow Trim / glowing Glint Trim inventory icons — the white part of the
 * trim texture (tintindex 0, layer0 of {@code item/generated}) is recoloured to the item's animated glow
 * colour, cycling through its stored glow colours exactly like the in-world glow ring.
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
        return 0xFF000000 | (CustomGlintRenderer.computeAnimatedGlowColor(colors) & 0xFFFFFF);
    }

    @Override
    public MapCodec<? extends ItemTintSource> type() {
        return MAP_CODEC;
    }

    /** Glow colours for whichever trim this tint is applied to: a Glow Trim's own colours, or a Glint
     *  Trim's colours only while it's glowing (the glow item-model variant — selected at custom_model_data
     *  ≥ 1000 — is the only one carrying this tint, so the {@code isGlowing} check is belt-and-suspenders). */
    private static int[] resolveGlowColors(ItemStack stack) {
        if (stack.getItem() instanceof GlowTrimItem) return GlowTrimItem.getColors(stack);
        if (stack.getItem() instanceof GlintTrimItem) {
            return GlintTrimItem.isGlowing(stack) ? GlintTrimItem.getColors(stack) : EMPTY;
        }
        return EMPTY;
    }
}
