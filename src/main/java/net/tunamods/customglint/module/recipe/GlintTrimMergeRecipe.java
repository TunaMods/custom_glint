package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
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
        int trimCount = 0;
        for (int i = 0; i < pInv.getContainerSize(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (!(s.getItem() instanceof GlintTrimItem)) return false;
            if (GlintTrimItem.getPattern(s) == null) return false;
            if (GlintTrimItem.getColors(s).length == 0) return false;
            trimCount++;
        }
        return trimCount >= 2;
    }

    @Override
    public ItemStack assemble(CraftingContainer pInv, RegistryAccess pRegistryAccess) {
        ItemStack result = ItemStack.EMPTY;
        for (int i = 0; i < pInv.getContainerSize(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (result.isEmpty()) { result = s; continue; }
            result = GlintTrimItem.mergeColors(result, s);
        }
        return result;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess pRegistryAccess) {
        ItemStack result = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
        GlintTrimItem.setPattern(result, new ResourceLocation("customglint", "textures/glint/wave.png"));
        GlintTrimItem.addColor(result, 0xFFFF0000);
        GlintTrimItem.addColor(result, 0xFF00AAFF);
        return result;
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        ItemStack trim1 = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
        GlintTrimItem.setPattern(trim1, new ResourceLocation("customglint", "textures/glint/wave.png"));
        GlintTrimItem.addColor(trim1, 0xFFFF0000);
        ItemStack trim2 = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
        GlintTrimItem.setPattern(trim2, new ResourceLocation("customglint", "textures/glint/sparkle.png"));
        GlintTrimItem.addColor(trim2, 0xFF00AAFF);
        list.add(Ingredient.of(trim1));
        list.add(Ingredient.of(trim2));
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
