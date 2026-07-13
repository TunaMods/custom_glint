package net.tunamods.customglint.module.recipe;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.tunamods.customglint.module.item.GlintTrimItem;

/** Trim + N slime balls → the same trim scaled up (+0.5 pattern scale per slime ball). */
public class GlintTrimScaleRecipe extends AbstractTrimModifierRecipe {
    public static final SimpleCraftingRecipeSerializer<GlintTrimScaleRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintTrimScaleRecipe::new);

    public GlintTrimScaleRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override protected Item modifier() { return Items.SLIME_BALL; }

    @Override protected void apply(ItemStack result, int count) {
        GlintTrimItem.setScale(result, count * 0.5f);
    }

    @Override protected void decorateResult(ItemStack result) {
        GlintTrimItem.setScale(result, 2.0f);
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }
}
