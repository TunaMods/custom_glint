package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
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

public class GlintTrimBlankDuplicateRecipe extends CustomRecipe {
    public static final SimpleCraftingRecipeSerializer<GlintTrimBlankDuplicateRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintTrimBlankDuplicateRecipe::new);

    public GlintTrimBlankDuplicateRecipe(ResourceLocation id, CraftingBookCategory category) {
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
                if (GlintTrimItem.getColors(s).length != 0) return false;
            } else if (i == 7) {
                if (!s.is(Items.GLOWSTONE_DUST)) return false;
            } else {
                if (!s.is(Items.DIAMOND)) return false;
            }
        }
        return true;
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
    public ItemStack getResultItem(RegistryAccess pRegistryAccess) {
        ItemStack result = new ItemStack(CustomGlintMod.GLINT_TRIM.get(), 2);
        GlintTrimItem.setPattern(result, new ResourceLocation("customglint", "textures/glint/wave.png"));
        return result;
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.withSize(9, Ingredient.EMPTY);
        ItemStack trimExample = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
        GlintTrimItem.setPattern(trimExample, new ResourceLocation("customglint", "textures/glint/wave.png"));
        for (int i = 0; i < 9; i++) {
            if (i == 4) list.set(i, Ingredient.of(trimExample));
            else if (i == 7) list.set(i, Ingredient.of(Items.GLOWSTONE_DUST));
            else list.set(i, Ingredient.of(Items.DIAMOND));
        }
        return list;
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
