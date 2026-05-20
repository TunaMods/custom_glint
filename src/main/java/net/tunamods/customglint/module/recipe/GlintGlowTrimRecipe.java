package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;

/** Crafting: GlintTrimItem (center, with pattern + ≥1 color) + 8 Glowstone Dust → same trim with glowing=true. */
public class GlintGlowTrimRecipe extends CustomRecipe {
    public static final SimpleCraftingRecipeSerializer<GlintGlowTrimRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintGlowTrimRecipe::new);

    public GlintGlowTrimRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer pInv, Level pLevel) {
        if (pInv.getWidth() != 3 || pInv.getHeight() != 3) return false;
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
    public ItemStack assemble(CraftingContainer pInv, RegistryAccess pRegistryAccess) {
        ItemStack trim = pInv.getItem(4);
        if (trim.isEmpty() || !(trim.getItem() instanceof GlintTrimItem)) return ItemStack.EMPTY;
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

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }
}
