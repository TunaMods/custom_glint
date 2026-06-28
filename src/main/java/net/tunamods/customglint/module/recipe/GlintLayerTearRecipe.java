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
import net.tunamods.customglint.module.item.GlintLayerTearItem;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.ModItems;

public class GlintLayerTearRecipe extends CustomRecipe {

    public static final SimpleCraftingRecipeSerializer<GlintLayerTearRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintLayerTearRecipe::new);

    public GlintLayerTearRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        boolean hasTear = false;
        ItemStack glint1 = ItemStack.EMPTY;
        ItemStack glint2 = ItemStack.EMPTY;
        int filled = 0;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            filled++;
            if (s.getItem() instanceof GlintLayerTearItem) {
                if (hasTear) return false;
                hasTear = true;
            } else if (s.getItem() instanceof GlintTrimItem && CustomGlint.has(s) && GlintTrimItem.getColors(s).length > 0) {
                if (glint1.isEmpty()) glint1 = s;
                else if (glint2.isEmpty()) glint2 = s;
                else return false;
            } else {
                return false;
            }
        }
        return filled == 3 && hasTear && !glint1.isEmpty() && !glint2.isEmpty();
    }

    @Override
    public ItemStack assemble(CraftingInput pInv, HolderLookup.Provider pRegistryAccess) {
        ItemStack glint1 = ItemStack.EMPTY;
        ItemStack glint2 = ItemStack.EMPTY;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty() || s.getItem() instanceof GlintLayerTearItem) continue;
            if (s.getItem() instanceof GlintTrimItem) {
                if (glint1.isEmpty()) glint1 = s;
                else glint2 = s;
            }
        }
        if (glint1.isEmpty() || glint2.isEmpty()) return ItemStack.EMPTY;
        CustomGlint.Data d1 = CustomGlint.read(glint1);
        CustomGlint.Data d2 = CustomGlint.read(glint2);
        if (d1 == null || d2 == null) return ItemStack.EMPTY;
        int total = Math.min(d1.layers().length + d2.layers().length, 8);
        CustomGlint.Layer[] combined = new CustomGlint.Layer[total];
        int fromD1 = Math.min(d1.layers().length, total);
        System.arraycopy(d1.layers(), 0, combined, 0, fromD1);
        int fromD2 = total - fromD1;
        if (fromD2 > 0) System.arraycopy(d2.layers(), 0, combined, fromD1, fromD2);
        ItemStack result = glint1.copy();
        result.setCount(1);
        CustomGlint.write(result, combined);
        return result;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider pRegistryAccess) {
        ItemStack result = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(result, CustomGlint.res("textures/glint/wave.png"));
        GlintTrimItem.addColor(result, 0xFFFF0000);
        CustomGlint.Layer layer1 = new CustomGlint.Layer(CustomGlint.res("textures/glint/wave.png"), new int[]{0xFFFF0000}, 1.0f, true, 1.0f, false);
        CustomGlint.Layer layer2 = new CustomGlint.Layer(CustomGlint.res("textures/glint/sparkle.png"), new int[]{0xFF00AAFF}, 1.0f, true, 1.0f, false);
        CustomGlint.write(result, new CustomGlint.Layer[]{layer1, layer2});
        return result;
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        list.add(Ingredient.of(ModItems.GLINT_LAYER_TEAR.get().getDefaultInstance()));
        ItemStack trim1 = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(trim1, CustomGlint.res("textures/glint/wave.png"));
        GlintTrimItem.addColor(trim1, 0xFFFF0000);
        ItemStack trim2 = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(trim2, CustomGlint.res("textures/glint/sparkle.png"));
        GlintTrimItem.addColor(trim2, 0xFF00AAFF);
        list.add(Ingredient.of(trim1));
        list.add(Ingredient.of(trim2));
        return list;
    }

    @Override
    public boolean canCraftInDimensions(int pWidth, int pHeight) {
        return pWidth * pHeight >= 3;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }
}
