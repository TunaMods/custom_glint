package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;

public class GlintTrimMergeRecipe extends CustomRecipe {
    public static final SimpleCraftingRecipeSerializer<GlintTrimMergeRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintTrimMergeRecipe::new);

    public GlintTrimMergeRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer pInv, Level pLevel) {
        int trimCount   = 0;
        int totalColors = 0;
        for (int i = 0; i < pInv.getContainerSize(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (!(s.getItem() instanceof GlintTrimItem)) return false;
            if (GlintTrimItem.getPattern(s) == null) return false;
            trimCount++;
            totalColors += GlintTrimItem.getColors(s).length;
        }
        return trimCount == 2 && totalColors <= 8;
    }

    @Override
    public ItemStack assemble(CraftingContainer pInv, RegistryAccess pRegistryAccess) {
        ItemStack first  = ItemStack.EMPTY;
        ItemStack second = ItemStack.EMPTY;
        for (int i = 0; i < pInv.getContainerSize(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (first.isEmpty()) first = s;
            else second = s;
        }
        if (first.isEmpty() || second.isEmpty()) return ItemStack.EMPTY;
        return GlintTrimItem.mergeColors(first, second);
    }

    @Override
    public boolean canCraftInDimensions(int pWidth, int pHeight) {
        return pWidth * pHeight >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }
}
