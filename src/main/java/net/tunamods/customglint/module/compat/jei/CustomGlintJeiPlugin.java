package net.tunamods.customglint.module.compat.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.ISubtypeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI integration: ingredient-info pages for the wand, trims, and tears.
 *
 * <p>The mod's real crafting and smithing recipes appear in JEI automatically from datapack
 * registration. The old hand-built "display" example recipes are intentionally not carried over to
 * 26.1: the recipe rework removed the hooks they relied on ({@code getIngredients()}/
 * {@code getResultItem()}; preview now comes from {@code Recipe.display()}) and the curated paired
 * previews weren't worth rebuilding against the JEI 29 display API. The 1.21.1 plugin is in git
 * history (working-1.21.1 branch) if they're ever wanted back.
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
        registration.addIngredientInfo(new ItemStack(ModItems.GLINT_WAND.get()), VanillaTypes.ITEM_STACK,
            Component.literal("Right-click to open the Glint Editor and paint animated enchantment glints onto any item."));

        List<ItemStack> trimVariants = new ArrayList<>();
        for (String patternName : GlintTrimItem.PATTERNS) {
            ItemStack trimVariant = new ItemStack(ModItems.GLINT_TRIM.get());
            Identifier patternRl = CustomGlint.designFromName(patternName); // handles vanilla + chromatic sentinels
            GlintTrimItem.setPattern(trimVariant, patternRl);
            trimVariants.add(trimVariant);
        }
        registration.addIngredientInfo(trimVariants, VanillaTypes.ITEM_STACK,
            Component.literal("Smithing template carrying a glint design. Craft with dyes to add colors, then apply to any item with Glowstone Dust at a smithing table."));
        registration.addIngredientInfo(ModItems.GLINT_TEAR_SIMULTANEOUS.get().getDefaultInstance(), VanillaTypes.ITEM_STACK,
            Component.literal("Craft with any glinted item to set all layers to Simultaneous mode, all colors render at once."));
        registration.addIngredientInfo(ModItems.GLINT_TEAR_SEQUENTIAL.get().getDefaultInstance(), VanillaTypes.ITEM_STACK,
            Component.literal("Craft with any glinted item to set all layers to Sequential mode, colors cycle one at a time."));
        registration.addIngredientInfo(ModItems.GLINT_LAYER_TEAR.get().getDefaultInstance(), VanillaTypes.ITEM_STACK,
            Component.literal("Craft with two Glint Trims to merge their layer arrays into a single multi-layer trim (up to 8 layers)."));
        registration.addIngredientInfo(ModItems.GLINT_BLACK_TEAR.get().getDefaultInstance(), VanillaTypes.ITEM_STACK,
            Component.literal("Craft with any glinted item to strip all glint data from it."));
    }
}
