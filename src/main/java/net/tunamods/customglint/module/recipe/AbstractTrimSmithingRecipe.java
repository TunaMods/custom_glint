package net.tunamods.customglint.module.recipe;

import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.level.Level;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;

/**
 * Shared plumbing for the trim/glow-trim smithing recipes: template + base item + Glowstone Dust. Subclasses
 * define what counts as a valid template ({@link #isTemplateIngredient}) and how the base item is transformed
 * ({@link #assemble}); everything else (base = any non-trim item, addition = glowstone, id/type wiring) is
 * common.
 */
public abstract class AbstractTrimSmithingRecipe implements SmithingRecipe {
    protected final ResourceLocation id;

    protected AbstractTrimSmithingRecipe(ResourceLocation id) {
        this.id = id;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public boolean isBaseIngredient(ItemStack stack) {
        return !stack.isEmpty()
                && !(stack.getItem() instanceof GlintTrimItem)
                && !(stack.getItem() instanceof GlowTrimItem);
    }

    @Override
    public boolean isAdditionIngredient(ItemStack stack) {
        return stack.is(Items.GLOWSTONE_DUST);
    }

    @Override
    public boolean matches(Container pContainer, Level pLevel) {
        return isTemplateIngredient(pContainer.getItem(0))
                && isBaseIngredient(pContainer.getItem(1))
                && isAdditionIngredient(pContainer.getItem(2));
    }

    /**
     * JEI ingredient list for a trim smithing recipe. The base and addition slots are identical for every
     * subclass, so they only supply the template sample.
     */
    protected NonNullList<Ingredient> ingredientsWithTemplate(ItemStack template) {
        NonNullList<Ingredient> list = NonNullList.create();
        list.add(Ingredient.of(template));
        list.add(Ingredient.of(Items.DIAMOND_SWORD, Items.DIAMOND_CHESTPLATE, Items.BOW, Items.BOOK, Items.ELYTRA));
        list.add(Ingredient.of(Items.GLOWSTONE_DUST));
        return list;
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(Container pContainer) {
        return NonNullList.withSize(pContainer.getContainerSize(), ItemStack.EMPTY);
    }

    @Override
    public boolean canCraftInDimensions(int pWidth, int pHeight) {
        return true;
    }

    @Override
    public RecipeType<?> getType() {
        return RecipeType.SMITHING;
    }
}
