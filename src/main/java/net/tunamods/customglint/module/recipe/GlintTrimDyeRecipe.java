package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;

public class GlintTrimDyeRecipe extends CustomRecipe {
    public static final SimpleCraftingRecipeSerializer<GlintTrimDyeRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintTrimDyeRecipe::new);

    public GlintTrimDyeRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer pInv, Level pLevel) {
        ItemStack trim = ItemStack.EMPTY;
        ItemStack dye  = ItemStack.EMPTY;
        int filled = 0;
        for (int i = 0; i < pInv.getContainerSize(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            filled++;
            if (s.getItem() instanceof GlintTrimItem && GlintTrimItem.getPattern(s) != null) {
                if (!trim.isEmpty()) return false;
                trim = s;
            } else if (s.getItem() instanceof DyeItem) {
                if (!dye.isEmpty()) return false;
                dye = s;
            } else {
                return false;
            }
        }
        return filled == 2 && !trim.isEmpty() && !dye.isEmpty();
    }

    @Override
    public ItemStack assemble(CraftingContainer pInv, RegistryAccess pRegistryAccess) {
        ItemStack trim = ItemStack.EMPTY;
        DyeItem dye = null;
        for (int i = 0; i < pInv.getContainerSize(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof GlintTrimItem) trim = s;
            else if (s.getItem() instanceof DyeItem d) dye = d;
        }
        if (trim.isEmpty() || dye == null) return ItemStack.EMPTY;
        ItemStack result = trim.copy();
        result.setCount(1);
        int[] colors = new int[]{ GlintTrimItem.DYE_COLORS[dye.getDyeColor().ordinal()] };
        result.getOrCreateTag().put(GlintTrimItem.COLORS_TAG, new IntArrayTag(colors));
        ResourceLocation pattern = GlintTrimItem.getPattern(result);
        if (pattern != null) CustomGlint.write(result, pattern, colors, 1.0f, true, 1.0f, false);
        return result;
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
