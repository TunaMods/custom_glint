package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Glint Trim + Dye -> the same trim recoloured to that single dye colour. */
public class GlintTrimDyeRecipe extends AbstractTrimDyeRecipe {
    private static final GlintTrimDyeRecipe INSTANCE = new GlintTrimDyeRecipe();
    public static final MapCodec<GlintTrimDyeRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, GlintTrimDyeRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<GlintTrimDyeRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public GlintTrimDyeRecipe() {}

    @Override
    protected boolean isTrim(ItemStack s) {
        return s.getItem() instanceof GlintTrimItem && GlintTrimItem.getPattern(s) != null;
    }

    @Override
    protected ItemStack applyDye(ItemStack trimCopy, DyeColor dye) {
        GlintTrimItem.setColors(trimCopy, new int[]{ GlintTrimItem.DYE_COLORS[dye.ordinal()] });
        return trimCopy;
    }

    @Override
    public RecipeSerializer<? extends CustomRecipe> getSerializer() {
        return SERIALIZER;
    }
}
