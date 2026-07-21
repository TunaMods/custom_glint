package net.tunamods.customglint.module.recipe;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;

/** 3×3 duplicate for a colored trim → two copies. */
public class GlintTrimDuplicateRecipe extends AbstractTrimDuplicateRecipe {
    public static final SimpleCraftingRecipeSerializer<GlintTrimDuplicateRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintTrimDuplicateRecipe::new);

    public GlintTrimDuplicateRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override protected boolean colorsRequired() { return true; }

    @Override protected int[] exampleColors() { return new int[]{0xFFFF0000}; }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }
}
