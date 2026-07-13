package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public class GlintTrimScaleRecipe extends AbstractTrimAmountRecipe {
    private static final GlintTrimScaleRecipe INSTANCE = new GlintTrimScaleRecipe();
    public static final MapCodec<GlintTrimScaleRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, GlintTrimScaleRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<GlintTrimScaleRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public GlintTrimScaleRecipe() {}

    @Override
    protected boolean isFiller(ItemStack s) { return s.is(Items.SLIME_BALL); }

    @Override
    protected ItemStack apply(ItemStack trimCopy, int count) {
        GlintTrimItem.setScale(trimCopy, count * 0.5f);
        return trimCopy;
    }

    @Override
    public RecipeSerializer<? extends CustomRecipe> getSerializer() {
        return SERIALIZER;
    }
}
