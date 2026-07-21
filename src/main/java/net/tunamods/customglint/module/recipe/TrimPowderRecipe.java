package net.tunamods.customglint.module.recipe;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.ModItems;

/**
 * Recycle recipe: 4 Trim Powder + 2 Glowstone Dust → one fresh Glint Trim with a random design and no colors
 * (blank and stackable, like loot-dropped trims). The design is rolled in {@link #assemble}.
 */
public class TrimPowderRecipe extends CustomRecipe {

    public static final SimpleCraftingRecipeSerializer<TrimPowderRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(TrimPowderRecipe::new);

    private static final int POWDER_COUNT = 4;
    private static final int GLOWSTONE_COUNT = 2;

    public TrimPowderRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer pInv, Level pLevel) {
        int powder = 0, glowstone = 0, filled = 0;
        for (int i = 0; i < pInv.getContainerSize(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            filled++;
            if (s.getItem() == ModItems.TRIM_POWDER.get()) powder++;
            else if (s.is(Items.GLOWSTONE_DUST)) glowstone++;
            else return false;
        }
        return filled == POWDER_COUNT + GLOWSTONE_COUNT && powder == POWDER_COUNT && glowstone == GLOWSTONE_COUNT;
    }

    @Override
    public ItemStack assemble(CraftingContainer pInv, RegistryAccess pRegistryAccess) {
        RandomSource random = RandomSource.create();
        ItemStack trim = new ItemStack(ModItems.GLINT_TRIM.get());
        // Random design only, always blank (no colors), matching how loot trims drop, so recycled trims stay
        // stackable. designFromName handles the vanilla/chromatic sentinels the same way the loot modifier does.
        String name = GlintTrimItem.PATTERNS.get(random.nextInt(GlintTrimItem.PATTERNS.size()));
        GlintTrimItem.setPattern(trim, CustomGlint.designFromName(name));
        return trim;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess pRegistryAccess) {
        ItemStack trim = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(trim, CustomGlint.WAVE);
        return trim;
    }

    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        for (int i = 0; i < POWDER_COUNT; i++) list.add(Ingredient.of(ModItems.TRIM_POWDER.get()));
        for (int i = 0; i < GLOWSTONE_COUNT; i++) list.add(Ingredient.of(Items.GLOWSTONE_DUST));
        return list;
    }

    @Override
    public boolean canCraftInDimensions(int pWidth, int pHeight) {
        return pWidth * pHeight >= POWDER_COUNT + GLOWSTONE_COUNT;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }
}
