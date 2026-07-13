package net.tunamods.customglint.module.recipe;

import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public class GlintTrimBlankDuplicateRecipe extends AbstractTrimDuplicateRecipe {
    private static final GlintTrimBlankDuplicateRecipe INSTANCE = new GlintTrimBlankDuplicateRecipe();
    public static final MapCodec<GlintTrimBlankDuplicateRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, GlintTrimBlankDuplicateRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<GlintTrimBlankDuplicateRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public GlintTrimBlankDuplicateRecipe() {}

    @Override
    protected boolean centerColorsMatch(int colorCount) { return colorCount == 0; }

    @Override
    public RecipeSerializer<? extends CustomRecipe> getSerializer() {
        return SERIALIZER;
    }
}
