package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;

public class GlintTrimSpeedRecipe extends CustomRecipe {
    public static final SimpleCraftingRecipeSerializer<GlintTrimSpeedRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintTrimSpeedRecipe::new);

    public GlintTrimSpeedRecipe(ResourceLocation id, CraftingBookCategory category) {
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
            } else if (s.is(Items.REDSTONE)) {
                count++;
            } else {
                return false;
            }
        }
        return !trim.isEmpty() && count >= 1 && count <= 8;
    }

    @Override
    public ItemStack assemble(CraftingContainer pInv, RegistryAccess pRegistryAccess) {
        ItemStack trim = ItemStack.EMPTY;
        int count = 0;
        for (int i = 0; i < pInv.getContainerSize(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof GlintTrimItem) trim = s;
            else if (s.is(Items.REDSTONE)) count++;
        }
        if (trim.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = trim.copy();
        result.setCount(1);
        GlintTrimItem.setSpeed(result, (float) count);
        return result;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess pRegistryAccess) {
        ItemStack result = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
        GlintTrimItem.setPattern(result, new ResourceLocation("customglint", "textures/glint/wave.png"));
        GlintTrimItem.addColor(result, 0xFFFF0000);
        GlintTrimItem.setSpeed(result, 4.0f);
        return result;
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        ItemStack trimExample = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
        GlintTrimItem.setPattern(trimExample, new ResourceLocation("customglint", "textures/glint/wave.png"));
        GlintTrimItem.addColor(trimExample, 0xFFFF0000);
        list.add(Ingredient.of(trimExample));
        list.add(Ingredient.of(Items.REDSTONE));
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
