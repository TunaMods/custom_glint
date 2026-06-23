package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;
import com.mojang.serialization.MapCodec;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleSmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.level.Level;

/**
 * Smithing: Glow Trim (template) + base item + Glowstone Dust -> base item with
 * glowColors+glowing applied via {@link CustomGlint#setGlowColors}. Does NOT touch any
 * existing glint Data on the base, Glow Trim is strictly a glow-only application.
 *
 * 26.1.2: predicate {@code matches} override (NBT-based), ingredient accessors for display only.
 */
public class GlowTrimSmithingRecipe extends SimpleSmithingRecipe {
    public static final MapCodec<GlowTrimSmithingRecipe> MAP_CODEC =
            Recipe.CommonInfo.MAP_CODEC.xmap(GlowTrimSmithingRecipe::new, r -> r.commonInfo);
    public static final StreamCodec<RegistryFriendlyByteBuf, GlowTrimSmithingRecipe> STREAM_CODEC =
            Recipe.CommonInfo.STREAM_CODEC.map(GlowTrimSmithingRecipe::new, r -> r.commonInfo);
    public static final RecipeSerializer<GlowTrimSmithingRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public GlowTrimSmithingRecipe() { this(new Recipe.CommonInfo(false)); }
    public GlowTrimSmithingRecipe(Recipe.CommonInfo commonInfo) { super(commonInfo); }

    private boolean isTemplateIngredient(ItemStack stack) {
        return stack.getItem() instanceof GlowTrimItem
                && GlowTrimItem.getColors(stack).length > 0;
    }

    private boolean isBaseIngredient(ItemStack stack) {
        return !stack.isEmpty()
                && !(stack.getItem() instanceof GlowTrimItem)
                && !(stack.getItem() instanceof GlintTrimItem);
    }

    private boolean isAdditionIngredient(ItemStack stack) {
        return stack.is(Items.GLOWSTONE_DUST);
    }

    @Override
    public boolean matches(SmithingRecipeInput pInput, Level pLevel) {
        return isTemplateIngredient(pInput.getItem(0))
                && isBaseIngredient(pInput.getItem(1))
                && isAdditionIngredient(pInput.getItem(2));
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
        return result;
    }

    @Override
    public Optional<Ingredient> templateIngredient() {
        return Optional.of(Ingredient.of(ModItems.GLOW_TRIM.get()));
    }

    @Override
    public Ingredient baseIngredient() {
        return Ingredient.of(Items.DIAMOND_SWORD, Items.DIAMOND_CHESTPLATE, Items.BOW, Items.BOOK, Items.ELYTRA);
    }

    @Override
    public Optional<Ingredient> additionIngredient() {
        return Optional.of(Ingredient.of(Items.GLOWSTONE_DUST));
    }

    @Override
    protected PlacementInfo createPlacementInfo() {
        return PlacementInfo.createFromOptionals(List.of(templateIngredient(), Optional.of(baseIngredient()), additionIngredient()));
    }

    @Override
    public RecipeSerializer<? extends SimpleSmithingRecipe> getSerializer() {
        return SERIALIZER;
    }
}
