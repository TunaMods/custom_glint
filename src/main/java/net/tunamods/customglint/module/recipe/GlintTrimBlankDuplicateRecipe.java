package net.tunamods.customglint.module.recipe;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;

/** 3×3 duplicate for a blank (colorless) trim → two copies. */
public class GlintTrimBlankDuplicateRecipe extends AbstractTrimDuplicateRecipe {
    public static final SimpleCraftingRecipeSerializer<GlintTrimBlankDuplicateRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintTrimBlankDuplicateRecipe::new);

    public GlintTrimBlankDuplicateRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override protected boolean colorsRequired() { return false; }

    @Override protected int[] exampleColors() { return new int[0]; }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }
}
