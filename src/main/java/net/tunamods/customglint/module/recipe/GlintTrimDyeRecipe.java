package net.tunamods.customglint.module.recipe;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
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
import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.module.menu.GlintTableMenu;

public class GlintTrimDyeRecipe extends CustomRecipe {
    public static final SimpleCraftingRecipeSerializer<GlintTrimDyeRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintTrimDyeRecipe::new);

    public GlintTrimDyeRecipe(ResourceLocation id, CraftingBookCategory category) {
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
            if (s.getItem() instanceof GlintTrimItem && GlintTrimItem.getPattern(s) != null) {
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
                && GlintTrimItem.getColors(trim).length < 8;
    }

    @Override
    public ItemStack assemble(CraftingContainer pInv, RegistryAccess pRegistryAccess) {
        ItemStack trim = ItemStack.EMPTY;
        DyeItem dye = null;
        for (int i = 0; i < pInv.getContainerSize(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof GlintTrimItem) trim = s;
            else if (s.getItem() instanceof DyeItem d) dye = d;
        }
        if (trim.isEmpty() || dye == null) return ItemStack.EMPTY;
        ItemStack result = trim.copy();
        result.setCount(1);
        // Append the dye color (cap 8), mirroring GlowTrimDyeRecipe. addColor's rewritePreview is a
        // no-op for multi-layer trims, so extra layers / speeds / scroll settings are preserved instead
        // of being collapsed into a single default layer.
        GlintTrimItem.addColor(result, GlintTrimItem.DYE_COLORS[dye.getDyeColor().ordinal()]);
        return result;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess pRegistryAccess) {
        ItemStack result = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(result, CustomGlint.WAVE);
        GlintTrimItem.addColor(result, 0xFFFF0000);
        return result;
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        ItemStack trimExample = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(trimExample, CustomGlint.WAVE);
        GlintTrimItem.addColor(trimExample, 0xFFFF0000);
        list.add(Ingredient.of(trimExample));
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
