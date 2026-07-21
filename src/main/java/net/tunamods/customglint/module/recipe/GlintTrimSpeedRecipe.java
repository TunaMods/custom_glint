package net.tunamods.customglint.module.recipe;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.tunamods.customglint.module.item.GlintTrimItem;

/** Trim + N redstone → the same trim animated faster (speed = redstone count). */
public class GlintTrimSpeedRecipe extends AbstractTrimModifierRecipe {
    public static final SimpleCraftingRecipeSerializer<GlintTrimSpeedRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintTrimSpeedRecipe::new);

    public GlintTrimSpeedRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override protected Item modifier() { return Items.REDSTONE; }

    @Override protected void apply(ItemStack result, int count) {
        GlintTrimItem.setSpeed(result, (float) count);
    }

    @Override protected void decorateResult(ItemStack result) {
        GlintTrimItem.setSpeed(result, 4.0f);
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }
}
