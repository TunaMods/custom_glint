package net.tunamods.customglint.module.recipe;

import net.minecraft.core.HolderLookup;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.ModItems;

/**
 * Recycle recipe: 4 Trim Powder + 2 Glowstone Dust → one fresh, randomly designed Glint Trim. The result is
 * rolled in {@link #assemble} (a random design, sometimes with a few random colors), so the output preview
 * changes each time the grid updates — that's intentional, it signals the trim is random.
 */
public class TrimPowderRecipe extends CustomRecipe {

    public static final SimpleCraftingRecipeSerializer<TrimPowderRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(TrimPowderRecipe::new);

    private static final int POWDER_COUNT = 4;
    private static final int GLOWSTONE_COUNT = 2;

    public TrimPowderRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput inv, Level level) {
        int powder = 0, glowstone = 0, filled = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            filled++;
            if (s.getItem() == ModItems.TRIM_POWDER.get()) powder++;
            else if (s.is(Items.GLOWSTONE_DUST)) glowstone++;
            else return false;
        }
        return filled == POWDER_COUNT + GLOWSTONE_COUNT && powder == POWDER_COUNT && glowstone == GLOWSTONE_COUNT;
    }

    @Override
    public ItemStack assemble(CraftingInput inv, HolderLookup.Provider registryAccess) {
        RandomSource random = RandomSource.create();
        ItemStack trim = new ItemStack(ModItems.GLINT_TRIM.get());
        // Mirror the loot roll: a random design, always blank (no colors) so identical designs stack.
        // designFromName handles the vanilla/chromatic sentinels the same way the loot modifier does.
        String name = GlintTrimItem.PATTERNS.get(random.nextInt(GlintTrimItem.PATTERNS.size()));
        GlintTrimItem.setPattern(trim, CustomGlint.designFromName(name));
        return trim;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registryAccess) {
        ItemStack trim = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(trim, CustomGlint.WAVE);
        return trim;
    }

    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= POWDER_COUNT + GLOWSTONE_COUNT;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }
}
