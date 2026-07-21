package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import com.mojang.serialization.MapCodec;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleSmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;

/** Smithing: GlintTrim (template) + base item + Glowstone Dust -> base item with the trim's glint. */
public class GlintTrimSmithingRecipe extends AbstractTrimSmithingRecipe {
    public static final MapCodec<GlintTrimSmithingRecipe> MAP_CODEC =
            Recipe.CommonInfo.MAP_CODEC.xmap(GlintTrimSmithingRecipe::new, r -> r.commonInfo);
    public static final StreamCodec<RegistryFriendlyByteBuf, GlintTrimSmithingRecipe> STREAM_CODEC =
            Recipe.CommonInfo.STREAM_CODEC.map(GlintTrimSmithingRecipe::new, r -> r.commonInfo);
    public static final RecipeSerializer<GlintTrimSmithingRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public GlintTrimSmithingRecipe() { this(new Recipe.CommonInfo(false)); }
    public GlintTrimSmithingRecipe(Recipe.CommonInfo commonInfo) { super(commonInfo); }

    @Override
    protected boolean isTemplateIngredient(ItemStack stack) {
        return stack.getItem() instanceof GlintTrimItem
                && GlintTrimItem.getPattern(stack) != null
                && GlintTrimItem.getColors(stack).length > 0;
    }

    @Override
    public ItemStack assemble(SmithingRecipeInput pInput) {
        ItemStack template = pInput.getItem(0);
        ItemStack base     = pInput.getItem(1);
        Identifier pattern = GlintTrimItem.getPattern(template);
        int[] colors             = GlintTrimItem.getColors(template);
        if (pattern == null || colors.length == 0) return ItemStack.EMPTY;
        CustomGlint.Data preview = CustomGlint.read(template);
        boolean simultaneous = preview != null && preview.layers().length > 0 && preview.layers()[0].simultaneous();
        float speed          = preview != null && preview.layers().length > 0 ? preview.layers()[0].speed() : 1.0f;
        boolean interpolate  = preview == null || preview.layers().length == 0 || preview.layers()[0].interpolate();
        ItemStack result = base.copy();
        result.setCount(1);
        if (preview != null && preview.layers().length > 1) {
            CustomGlint.write(result, preview.layers());
        } else {
            CustomGlint.write(result, pattern, colors, speed, interpolate, GlintTrimItem.getScale(template), simultaneous,
                    GlintTrimItem.getScrollDir(template), GlintTrimItem.getScrollOffset(template), GlintTrimItem.getSeed(template));
        }
        if (GlintTrimItem.isGlowing(template)) CustomGlint.setGlowing(result, true);
        return result;
    }

    @Override
    public Optional<Ingredient> templateIngredient() {
        return Optional.of(Ingredient.of(ModItems.GLINT_TRIM.get()));
    }

    @Override
    public RecipeSerializer<? extends SimpleSmithingRecipe> getSerializer() {
        return SERIALIZER;
    }
}
