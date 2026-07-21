package net.tunamods.customglint.module.recipe;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.ModItems;

/** GlintTrimItem (with a pattern) + 1-8 Redstone → the same trim animating at that many times base speed. */
public class GlintTrimSpeedRecipe extends CustomRecipe {
    public static final SimpleCraftingRecipeSerializer<GlintTrimSpeedRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintTrimSpeedRecipe::new);

    public GlintTrimSpeedRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        return TrimRecipes.matchTrimPlusModifier(pInv, Items.REDSTONE) != null;
    }

    @Override
    public ItemStack assemble(CraftingInput pInv, HolderLookup.Provider pRegistryAccess) {
        TrimRecipes.TrimAndCount found = TrimRecipes.scanTrimPlusModifier(pInv, Items.REDSTONE);
        if (found.trim().isEmpty()) return ItemStack.EMPTY;
        ItemStack result = found.trim().copy();
        result.setCount(1);
        GlintTrimItem.setSpeed(result, (float) found.count()); // one redstone per speed multiple
        return result;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider pRegistryAccess) {
        ItemStack result = TrimRecipes.exampleTrim(CustomGlint.WAVE, 0xFFFF0000);
        GlintTrimItem.setSpeed(result, 4.0f);
        return result;
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        list.add(Ingredient.of(TrimRecipes.exampleTrim(CustomGlint.WAVE, 0xFFFF0000)));
        list.add(Ingredient.of(Items.REDSTONE));
        return list;
    }

    @Override
    public boolean canCraftInDimensions(int pWidth, int pHeight) {
        return pWidth * pHeight >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }
}
