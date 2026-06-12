package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.level.Level;

/**
 * Smithing: Glow Trim (template) + base item + Glowstone Dust → base item with
 * glowColors+glowing applied via {@link CustomGlint#setGlowColors}. Does NOT touch any
 * existing glint Data on the base — Glow Trim is strictly a glow-only application.
 */
public class GlowTrimSmithingRecipe implements SmithingRecipe {
    public static final Serializer SERIALIZER = new Serializer();

    public GlowTrimSmithingRecipe() {}

    @Override
    public boolean isTemplateIngredient(ItemStack stack) {
        return stack.getItem() instanceof GlowTrimItem
                && GlowTrimItem.getColors(stack).length > 0;
    }

    @Override
    public boolean isBaseIngredient(ItemStack stack) {
        return !stack.isEmpty()
                && !(stack.getItem() instanceof GlowTrimItem)
                && !(stack.getItem() instanceof GlintTrimItem);
    }

    @Override
    public boolean isAdditionIngredient(ItemStack stack) {
        return stack.is(Items.GLOWSTONE_DUST);
    }

    @Override
    public boolean matches(SmithingRecipeInput pInput, Level pLevel) {
        return isTemplateIngredient(pInput.getItem(0))
                && isBaseIngredient(pInput.getItem(1))
                && isAdditionIngredient(pInput.getItem(2));
    }

    @Override
    public ItemStack assemble(SmithingRecipeInput pInput, HolderLookup.Provider pRegistryAccess) {
        ItemStack template = pInput.getItem(0);
        ItemStack base     = pInput.getItem(1);
        int[] colors = GlowTrimItem.getColors(template);
        if (colors.length == 0) return ItemStack.EMPTY;
        ItemStack result = base.copy();
        result.setCount(1);
        CustomGlint.setGlowColors(result, colors);
        CustomGlint.setGlowing(result, true);
        return result;
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        ItemStack trimExample = new ItemStack(CustomGlintMod.GLOW_TRIM.get());
        GlowTrimItem.addColor(trimExample, 0xFFFF0000);
        list.add(Ingredient.of(trimExample));
        list.add(Ingredient.of(Items.DIAMOND_SWORD, Items.DIAMOND_CHESTPLATE, Items.BOW, Items.BOOK, Items.ELYTRA));
        list.add(Ingredient.of(Items.GLOWSTONE_DUST));
        return list;
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(SmithingRecipeInput pInput) {
        return NonNullList.withSize(pInput.size(), ItemStack.EMPTY);
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider pRegistryAccess) {
        ItemStack result = new ItemStack(Items.DIAMOND_SWORD);
        CustomGlint.setGlowColors(result, new int[]{0xFFFF0000});
        return result;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }

    public static class Serializer implements RecipeSerializer<GlowTrimSmithingRecipe> {
        private static final MapCodec<GlowTrimSmithingRecipe> CODEC =
                MapCodec.unit(GlowTrimSmithingRecipe::new);
        private static final StreamCodec<RegistryFriendlyByteBuf, GlowTrimSmithingRecipe> STREAM_CODEC =
                StreamCodec.unit(new GlowTrimSmithingRecipe());

        @Override
        public MapCodec<GlowTrimSmithingRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, GlowTrimSmithingRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
