package net.tunamods.customglint.module.recipe;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlowTrimItem;
import net.tunamods.customglint.module.item.ModItems;

/**
 * Smithing: Glow Trim (template) + base item + Glowstone Dust → base item with glowColors+glowing applied via
 * {@link CustomGlint#setGlowColors}. Does NOT touch any existing glint Data on the base - glow-only.
 */
public class GlowTrimSmithingRecipe extends AbstractTrimSmithingRecipe {
    public static final RecipeSerializer<GlowTrimSmithingRecipe> SERIALIZER =
            new SimpleSmithingSerializer<>(GlowTrimSmithingRecipe::new);

    public GlowTrimSmithingRecipe(ResourceLocation id) {
        super(id);
    }

    @Override
    public boolean isTemplateIngredient(ItemStack stack) {
        return stack.getItem() instanceof GlowTrimItem
                && GlowTrimItem.getColors(stack).length > 0;
    }

    @Override
    public ItemStack assemble(Container pContainer, RegistryAccess pRegistryAccess) {
        ItemStack template = pContainer.getItem(0);
        ItemStack base     = pContainer.getItem(1);
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
        ItemStack trimExample = new ItemStack(ModItems.GLOW_TRIM.get());
        GlowTrimItem.addColor(trimExample, 0xFFFF0000);
        list.add(Ingredient.of(trimExample));
        list.add(Ingredient.of(Items.DIAMOND_SWORD, Items.DIAMOND_CHESTPLATE, Items.BOW, Items.BOOK, Items.ELYTRA));
        list.add(Ingredient.of(Items.GLOWSTONE_DUST));
        return list;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess pRegistryAccess) {
        ItemStack result = new ItemStack(Items.DIAMOND_SWORD);
        CustomGlint.setGlowColors(result, new int[]{0xFFFF0000});
        return result;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }
}
