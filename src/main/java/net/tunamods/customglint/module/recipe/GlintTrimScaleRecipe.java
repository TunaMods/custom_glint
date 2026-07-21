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

/** GlintTrimItem (with a pattern) + 1-8 Slime Balls → the same trim at 0.5x scale per ball. */
public class GlintTrimScaleRecipe extends CustomRecipe {
    public static final SimpleCraftingRecipeSerializer<GlintTrimScaleRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintTrimScaleRecipe::new);

    public GlintTrimScaleRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        return TrimRecipes.matchTrimPlusModifier(pInv, Items.SLIME_BALL) != null;
    }

    @Override
    public ItemStack assemble(CraftingInput pInv, HolderLookup.Provider pRegistryAccess) {
        TrimRecipes.TrimAndCount found = TrimRecipes.scanTrimPlusModifier(pInv, Items.SLIME_BALL);
        if (found.trim().isEmpty()) return ItemStack.EMPTY;
        ItemStack result = found.trim().copy();
        result.setCount(1);
        GlintTrimItem.setScale(result, found.count() * 0.5f); // 0.5x per slime ball, so 1..8 balls map to 0.5x..4.0x scale
        return result;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider pRegistryAccess) {
        ItemStack result = TrimRecipes.exampleTrim(CustomGlint.WAVE, 0xFFFF0000);
        GlintTrimItem.setScale(result, 2.0f);
        return result;
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        list.add(Ingredient.of(TrimRecipes.exampleTrim(CustomGlint.WAVE, 0xFFFF0000)));
        list.add(Ingredient.of(Items.SLIME_BALL));
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
