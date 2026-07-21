package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlowTrimItem;
import com.mojang.serialization.MapCodec;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleSmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;

/**
 * Smithing: Glow Trim (template) + base item + Glowstone Dust -> base item with
 * glowColors+glowing applied via {@link CustomGlint#setGlowColors}. Does NOT touch any
 * existing glint Data on the base, Glow Trim is strictly a glow-only application.
 */
public class GlowTrimSmithingRecipe extends AbstractTrimSmithingRecipe {
    public static final MapCodec<GlowTrimSmithingRecipe> MAP_CODEC =
            Recipe.CommonInfo.MAP_CODEC.xmap(GlowTrimSmithingRecipe::new, r -> r.commonInfo);
    public static final StreamCodec<RegistryFriendlyByteBuf, GlowTrimSmithingRecipe> STREAM_CODEC =
            Recipe.CommonInfo.STREAM_CODEC.map(GlowTrimSmithingRecipe::new, r -> r.commonInfo);
    public static final RecipeSerializer<GlowTrimSmithingRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public GlowTrimSmithingRecipe() { this(new Recipe.CommonInfo(false)); }
    public GlowTrimSmithingRecipe(Recipe.CommonInfo commonInfo) { super(commonInfo); }

    @Override
    protected boolean isTemplateIngredient(ItemStack stack) {
        return stack.getItem() instanceof GlowTrimItem
                && GlowTrimItem.getColors(stack).length > 0;
    }

    @Override
    public ItemStack assemble(SmithingRecipeInput pInput) {
        ItemStack template = pInput.getItem(0);
        ItemStack base     = pInput.getItem(1);
        int[] colors = GlowTrimItem.getColors(template);
        if (colors.length == 0) return ItemStack.EMPTY;
        ItemStack result = base.copy();
        result.setCount(1);
        CustomGlint.setGlowColors(result, colors);
        CustomGlint.setGlowing(result, true);
        // Carry the trim's glow-cycle speed + interpolation onto the item so its outline animates as designed.
        CustomGlint.setGlowAnim(result, CustomGlint.getGlowSpeed(template), CustomGlint.getGlowInterpolate(template));
        return result;
    }

    @Override
    public Optional<Ingredient> templateIngredient() {
        return Optional.of(Ingredient.of(ModItems.GLOW_TRIM.get()));
    }

    @Override
    public RecipeSerializer<? extends SimpleSmithingRecipe> getSerializer() {
        return SERIALIZER;
    }
}
