package net.tunamods.customglint.module.recipe;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.ModItems;

/**
 * Shared shape for the 3×3 "duplicate a trim" recipes: trim in the center, glowstone dust at slot 7, diamonds
 * everywhere else, yielding two copies of the trim. The colored and blank variants differ only in whether the
 * center trim must already carry colors ({@link #colorsRequired()}).
 */
public abstract class AbstractTrimDuplicateRecipe extends CustomRecipe {
    protected AbstractTrimDuplicateRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    /** Colored variant needs the center trim to have ≥1 color; the blank variant needs it to have none. */
    protected abstract boolean colorsRequired();

    /** Colors to show on the JEI preview trim (empty for the blank variant). */
    protected abstract int[] exampleColors();

    @Override
    public boolean matches(CraftingContainer pInv, Level pLevel) {
        if (pInv.getWidth() != 3 || pInv.getHeight() != 3) return false;
        for (int i = 0; i < 9; i++) {
            ItemStack s = pInv.getItem(i);
            if (i == 4) {
                if (!(s.getItem() instanceof GlintTrimItem)) return false;
                if (GlintTrimItem.getPattern(s) == null) return false;
                if ((GlintTrimItem.getColors(s).length != 0) != colorsRequired()) return false;
            } else if (i == 7) {
                if (!s.is(Items.GLOWSTONE_DUST)) return false;
            } else {
                if (!s.is(Items.DIAMOND)) return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack assemble(CraftingContainer pInv, RegistryAccess pRegistryAccess) {
        for (int i = 0; i < pInv.getContainerSize(); i++) {
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
    public ItemStack getResultItem(RegistryAccess pRegistryAccess) {
        ItemStack result = GlintTrimItem.example(CustomGlint.WAVE, exampleColors());
        result.setCount(2);
        return result;
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.withSize(9, Ingredient.EMPTY);
        Ingredient trim = Ingredient.of(GlintTrimItem.example(CustomGlint.WAVE, exampleColors()));
        for (int i = 0; i < 9; i++) {
            if (i == 4) list.set(i, trim);
            else if (i == 7) list.set(i, Ingredient.of(Items.GLOWSTONE_DUST));
            else list.set(i, Ingredient.of(Items.DIAMOND));
        }
        return list;
    }

    @Override
    public boolean canCraftInDimensions(int pWidth, int pHeight) {
        return pWidth >= 3 && pHeight >= 3;
    }
}
