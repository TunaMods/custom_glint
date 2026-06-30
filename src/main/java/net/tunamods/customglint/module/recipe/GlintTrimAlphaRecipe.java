package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.common.CustomGlint;

import net.tunamods.customglint.module.item.ModItems;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;

import net.tunamods.customglint.module.item.GlintTrimItem;

/**
 * GlintTrimItem (with a pattern + ≥1 color) + 1–8 Glass → the same trim with every color's alpha (the A /
 * transparency channel) set by the glass count: 8 glass = fully opaque (A=255), fewer = more see-through.
 * Mirrors {@link GlintTrimScaleRecipe}.
 */
public class GlintTrimAlphaRecipe extends CustomRecipe {
    public static final SimpleCraftingRecipeSerializer<GlintTrimAlphaRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintTrimAlphaRecipe::new);

    public GlintTrimAlphaRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

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
            } else if (s.is(Items.GLASS)) {
                count++;
            } else {
                return false;
            }
        }
        return !trim.isEmpty() && GlintTrimItem.getColors(trim).length > 0 && count >= 1 && count <= 8;
    }

    @Override
    public ItemStack assemble(CraftingContainer pInv, RegistryAccess pRegistryAccess) {
        ItemStack trim = ItemStack.EMPTY;
        int count = 0;
        for (int i = 0; i < pInv.getContainerSize(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof GlintTrimItem) trim = s;
            else if (s.is(Items.GLASS)) count++;
        }
        if (trim.isEmpty()) return ItemStack.EMPTY;
        int[] colors = GlintTrimItem.getColors(trim);
        if (colors.length == 0) return ItemStack.EMPTY;
        int alpha = Math.round(count * 255f / 8f);
        int[] out = new int[colors.length];
        for (int i = 0; i < colors.length; i++) out[i] = (alpha << 24) | (colors[i] & 0xFFFFFF);
        ItemStack result = trim.copy();
        result.setCount(1);
        GlintTrimItem.setColors(result, out);
        return result;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess pRegistryAccess) {
        ItemStack result = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(result, CustomGlint.WAVE);
        GlintTrimItem.addColor(result, 0xFFFF0000);
        return result;
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        ItemStack trimExample = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(trimExample, CustomGlint.WAVE);
        GlintTrimItem.addColor(trimExample, 0xFFFF0000);
        list.add(Ingredient.of(trimExample));
        list.add(Ingredient.of(Items.GLASS));
        return list;
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
