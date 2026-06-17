package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;
import com.mojang.serialization.MapCodec;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
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
 * Smithing: GlintTrim (template) + base item + Glowstone Dust -> base item with the trim's glint.
 *
 * 26.1.2 SmithingRecipe is ingredient/codec based, but this recipe needs NBT-predicate matching
 * (any GlintTrim with a pattern+colors; any non-trim base). So {@code matches} is overridden with
 * the real predicate logic, while the ingredient accessors return representative items for the
 * recipe-book/JEI display + placement filtering only.
 */
public class GlintTrimSmithingRecipe extends SimpleSmithingRecipe {
    public static final MapCodec<GlintTrimSmithingRecipe> MAP_CODEC =
            Recipe.CommonInfo.MAP_CODEC.xmap(GlintTrimSmithingRecipe::new, r -> r.commonInfo);
    public static final StreamCodec<RegistryFriendlyByteBuf, GlintTrimSmithingRecipe> STREAM_CODEC =
            Recipe.CommonInfo.STREAM_CODEC.map(GlintTrimSmithingRecipe::new, r -> r.commonInfo);
    public static final RecipeSerializer<GlintTrimSmithingRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public GlintTrimSmithingRecipe() { this(new Recipe.CommonInfo(false)); }
    public GlintTrimSmithingRecipe(Recipe.CommonInfo commonInfo) { super(commonInfo); }

    private boolean isTemplateIngredient(ItemStack stack) {
        return stack.getItem() instanceof GlintTrimItem
                && GlintTrimItem.getPattern(stack) != null
                && GlintTrimItem.getColors(stack).length > 0;
    }

    private boolean isBaseIngredient(ItemStack stack) {
        return !stack.isEmpty()
                && !(stack.getItem() instanceof GlintTrimItem)
                && !(stack.getItem() instanceof GlowTrimItem);
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
            CustomGlint.write(result, pattern, colors, speed, interpolate, GlintTrimItem.getScale(template), simultaneous);
        }
        if (GlintTrimItem.isGlowing(template)) CustomGlint.setGlowing(result, true);
        return result;
    }

    @Override
    public Optional<Ingredient> templateIngredient() {
        return Optional.of(Ingredient.of(CustomGlintMod.GLINT_TRIM.get()));
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
