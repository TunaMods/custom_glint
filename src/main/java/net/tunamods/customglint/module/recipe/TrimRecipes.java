package net.tunamods.customglint.module.recipe;

import javax.annotation.Nullable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;

import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;
import net.tunamods.customglint.module.item.ModItems;

/**
 * Shared pieces of the Trim crafting recipes: the "one trim plus N modifier items" grid scans behind the
 * speed / scale / alpha recipes, and the example stacks the recipe book and JEI display. None of this takes
 * part in a recipe's stored data, so it stays package-private support code.
 */
final class TrimRecipes {
    private TrimRecipes() {}

    /** Modifier cap for the speed / scale / alpha recipes: eight slots surround the trim in a 3x3 grid. */
    static final int MAX_MODIFIERS = 8;

    /** A Glint Trim found in a crafting grid together with the number of modifier items beside it. */
    record TrimAndCount(ItemStack trim, int count) {}

    /**
     * Strict grid check for the modifier recipes: exactly one patterned Glint Trim, 1 to {@link #MAX_MODIFIERS}
     * copies of {@code modifier}, and nothing else. Returns null when the grid holds anything more.
     */
    @Nullable
    static TrimAndCount matchTrimPlusModifier(CraftingInput inv, Item modifier) {
        ItemStack trim = ItemStack.EMPTY;
        int count = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof GlintTrimItem && GlintTrimItem.getPattern(s) != null) {
                if (!trim.isEmpty()) return null;
                trim = s;
            } else if (s.is(modifier)) {
                count++;
            } else {
                return null;
            }
        }
        if (trim.isEmpty() || count < 1 || count > MAX_MODIFIERS) return null;
        return new TrimAndCount(trim, count);
    }

    /**
     * The assemble-side scan for the modifier recipes: the last Glint Trim in the grid plus the modifier
     * count, ignoring anything else. Looser than {@link #matchTrimPlusModifier} on purpose, because a
     * third-party autocrafter can call assemble() without calling matches() first. The trim comes back EMPTY
     * when the grid holds none, and every caller returns an empty result in that case.
     */
    static TrimAndCount scanTrimPlusModifier(CraftingInput inv, Item modifier) {
        ItemStack trim = ItemStack.EMPTY;
        int count = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof GlintTrimItem) trim = s;
            else if (s.is(modifier)) count++;
        }
        return new TrimAndCount(trim, count);
    }

    /** Display-only Glint Trim for {@code getResultItem} / {@code getIngredients}. */
    static ItemStack exampleTrim(ResourceLocation design, int... colors) {
        ItemStack trim = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(trim, design);
        for (int color : colors) GlintTrimItem.addColor(trim, color);
        return trim;
    }

    /** Display-only Glow Trim, the glow-side counterpart of {@link #exampleTrim}. */
    static ItemStack exampleGlowTrim(int... colors) {
        ItemStack trim = new ItemStack(ModItems.GLOW_TRIM.get());
        for (int color : colors) GlowTrimItem.addColor(trim, color);
        return trim;
    }

    /** Every vanilla dye, for the dye slot of the two dye recipes. */
    static Ingredient anyDye() {
        return Ingredient.of(
            Items.WHITE_DYE, Items.ORANGE_DYE, Items.MAGENTA_DYE, Items.LIGHT_BLUE_DYE,
            Items.YELLOW_DYE, Items.LIME_DYE, Items.PINK_DYE, Items.GRAY_DYE,
            Items.LIGHT_GRAY_DYE, Items.CYAN_DYE, Items.PURPLE_DYE, Items.BLUE_DYE,
            Items.BROWN_DYE, Items.GREEN_DYE, Items.RED_DYE, Items.BLACK_DYE
        );
    }
}
