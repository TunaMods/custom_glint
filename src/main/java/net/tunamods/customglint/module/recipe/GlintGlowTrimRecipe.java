package net.tunamods.customglint.module.recipe;

import net.minecraft.core.HolderLookup;
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

/** Crafting: GlintTrimItem (center, with pattern + ≥1 color) + 8 Glowstone Dust → same trim with glowing=true. */
public class GlintGlowTrimRecipe extends CustomRecipe {
    public static final SimpleCraftingRecipeSerializer<GlintGlowTrimRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintGlowTrimRecipe::new);

    public GlintGlowTrimRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        if (pInv.width() != 3 || pInv.height() != 3) return false;
        for (int i = 0; i < 9; i++) {
            ItemStack s = pInv.getItem(i);
            if (i == 4) {
                if (!(s.getItem() instanceof GlintTrimItem)) return false;
                if (GlintTrimItem.getPattern(s) == null) return false;
                if (GlintTrimItem.getColors(s).length == 0) return false;
            } else {
                if (!s.is(Items.GLOWSTONE_DUST)) return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack assemble(CraftingInput pInv, HolderLookup.Provider pRegistryAccess) {
        // Find the trim by scanning rather than indexing slot 4 directly: a third-party autocrafter may call
        // assemble() on a sub-5-slot CraftingInput without first calling matches(), which would otherwise throw.
        ItemStack trim = ItemStack.EMPTY;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.getItem() instanceof GlintTrimItem) { trim = s; break; }
        }
        if (trim.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = trim.copy();
        result.setCount(1);
        GlintTrimItem.setGlowing(result, true);
        CustomGlint.setGlowing(result, true);
        return result;
    }

    @Override
    public boolean canCraftInDimensions(int pWidth, int pHeight) {
        return pWidth >= 3 && pHeight >= 3;
    }

    /** Data-component transform (like the other Trim recipes): special so it gets no recipe-book toast. */
    @Override
    public boolean isSpecial() { return true; }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }
}
