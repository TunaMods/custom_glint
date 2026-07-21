package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Glint Trim + 1..8 Slime Balls -> the same trim with its pattern scale set by the slime count. */
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
        GlintTrimItem.setScale(trimCopy, count * 0.5f); // half a step per slime ball: 2 = the 1.0x default, 8 = 4.0x
        return trimCopy;
    }

    @Override
    public RecipeSerializer<? extends CustomRecipe> getSerializer() {
        return SERIALIZER;
    }
}
