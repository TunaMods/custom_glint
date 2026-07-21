package net.tunamods.customglint.module.recipe;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;

/**
 * Shared shape for the single-property trim modifier recipes: one glint trim plus 1-8 of a modifier item
 * ({@link #modifier()}) → the same trim with one property changed by the count. Scale (slime), speed
 * (redstone), and alpha (glass) each supply only the item and the {@link #apply} step.
 */
public abstract class AbstractTrimModifierRecipe extends CustomRecipe {
    protected static final int MAX_MODIFIERS = 8;

    protected AbstractTrimModifierRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    /** The loose item counted from the grid (slime ball / redstone / glass). */
    protected abstract Item modifier();

    /** Write the property derived from {@code count} (1-8) onto the result trim. */
    protected abstract void apply(ItemStack result, int count);

    /** Alpha needs an existing color to tint; scale/speed don't. */
    protected boolean requiresColors() { return false; }

    /** Extra decoration for the JEI preview result (e.g. a sample scale or speed). */
    protected void decorateResult(ItemStack result) { }

    @Override
    public boolean matches(CraftingContainer pInv, Level pLevel) {
        ItemStack trim = ItemStack.EMPTY;
        int count = 0;
        for (int i = 0; i < pInv.getContainerSize(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof GlintTrimItem && GlintTrimItem.getPattern(s) != null) {
                if (!trim.isEmpty()) return false;
                trim = s;
            } else if (s.is(modifier())) {
                count++;
            } else {
                return false;
            }
        }
        // 8 modifiers max: the trim takes one slot, leaving 8 free in a 3x3 grid.
        if (trim.isEmpty() || count < 1 || count > MAX_MODIFIERS) return false;
        return !requiresColors() || GlintTrimItem.getColors(trim).length > 0;
    }

    @Override
    public ItemStack assemble(CraftingContainer pInv, RegistryAccess pRegistryAccess) {
        ItemStack trim = ItemStack.EMPTY;
        int count = 0;
        for (int i = 0; i < pInv.getContainerSize(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof GlintTrimItem) trim = s;
            else if (s.is(modifier())) count++;
        }
        if (trim.isEmpty()) return ItemStack.EMPTY;
        if (requiresColors() && GlintTrimItem.getColors(trim).length == 0) return ItemStack.EMPTY;
        ItemStack result = trim.copy();
        result.setCount(1);
        apply(result, count);
        return result;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess pRegistryAccess) {
        ItemStack result = GlintTrimItem.example(CustomGlint.WAVE, 0xFFFF0000);
        decorateResult(result);
        return result;
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        list.add(Ingredient.of(GlintTrimItem.example(CustomGlint.WAVE, 0xFFFF0000)));
        list.add(Ingredient.of(modifier()));
        return list;
    }

    @Override
    public boolean canCraftInDimensions(int pWidth, int pHeight) {
        return pWidth * pHeight >= 2;
    }
}
