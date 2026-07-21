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

/**
 * GlintTrimItem (with a pattern + ≥1 color) + 1-8 Glass → the same trim with every color's alpha (the A /
 * transparency channel) set by the glass count: 8 glass = fully opaque (A=255), fewer = more see-through.
 * Mirrors {@link GlintTrimSpeedRecipe} / {@link GlintTrimScaleRecipe}.
 */
public class GlintTrimAlphaRecipe extends CustomRecipe {
    public static final SimpleCraftingRecipeSerializer<GlintTrimAlphaRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintTrimAlphaRecipe::new);

    public GlintTrimAlphaRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        TrimRecipes.TrimAndCount found = TrimRecipes.matchTrimPlusModifier(pInv, Items.GLASS);
        return found != null && GlintTrimItem.getColors(found.trim()).length > 0;
    }

    @Override
    public ItemStack assemble(CraftingInput pInv, HolderLookup.Provider pRegistryAccess) {
        TrimRecipes.TrimAndCount found = TrimRecipes.scanTrimPlusModifier(pInv, Items.GLASS);
        if (found.trim().isEmpty()) return ItemStack.EMPTY;
        int[] colors = GlintTrimItem.getColors(found.trim());
        if (colors.length == 0) return ItemStack.EMPTY;
        // Full glass = opaque, so the count scales the 0..255 alpha channel.
        int alpha = Math.round(found.count() * 255f / TrimRecipes.MAX_MODIFIERS);
        int[] out = new int[colors.length];
        for (int i = 0; i < colors.length; i++) out[i] = (alpha << 24) | (colors[i] & 0xFFFFFF);
        ItemStack result = found.trim().copy();
        result.setCount(1);
        GlintTrimItem.setColors(result, out);
        return result;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider pRegistryAccess) {
        return TrimRecipes.exampleTrim(CustomGlint.WAVE, 0xFFFF0000);
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        list.add(Ingredient.of(TrimRecipes.exampleTrim(CustomGlint.WAVE, 0xFFFF0000)));
        list.add(Ingredient.of(Items.GLASS));
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
