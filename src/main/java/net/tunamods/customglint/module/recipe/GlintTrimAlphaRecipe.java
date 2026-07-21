package net.tunamods.customglint.module.recipe;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.tunamods.customglint.module.item.GlintTrimItem;

/** Trim (pattern + ≥1 color) + N glass → set every color's alpha to N/8 (8 glass = opaque). */
public class GlintTrimAlphaRecipe extends AbstractTrimModifierRecipe {
    public static final SimpleCraftingRecipeSerializer<GlintTrimAlphaRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintTrimAlphaRecipe::new);

    public GlintTrimAlphaRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override protected Item modifier() { return Items.GLASS; }

    @Override protected boolean requiresColors() { return true; }

    @Override protected void apply(ItemStack result, int count) {
        int[] colors = GlintTrimItem.getColors(result);
        int alpha = Math.round(count * 255f / 8f); // 8 glass = opaque (A=255)
        int[] out = new int[colors.length];
        for (int i = 0; i < colors.length; i++) out[i] = (alpha << 24) | (colors[i] & 0xFFFFFF);
        GlintTrimItem.setColors(result, out);
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }
}
