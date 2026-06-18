package net.tunamods.customglint.module.compat.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.ISubtypeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI integration: ingredient-info pages for the wand, trims, and tears.
 *
 * <p><b>TODO(26.1) — JEI 19→29 migration, synthetic recipe displays dropped.</b> The previous plugin
 * also registered hand-built example "display" recipes that extended the mod's {@code CraftingRecipe}/
 * {@code SmithingTransformRecipe} classes and overrode {@code getIngredients()}/{@code getResultItem()}.
 * 26.1's recipe rework removed those methods (preview now comes from {@code Recipe.display()}), dropped
 * the {@code CraftingBookCategory} {@code CustomRecipe} constructor, changed {@code SmithingTransformRecipe}'s
 * constructor, and made {@code Ingredient.of(...)} ItemLike-only (no NBT stacks) — so those synthetic
 * displays no longer compile, and JEI 29's display API differs (RecipeHolder/IDisplay changes). The mod's
 * real crafting/smithing recipes still auto-appear in JEI from datapack registration; only the curated
 * paired-example previews are gone. The ingredient-info tooltips below are kept (still valid). Re-adding
 * the example displays needs the JEI 29 display API + {@code Recipe.display()}. The 1.21.1 plugin is in
 * git history (working-1.21.1 branch).
 */
@JeiPlugin
public class CustomGlintJeiPlugin implements IModPlugin {

    private static final Identifier UID = CustomGlint.res("jei_plugin");

    @Override
    public Identifier getPluginUid() {
        return UID;
    }

    @Override
    public void registerItemSubtypes(ISubtypeRegistration registration) {
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addIngredientInfo(new ItemStack(CustomGlintMod.GLINT_WAND.get()), VanillaTypes.ITEM_STACK,
            Component.literal("Right-click to open the Glint Editor and paint animated enchantment glints onto any item."));

        List<ItemStack> trimVariants = new ArrayList<>();
        for (String patternName : GlintTrimItem.PATTERNS) {
            ItemStack trimVariant = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
            Identifier patternRl;
            if (patternName.equals("vanilla")) {
                patternRl = CustomGlint.VANILLA;
            } else if (patternName.contains(":")) {
                int c = patternName.indexOf(':');
                patternRl = Identifier.fromNamespaceAndPath(patternName.substring(0, c), "textures/glint/" + patternName.substring(c + 1) + ".png");
            } else {
                patternRl = CustomGlint.res("textures/glint/" + patternName + ".png");
            }
            GlintTrimItem.setPattern(trimVariant, patternRl);
            trimVariants.add(trimVariant);
        }
        registration.addIngredientInfo(trimVariants, VanillaTypes.ITEM_STACK,
            Component.literal("Smithing template carrying a glint design. Craft with dyes to add colors, then apply to any item with Glowstone Dust at a smithing table."));
        registration.addIngredientInfo(CustomGlintMod.GLINT_TEAR_SIMULTANEOUS.get().getDefaultInstance(), VanillaTypes.ITEM_STACK,
            Component.literal("Craft with any glinted item to set all layers to Simultaneous mode — all colors render at once."));
        registration.addIngredientInfo(CustomGlintMod.GLINT_TEAR_SEQUENTIAL.get().getDefaultInstance(), VanillaTypes.ITEM_STACK,
            Component.literal("Craft with any glinted item to set all layers to Sequential mode — colors cycle one at a time."));
        registration.addIngredientInfo(CustomGlintMod.GLINT_LAYER_TEAR.get().getDefaultInstance(), VanillaTypes.ITEM_STACK,
            Component.literal("Craft with two Glint Trims to merge their layer arrays into a single multi-layer trim (up to 8 layers)."));
        registration.addIngredientInfo(CustomGlintMod.GLINT_BLACK_TEAR.get().getDefaultInstance(), VanillaTypes.ITEM_STACK,
            Component.literal("Craft with any glinted item to strip all glint data from it."));
    }
}
