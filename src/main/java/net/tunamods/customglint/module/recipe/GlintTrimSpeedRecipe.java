package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Glint Trim + 1..8 Redstone -> the same trim animating at that many times the base speed. */
public class GlintTrimSpeedRecipe extends AbstractTrimAmountRecipe {
    private static final GlintTrimSpeedRecipe INSTANCE = new GlintTrimSpeedRecipe();
    public static final MapCodec<GlintTrimSpeedRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, GlintTrimSpeedRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<GlintTrimSpeedRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public GlintTrimSpeedRecipe() {}

    @Override
    protected boolean isFiller(ItemStack s) { return s.is(Items.REDSTONE); }

    @Override
    protected ItemStack apply(ItemStack trimCopy, int count) {
        GlintTrimItem.setSpeed(trimCopy, (float) count);
        return trimCopy;
    }

    @Override
    public RecipeSerializer<? extends CustomRecipe> getSerializer() {
        return SERIALIZER;
    }
}
