package net.tunamods.customglint.module.recipe;

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

public class GlintTrimDuplicateRecipe extends CustomRecipe {
    public static final SimpleCraftingRecipeSerializer<GlintTrimDuplicateRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintTrimDuplicateRecipe::new);

    public GlintTrimDuplicateRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer pInv, Level pLevel) {
        ItemStack trim = ItemStack.EMPTY;
        int diamonds = 0;
        int glowstone = 0;
        int filled = 0;
        for (int i = 0; i < pInv.getContainerSize(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            filled++;
            if (s.getItem() instanceof GlintTrimItem) {
                if (!trim.isEmpty()) return false;
                trim = s;
            } else if (s.is(Items.DIAMOND)) {
                diamonds++;
            } else if (s.is(Items.GLOWSTONE)) {
                glowstone++;
            } else {
                return false;
            }
        }
        return filled == 9 && !trim.isEmpty() && diamonds == 7 && glowstone == 1
                && GlintTrimItem.getPattern(trim) != null
                && GlintTrimItem.getColors(trim).length > 0;
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
    public boolean canCraftInDimensions(int pWidth, int pHeight) {
        return pWidth * pHeight >= 9;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }
}
