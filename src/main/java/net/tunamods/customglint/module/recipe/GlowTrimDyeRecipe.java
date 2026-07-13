package net.tunamods.customglint.module.recipe;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;
import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.module.menu.GlintTableMenu;

/** Glow Trim + Dye → Glow Trim with one more color appended (cap 8). Mirrors GlintTrimDyeRecipe. */
public class GlowTrimDyeRecipe extends CustomRecipe {
    public static final SimpleCraftingRecipeSerializer<GlowTrimDyeRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlowTrimDyeRecipe::new);

    public GlowTrimDyeRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer pInv, Level pLevel) {
        ItemStack trim = ItemStack.EMPTY;
        ItemStack dye  = ItemStack.EMPTY;
        int filled = 0;
        for (int i = 0; i < pInv.getContainerSize(); i++) {
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
                && GlowTrimItem.getColors(trim).length < 8;
    }

    @Override
    public ItemStack assemble(CraftingContainer pInv, RegistryAccess pRegistryAccess) {
        ItemStack trim = ItemStack.EMPTY;
        DyeItem dye = null;
        for (int i = 0; i < pInv.getContainerSize(); i++) {
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
        result.getOrCreateTag().put(GlowTrimItem.COLORS_TAG, new IntArrayTag(next));
        CustomGlint.setGlowColors(result, next);
        return result;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess pRegistryAccess) {
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
        list.add(Ingredient.of(GlintTableMenu.DYE_ITEMS));
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
