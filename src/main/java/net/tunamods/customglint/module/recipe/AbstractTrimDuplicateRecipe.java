package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.level.Level;

/**
 * Shared shape for the 3x3 trim-duplicate crafts: a Glint Trim in the center, glowstone dust below it,
 * diamonds in the other seven slots -> two of that trim. The colored and blank variants differ only in
 * whether the center trim must have colors, so subclasses supply that one predicate.
 */
public abstract class AbstractTrimDuplicateRecipe extends CustomRecipe {

    protected AbstractTrimDuplicateRecipe() {}

    /** Whether the center trim's color count qualifies (colored variant wants >0, blank wants 0). */
    protected abstract boolean centerColorsMatch(int colorCount);

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        if (pInv.width() != 3 || pInv.height() != 3) return false;
        for (int i = 0; i < 9; i++) {
            ItemStack s = pInv.getItem(i);
            if (i == 4) {
                if (!(s.getItem() instanceof GlintTrimItem)) return false;
                if (GlintTrimItem.getPattern(s) == null) return false;
                if (!centerColorsMatch(GlintTrimItem.getColors(s).length)) return false;
            } else if (i == 7) {
                if (!s.is(Items.GLOWSTONE_DUST)) return false;
            } else {
                if (!s.is(Items.DIAMOND)) return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack assemble(CraftingInput pInv) {
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
    public boolean isSpecial() { return true; }
}
