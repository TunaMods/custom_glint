package net.tunamods.customglint.module.recipe;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SimpleSmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.level.Level;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;

import java.util.List;
import java.util.Optional;

/**
 * Shared shape for the two "trim (template) + any base item + Glowstone Dust" smithing crafts. The base and
 * addition slots behave the same for both, so only the template predicate and the assemble step differ.
 *
 * 26.1.2 SmithingRecipe is ingredient/codec based, but these recipes need NBT-predicate matching (a trim
 * carrying colors; any non-trim base). So {@code matches} is overridden with the real predicate logic, while
 * the ingredient accessors return representative items for the recipe-book/JEI display + placement filtering.
 */
public abstract class AbstractTrimSmithingRecipe extends SimpleSmithingRecipe {

    protected AbstractTrimSmithingRecipe(Recipe.CommonInfo commonInfo) {
        super(commonInfo);
    }

    /** Whether the template slot holds a usable trim of this recipe's kind. */
    protected abstract boolean isTemplateIngredient(ItemStack stack);

    /** Anything but a trim can take a glint or a glow. */
    private static boolean isBaseIngredient(ItemStack stack) {
        return !stack.isEmpty()
                && !(stack.getItem() instanceof GlintTrimItem)
                && !(stack.getItem() instanceof GlowTrimItem);
    }

    @Override
    public boolean matches(SmithingRecipeInput pInput, Level pLevel) {
        return isTemplateIngredient(pInput.getItem(0))
                && isBaseIngredient(pInput.getItem(1))
                && pInput.getItem(2).is(Items.GLOWSTONE_DUST);
    }

    @Override
    public Ingredient baseIngredient() {
        // The smithing table's base slot only accepts items that some recipe's base ingredient lists
        // (via RecipePropertySet.SMITHING_BASE). Any item can carry a glint, so advertise every item
        // except the trims themselves so the slot lets anything in. matches() still gates the output.
        return Ingredient.of(BuiltInRegistries.ITEM.stream()
                .filter(i -> i != Items.AIR
                        && !(i instanceof GlintTrimItem)
                        && !(i instanceof GlowTrimItem)));
    }

    @Override
    public Optional<Ingredient> additionIngredient() {
        return Optional.of(Ingredient.of(Items.GLOWSTONE_DUST));
    }

    @Override
    protected PlacementInfo createPlacementInfo() {
        return PlacementInfo.createFromOptionals(List.of(templateIngredient(), Optional.of(baseIngredient()), additionIngredient()));
    }
}
