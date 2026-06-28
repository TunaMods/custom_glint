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

import net.tunamods.customglint.module.item.GlowTrimItem;
import net.tunamods.customglint.module.item.ModItems;

/** Two or more Glow Trims → single Glow Trim with merged colors (cap 8). Mirrors GlintTrimMergeRecipe. */
public class GlowTrimMergeRecipe extends CustomRecipe {
    public static final SimpleCraftingRecipeSerializer<GlowTrimMergeRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlowTrimMergeRecipe::new);

    public GlowTrimMergeRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        int trimCount = 0;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (!(s.getItem() instanceof GlowTrimItem)) return false;
            if (GlowTrimItem.getColors(s).length == 0) return false;
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
            result = GlowTrimItem.mergeColors(result, s);
        }
        return result;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider pRegistryAccess) {
        ItemStack result = new ItemStack(ModItems.GLOW_TRIM.get());
        GlowTrimItem.addColor(result, 0xFFFF0000);
        GlowTrimItem.addColor(result, 0xFF00AAFF);
        return result;
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        ItemStack trim1 = new ItemStack(ModItems.GLOW_TRIM.get());
        GlowTrimItem.addColor(trim1, 0xFFFF0000);
        ItemStack trim2 = new ItemStack(ModItems.GLOW_TRIM.get());
        GlowTrimItem.addColor(trim2, 0xFF00AAFF);
        list.add(Ingredient.of(trim1));
        list.add(Ingredient.of(trim2));
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
