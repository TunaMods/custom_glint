package net.tunamods.customglint.module.recipe;

import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public class GlintTrimDuplicateRecipe extends AbstractTrimDuplicateRecipe {
    private static final GlintTrimDuplicateRecipe INSTANCE = new GlintTrimDuplicateRecipe();
    public static final MapCodec<GlintTrimDuplicateRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, GlintTrimDuplicateRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<GlintTrimDuplicateRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public GlintTrimDuplicateRecipe() {}

    @Override
    protected boolean centerColorsMatch(int colorCount) { return colorCount != 0; }

    @Override
    public RecipeSerializer<? extends CustomRecipe> getSerializer() {
        return SERIALIZER;
    }
}
