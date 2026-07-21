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

/** A colored Glint Trim ringed by Diamonds, with Glowstone Dust below it, copies itself: 3x3 grid in, two
 *  identical trims out. {@link GlintTrimBlankDuplicateRecipe} is the same craft for an uncolored trim. */
public class GlintTrimDuplicateRecipe extends CustomRecipe {
    public static final SimpleCraftingRecipeSerializer<GlintTrimDuplicateRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintTrimDuplicateRecipe::new);

    public GlintTrimDuplicateRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        if (pInv.width() != 3 || pInv.height() != 3) return false;
        for (int i = 0; i < 9; i++) {
            ItemStack s = pInv.getItem(i);
            if (i == 4) {
                if (!(s.getItem() instanceof GlintTrimItem)) return false;
                if (GlintTrimItem.getPattern(s) == null) return false;
                if (GlintTrimItem.getColors(s).length == 0) return false;
            } else if (i == 7) {
                if (!s.is(Items.GLOWSTONE_DUST)) return false;
            } else {
                if (!s.is(Items.DIAMOND)) return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack assemble(CraftingInput pInv, HolderLookup.Provider pRegistryAccess) {
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.getItem() instanceof GlintTrimItem) {
                ItemStack result = s.copy();
                result.setCount(2);
                return result;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider pRegistryAccess) {
        ItemStack result = TrimRecipes.exampleTrim(CustomGlint.WAVE, 0xFFFF0000);
        result.setCount(2);
        return result;
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.withSize(9, Ingredient.EMPTY);
        ItemStack trimExample = TrimRecipes.exampleTrim(CustomGlint.WAVE, 0xFFFF0000);
        for (int i = 0; i < 9; i++) {
            if (i == 4) list.set(i, Ingredient.of(trimExample));
            else if (i == 7) list.set(i, Ingredient.of(Items.GLOWSTONE_DUST));
            else list.set(i, Ingredient.of(Items.DIAMOND));
        }
        return list;
    }

    @Override
    public boolean canCraftInDimensions(int pWidth, int pHeight) {
        // Both dimensions, not the area: matches() needs a real 3x3, so a 9x1 grid can never craft this.
        return pWidth >= 3 && pHeight >= 3;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }
}
