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
import net.tunamods.customglint.module.item.GlintTrimItem;

/** Smithing: Glint Trim (template) + base item + Glowstone Dust → the base item carrying the trim's glint. */
public class GlintTrimSmithingRecipe extends AbstractTrimSmithingRecipe {
    public static final RecipeSerializer<GlintTrimSmithingRecipe> SERIALIZER =
            new SimpleSmithingSerializer<>(GlintTrimSmithingRecipe::new);

    public GlintTrimSmithingRecipe(ResourceLocation id) {
        super(id);
    }

    @Override
    public boolean isTemplateIngredient(ItemStack stack) {
        if (!(stack.getItem() instanceof GlintTrimItem)) return false;
        ResourceLocation pattern = GlintTrimItem.getPattern(stack);
        // Chromatic trims render from a procedural palette and are valid with zero colors.
        return pattern != null
                && (GlintTrimItem.getColors(stack).length > 0 || CustomGlint.isChromatic(pattern));
    }

    @Override
    public ItemStack assemble(Container pContainer, RegistryAccess pRegistryAccess) {
        ItemStack template = pContainer.getItem(0);
        ItemStack base     = pContainer.getItem(1);
        ResourceLocation pattern = GlintTrimItem.getPattern(template);
        int[] colors             = GlintTrimItem.getColors(template);
        if (pattern == null || (colors.length == 0 && !CustomGlint.isChromatic(pattern))) return ItemStack.EMPTY;
        CustomGlint.Data preview = CustomGlint.read(template);
        boolean simultaneous = preview != null && preview.layers().length > 0 && preview.layers()[0].simultaneous();
        float speed          = preview != null && preview.layers().length > 0 ? preview.layers()[0].speed() : 1.0f;
        boolean interpolate  = preview == null || preview.layers().length == 0 || preview.layers()[0].interpolate();
        ItemStack result = base.copy();
        result.setCount(1);
        if (preview != null && preview.layers().length > 1) {
            CustomGlint.write(result, preview.layers());
        } else {
            CustomGlint.write(result, pattern, colors, speed, interpolate, GlintTrimItem.getScale(template), simultaneous);
        }
        if (GlintTrimItem.isGlowing(template)) CustomGlint.setGlowing(result, true);
        return result;
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        return ingredientsWithTemplate(GlintTrimItem.example(CustomGlint.WAVE, 0xFFFF0000));
    }

    @Override
    public ItemStack getResultItem(RegistryAccess pRegistryAccess) {
        return CustomGlint.glinted(Items.DIAMOND_SWORD, CustomGlint.WAVE, new int[]{0xFFFF0000});
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }
}
