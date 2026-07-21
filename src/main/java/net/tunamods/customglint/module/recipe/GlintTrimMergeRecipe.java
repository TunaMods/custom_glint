package net.tunamods.customglint.module.recipe;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
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

/** Two or more colored Glint Trims → one trim carrying their merged colors (cap 8). Mirrored by
 *  GlowTrimMergeRecipe. */
public class GlintTrimMergeRecipe extends CustomRecipe {
    public static final SimpleCraftingRecipeSerializer<GlintTrimMergeRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintTrimMergeRecipe::new);

    public GlintTrimMergeRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        int trimCount = 0;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (!(s.getItem() instanceof GlintTrimItem)) return false;
            if (GlintTrimItem.getPattern(s) == null) return false;
            if (GlintTrimItem.getColors(s).length == 0) return false;
            trimCount++;
        }
        return trimCount >= 2;
    }

    @Override
    public ItemStack assemble(CraftingInput pInv, HolderLookup.Provider pRegistryAccess) {
        ItemStack result = ItemStack.EMPTY;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (result.isEmpty()) { result = s.copy(); continue; }
            result = GlintTrimItem.mergeColors(result, s);
        }
        return result;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider pRegistryAccess) {
        return TrimRecipes.exampleTrim(CustomGlint.WAVE, 0xFFFF0000, 0xFF00AAFF);
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        list.add(Ingredient.of(TrimRecipes.exampleTrim(CustomGlint.WAVE, 0xFFFF0000)));
        list.add(Ingredient.of(TrimRecipes.exampleTrim(CustomGlint.SPARKLE, 0xFF00AAFF)));
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
