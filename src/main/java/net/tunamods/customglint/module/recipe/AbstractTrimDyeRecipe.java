package net.tunamods.customglint.module.recipe;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.level.Level;

/**
 * Shared shape for the "one trim + one dye -> the same trim, coloured" crafts. Subclasses pick which trim
 * item they accept and what the dye does to it (the Glint Trim replaces its colour, the Glow Trim appends).
 * The two-slot scan lives here.
 */
public abstract class AbstractTrimDyeRecipe extends CustomRecipe {

    protected AbstractTrimDyeRecipe() {}

    /** Whether this stack is a trim this recipe can dye. */
    protected abstract boolean isTrim(ItemStack s);

    /** Extra match gate beyond "one trim + one dye"; the appending variant stops at the colour cap. */
    protected boolean trimQualifies(ItemStack trim) { return true; }

    /** Writes the dyed colour onto a copy of the matched trim (already count-1'd). */
    protected abstract ItemStack applyDye(ItemStack trimCopy, DyeColor dye);

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        ItemStack trim = ItemStack.EMPTY;
        ItemStack dye  = ItemStack.EMPTY;
        int filled = 0;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            filled++;
            if (isTrim(s)) {
                if (!trim.isEmpty()) return false;
                trim = s;
            } else if (s.getItem() instanceof DyeItem) {
                if (!dye.isEmpty()) return false;
                dye = s;
            } else {
                return false;
            }
        }
        return filled == 2 && !trim.isEmpty() && !dye.isEmpty() && trimQualifies(trim);
    }

    @Override
    public ItemStack assemble(CraftingInput pInv) {
        ItemStack trim = ItemStack.EMPTY;
        ItemStack dyeStack = ItemStack.EMPTY;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (isTrim(s)) trim = s;
            else if (s.getItem() instanceof DyeItem) dyeStack = s;
        }
        DyeColor dyeColor = dyeStack.isEmpty() ? null : dyeStack.get(DataComponents.DYE);
        if (trim.isEmpty() || dyeColor == null) return ItemStack.EMPTY;
        ItemStack result = trim.copy();
        result.setCount(1);
        return applyDye(result, dyeColor);
    }

    @Override
    public boolean isSpecial() { return true; }
}
