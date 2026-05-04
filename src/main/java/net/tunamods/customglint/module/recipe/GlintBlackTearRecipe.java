// MIT License — Copyright (c) 2026 Likely Tuna | TunaMods — see LICENSE.txt
package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;

public class GlintBlackTearRecipe extends CustomRecipe {

    public static final SimpleCraftingRecipeSerializer<GlintBlackTearRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintBlackTearRecipe::new);

    public GlintBlackTearRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer pInv, Level pLevel) {
        boolean hasTear = false;
        boolean hasGlinted = false;
        int filled = 0;
        for (int i = 0; i < pInv.getContainerSize(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            filled++;
            if (s.getItem() == CustomGlintMod.GLINT_BLACK_TEAR.get()) {
                if (hasTear) return false;
                hasTear = true;
            } else if (CustomGlint.has(s)) {
                if (hasGlinted) return false;
                hasGlinted = true;
            } else {
                return false;
            }
        }
        return filled == 2 && hasTear && hasGlinted;
    }

    @Override
    public ItemStack assemble(CraftingContainer pInv, RegistryAccess pRegistryAccess) {
        ItemStack glinted = ItemStack.EMPTY;
        for (int i = 0; i < pInv.getContainerSize(); i++) {
            ItemStack s = pInv.getItem(i);
            if (!s.isEmpty() && s.getItem() != CustomGlintMod.GLINT_BLACK_TEAR.get() && CustomGlint.has(s)) {
                glinted = s;
                break;
            }
        }
        if (glinted.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = glinted.copy();
        result.setCount(1);
        if (result.getItem() instanceof GlintTrimItem) {
            ResourceLocation pattern = GlintTrimItem.getPattern(result);
            if (pattern == null) {
                CustomGlint.Data data = CustomGlint.read(result);
                if (data != null && data.layers().length > 0) pattern = data.layers()[0].design();
            }
            if (result.hasTag()) {
                result.getTag().remove(GlintTrimItem.COLORS_TAG);
                result.getTag().remove(GlintTrimItem.SPEED_TAG);
                result.getTag().remove(GlintTrimItem.SCALE_TAG);
            }
            CustomGlint.remove(result);
            if (pattern != null) GlintTrimItem.setPattern(result, pattern);
        } else {
            CustomGlint.remove(result);
        }
        return result;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess pRegistryAccess) {
        return new ItemStack(Items.DIAMOND_SWORD);
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        list.add(Ingredient.of(CustomGlintMod.GLINT_BLACK_TEAR.get().getDefaultInstance()));
        list.add(Ingredient.of(
            CustomGlint.glinted(Items.DIAMOND_SWORD, new ResourceLocation("customglint", "textures/glint/wave.png"), new int[]{0xFFFF0000}),
            CustomGlint.glinted(Items.GOLDEN_CHESTPLATE, new ResourceLocation("customglint", "textures/glint/sparkle.png"), new int[]{0xFF00AAFF}),
            CustomGlint.glinted(Items.BOW, new ResourceLocation("customglint", "textures/glint/stars.png"), new int[]{0xFFFFFF00}),
            CustomGlint.glinted(Items.BOOK, new ResourceLocation("customglint", "textures/glint/pulse.png"), new int[]{0xFF8800CC})
        ));
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
