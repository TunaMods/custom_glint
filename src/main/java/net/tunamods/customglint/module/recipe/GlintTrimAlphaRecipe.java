package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * GlintTrimItem (with pattern + ≥1 color) + 1-8 Glass → same trim with every color's alpha (the A /
 * transparency channel) set by the glass count: 8 glass = fully opaque (A=255), fewer = more
 * see-through. Mirrors {@link GlintTrimSpeedRecipe} / {@link GlintTrimScaleRecipe}.
 */
public class GlintTrimAlphaRecipe extends AbstractTrimAmountRecipe {
    private static final GlintTrimAlphaRecipe INSTANCE = new GlintTrimAlphaRecipe();
    public static final MapCodec<GlintTrimAlphaRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, GlintTrimAlphaRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<GlintTrimAlphaRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public GlintTrimAlphaRecipe() {}

    @Override
    protected boolean isFiller(ItemStack s) { return s.is(Items.GLASS); }

    @Override
    protected boolean trimQualifies(ItemStack trim) { return GlintTrimItem.getColors(trim).length > 0; }

    @Override
    protected ItemStack apply(ItemStack trimCopy, int count) {
        int[] colors = GlintTrimItem.getColors(trimCopy);
        if (colors.length == 0) return ItemStack.EMPTY;
        int alpha = Math.round(count * 255f / 8f);
        int[] out = new int[colors.length];
        for (int i = 0; i < colors.length; i++) out[i] = (alpha << 24) | (colors[i] & 0xFFFFFF);
        GlintTrimItem.setColors(trimCopy, out);
        return trimCopy;
    }

    @Override
    public RecipeSerializer<? extends CustomRecipe> getSerializer() {
        return SERIALIZER;
    }
}
