package net.tunamods.customglint.module.recipe;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.ModItems;

public class GlintTearApplyRecipe extends CustomRecipe {
    public static final SimpleCraftingRecipeSerializer<GlintTearApplyRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintTearApplyRecipe::new);

    public GlintTearApplyRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        boolean hasTear = false;
        boolean hasGlinted = false;
        int filled = 0;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            filled++;
            if (s.getItem() == ModItems.GLINT_TEAR_SIMULTANEOUS.get()
                    || s.getItem() == ModItems.GLINT_TEAR_SEQUENTIAL.get()) {
                if (hasTear) return false;
                hasTear = true;
            } else if (CustomGlint.has(s) && !(s.getItem() instanceof GlintTrimItem && GlintTrimItem.getColors(s).length == 0)) {
                if (hasGlinted) return false;
                hasGlinted = true;
            } else {
                return false;
            }
        }
        return filled == 2 && hasTear && hasGlinted;
    }

    @Override
    public ItemStack assemble(CraftingInput pInv, HolderLookup.Provider pRegistryAccess) {
        ItemStack glinted = ItemStack.EMPTY;
        Boolean simultaneous = null;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() == ModItems.GLINT_TEAR_SIMULTANEOUS.get()) simultaneous = true;
            else if (s.getItem() == ModItems.GLINT_TEAR_SEQUENTIAL.get()) simultaneous = false;
            else if (CustomGlint.has(s)) glinted = s;
        }
        if (glinted.isEmpty() || simultaneous == null) return ItemStack.EMPTY;
        CustomGlint.Data data = CustomGlint.read(glinted);
        if (data == null) return ItemStack.EMPTY;
        ItemStack result = glinted.copy();
        result.setCount(1);
        CustomGlint.Layer[] src = data.layers();
        CustomGlint.Layer[] newLayers = new CustomGlint.Layer[src.length];
        for (int i = 0; i < src.length; i++)
            // Override only the simultaneous flag; preserve each layer's scroll direction/offset and chromatic seed.
            newLayers[i] = new CustomGlint.Layer(src[i].design(), src[i].colors(), src[i].speed(), src[i].interpolate(),
                    src[i].patternScale(), simultaneous, src[i].scrollDir(), src[i].scrollOffset(), src[i].seed());
        CustomGlint.write(result, newLayers);
        return result;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider pRegistryAccess) {
        ItemStack result = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(result, CustomGlint.res("textures/glint/wave.png"));
        GlintTrimItem.addColor(result, 0xFFFF0000);
        GlintTrimItem.addColor(result, 0xFF0000FF);
        CustomGlint.write(result, CustomGlint.res("textures/glint/wave.png"), new int[]{0xFFFF0000, 0xFF0000FF}, 1.0f, true, 1.0f, true);
        return result;
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        list.add(Ingredient.of(
            ModItems.GLINT_TEAR_SIMULTANEOUS.get().getDefaultInstance(),
            ModItems.GLINT_TEAR_SEQUENTIAL.get().getDefaultInstance()
        ));
        ItemStack trimRed = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(trimRed, CustomGlint.res("textures/glint/wave.png"));
        GlintTrimItem.addColor(trimRed, 0xFFFF0000);
        ItemStack trimBlue = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(trimBlue, CustomGlint.res("textures/glint/sparkle.png"));
        GlintTrimItem.addColor(trimBlue, 0xFF0000FF);
        ItemStack trimRedBlue = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(trimRedBlue, CustomGlint.res("textures/glint/wave.png"));
        GlintTrimItem.addColor(trimRedBlue, 0xFFFF0000);
        GlintTrimItem.addColor(trimRedBlue, 0xFF0000FF);
        ItemStack trimGold = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(trimGold, CustomGlint.res("textures/glint/stars.png"));
        GlintTrimItem.addColor(trimGold, 0xFFFFAA00);
        list.add(Ingredient.of(trimRed, trimBlue, trimRedBlue, trimGold));
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
