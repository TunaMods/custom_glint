package net.tunamods.customglint.module.recipe;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.DyeItem;
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
import net.tunamods.customglint.module.item.GlowTrimItem;
import net.tunamods.customglint.module.item.ModItems;

/** Glow Trim + Dye → Glow Trim with one more color appended (cap 8). Mirrors GlintTrimDyeRecipe. */
public class GlowTrimDyeRecipe extends CustomRecipe {
    public static final SimpleCraftingRecipeSerializer<GlowTrimDyeRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlowTrimDyeRecipe::new);

    public GlowTrimDyeRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        ItemStack trim = ItemStack.EMPTY;
        ItemStack dye  = ItemStack.EMPTY;
        int filled = 0;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            filled++;
            if (s.getItem() instanceof GlowTrimItem) {
                if (!trim.isEmpty()) return false;
                trim = s;
            } else if (s.getItem() instanceof DyeItem) {
                if (!dye.isEmpty()) return false;
                dye = s;
            } else {
                return false;
            }
        }
        return filled == 2 && !trim.isEmpty() && !dye.isEmpty()
                && GlowTrimItem.getColors(trim).length < CustomGlint.MAX_COLORS_PER_LAYER;
    }

    @Override
    public ItemStack assemble(CraftingInput pInv, HolderLookup.Provider pRegistryAccess) {
        ItemStack trim = ItemStack.EMPTY;
        DyeItem dye = null;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof GlowTrimItem) trim = s;
            else if (s.getItem() instanceof DyeItem d) dye = d;
        }
        if (trim.isEmpty() || dye == null) return ItemStack.EMPTY;
        ItemStack result = trim.copy();
        result.setCount(1);
        int[] current = GlowTrimItem.getColors(result);
        int[] next = new int[current.length + 1];
        System.arraycopy(current, 0, next, 0, current.length);
        next[current.length] = GlintTrimItem.DYE_COLORS[dye.getDyeColor().ordinal()];
        GlowTrimItem.setColors(result, next);
        return result;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider pRegistryAccess) {
        ItemStack result = new ItemStack(ModItems.GLOW_TRIM.get());
        GlowTrimItem.addColor(result, 0xFFFF0000);
        return result;
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        list.add(Ingredient.of(new ItemStack(ModItems.GLOW_TRIM.get())));
        list.add(Ingredient.of(
            Items.WHITE_DYE, Items.ORANGE_DYE, Items.MAGENTA_DYE, Items.LIGHT_BLUE_DYE,
            Items.YELLOW_DYE, Items.LIME_DYE, Items.PINK_DYE, Items.GRAY_DYE,
            Items.LIGHT_GRAY_DYE, Items.CYAN_DYE, Items.PURPLE_DYE, Items.BLUE_DYE,
            Items.BROWN_DYE, Items.GREEN_DYE, Items.RED_DYE, Items.BLACK_DYE
        ));
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
