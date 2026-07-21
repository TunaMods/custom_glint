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

/** Glint Tear + any glinted item → the same item with every layer switched to the tear's color mode
 *  (simultaneous or sequential). Nothing else about the glint changes. */
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
        ItemStack result = TrimRecipes.exampleTrim(CustomGlint.WAVE, 0xFFFF0000, 0xFF0000FF);
        CustomGlint.write(result, CustomGlint.WAVE, new int[]{0xFFFF0000, 0xFF0000FF}, 1.0f, true, 1.0f, true);
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
        list.add(Ingredient.of(
            TrimRecipes.exampleTrim(CustomGlint.WAVE, 0xFFFF0000),
            TrimRecipes.exampleTrim(CustomGlint.SPARKLE, 0xFF0000FF),
            TrimRecipes.exampleTrim(CustomGlint.WAVE, 0xFFFF0000, 0xFF0000FF),
            TrimRecipes.exampleTrim(CustomGlint.STARS, 0xFFFFAA00)
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
