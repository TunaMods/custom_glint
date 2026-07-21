package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.level.Level;

/**
 * Shared shape for the "one Glint Trim + 1..8 of a filler item -> the same trim with one modifier set"
 * crafts (speed / redstone, scale / slime, opacity / glass). Subclasses pick the filler and the transform;
 * the trim-plus-count scan and the count bound live here.
 */
public abstract class AbstractTrimAmountRecipe extends CustomRecipe {

    /** The eight 3x3 slots left over once the trim takes one, so eight is the top step of every modifier. */
    protected static final int MAX_FILLER = 8;

    protected AbstractTrimAmountRecipe() {}

    /** The filler item paired with the trim (redstone / slime ball / glass). */
    protected abstract boolean isFiller(ItemStack s);

    /** Applies the count to a copy of the matched trim (already count-1'd), or returns EMPTY if it can't. */
    protected abstract ItemStack apply(ItemStack trimCopy, int count);

    /** Extra match gate beyond "one trim + 1..8 filler"; opacity needs the trim to already have colors. */
    protected boolean trimQualifies(ItemStack trim) { return true; }

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        ItemStack trim = ItemStack.EMPTY;
        int count = 0;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof GlintTrimItem && GlintTrimItem.getPattern(s) != null) {
                if (!trim.isEmpty()) return false;
                trim = s;
            } else if (isFiller(s)) {
                count++;
            } else {
                return false;
            }
        }
        return !trim.isEmpty() && trimQualifies(trim) && count >= 1 && count <= MAX_FILLER;
    }

    @Override
    public ItemStack assemble(CraftingInput pInv) {
        ItemStack trim = ItemStack.EMPTY;
        int count = 0;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof GlintTrimItem) trim = s;
            else if (isFiller(s)) count++;
        }
        if (trim.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = trim.copy();
        result.setCount(1);
        return apply(result, count);
    }

    @Override
    public boolean isSpecial() { return true; }
}
