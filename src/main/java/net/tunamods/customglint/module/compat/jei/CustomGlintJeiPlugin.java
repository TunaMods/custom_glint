package net.tunamods.customglint.module.compat.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IIngredientAcceptor;
import mezz.jei.api.recipe.category.extensions.vanilla.smithing.ISmithingCategoryExtension;
import mezz.jei.api.recipe.vanilla.IJeiShapedRecipeBuilder;
import mezz.jei.api.recipe.vanilla.IVanillaRecipeFactory;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.ISubtypeRegistration;
import mezz.jei.api.registration.IVanillaCategoryExtensionRegistration;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.ModComponents;
import net.tunamods.customglint.module.item.ModItems;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JEI integration: ingredient-info pages plus hand-built example recipes that preview the glint
 * crafts (tear, dye, merge, layer, black tear, speed, scale, glow trim) and the smithing application.
 *
 * <p>The real custom-serializer recipes don't carry component-aware previews (their ingredients are
 * item-only and would show blank trims), so these synthetic displays exist purely to show players a
 * concrete colored example of each craft. On 26.1 the old {@code getIngredients()}/{@code getResultItem()}
 * override path is gone; previews now ride the {@link SlotDisplay} system. Each slot is fed an
 * {@link ItemStackTemplate} built from a fully glinted stack, so the trim colors and the finished glint
 * render in the slot. Crafting examples use JEI's {@link IVanillaRecipeFactory#createShapedRecipeBuilder}
 * (per-slot {@link SlotDisplay} overrides); smithing examples build a {@link SmithingTransformRecipe}
 * whose result template carries the glint.
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
        // Every Glint Trim is the same Item; its design/colors/glow live in the TRIM config component. Without
        // this, JEI treats all trims as one subtype and collapses the whole design selection (including the
        // trim ingredient-info page below) into a single entry.
        registration.registerFromDataComponentTypes(ModItems.GLINT_TRIM.get(), ModComponents.TRIM.get());
        registration.registerFromDataComponentTypes(ModItems.GLOW_TRIM.get(), ModComponents.GLOW_TRIM.get());
    }

    @Override
    public void registerVanillaCategoryExtensions(IVanillaCategoryExtensionRegistration registration) {
        // JEI's smithing category fills its slots from the recipe's item-only Ingredients, so the template
        // slot would show an undesigned trim. This extension feeds the actual colored trim and glinted result.
        registration.getSmithingCategory().addExtension(GlintSmithingDisplay.class, new GlintSmithingExtension());
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addIngredientInfo(new ItemStack(ModItems.GLINT_WAND.get()), VanillaTypes.ITEM_STACK,
            Component.literal("Right-click to open the Glint Editor and paint animated enchantment glints onto any item."));
        registration.addIngredientInfo(new ItemStack(ModItems.GLINT_TABLE_ITEM.get()), VanillaTypes.ITEM_STACK,
            Component.literal("Place it down and right-click to open the slot-based trim builder. Store designs, paint them with dyes, set brightness and animation, then print finished Glint Trims to apply at a smithing table."));

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

        IVanillaRecipeFactory factory = registration.getVanillaRecipeFactory();
        List<RecipeHolder<CraftingRecipe>> crafting = new ArrayList<>();
        List<RecipeHolder<SmithingRecipe>> smithing = new ArrayList<>();

        // Tear: tear + colored trim -> same trim in the other render mode.
        addTear(crafting, factory, "jei_tear_sim_0", true,  CustomGlint.WAVE,    new int[]{CustomGlint.RED, CustomGlint.BLUE});
        addTear(crafting, factory, "jei_tear_sim_1", true,  CustomGlint.STRIPES, new int[]{CustomGlint.GREEN, CustomGlint.YELLOW});
        addTear(crafting, factory, "jei_tear_sim_2", true,  CustomGlint.SPARKLE, new int[]{CustomGlint.PURPLE, CustomGlint.MAGENTA, CustomGlint.PINK});
        addTear(crafting, factory, "jei_tear_seq_0", false, CustomGlint.WAVE,    new int[]{CustomGlint.RED, CustomGlint.BLUE});
        addTear(crafting, factory, "jei_tear_seq_1", false, CustomGlint.STRIPES, new int[]{CustomGlint.GREEN, CustomGlint.YELLOW});
        addTear(crafting, factory, "jei_tear_seq_2", false, CustomGlint.SPARKLE, new int[]{CustomGlint.PURPLE, CustomGlint.MAGENTA, CustomGlint.PINK});

        // Dye: blank trim + dye -> trim with that color.
        addDye(crafting, factory, "jei_dye_0", CustomGlint.WAVE,    Items.RED_DYE,    CustomGlint.RED);
        addDye(crafting, factory, "jei_dye_1", CustomGlint.STRIPES, Items.BLUE_DYE,   CustomGlint.BLUE);
        addDye(crafting, factory, "jei_dye_2", CustomGlint.SPARKLE, Items.CYAN_DYE,   CustomGlint.CYAN);
        addDye(crafting, factory, "jei_dye_3", CustomGlint.CRYSTAL, Items.PURPLE_DYE, CustomGlint.PURPLE);
        addDye(crafting, factory, "jei_dye_4", CustomGlint.SWIRL,   Items.LIME_DYE,   CustomGlint.LIME);

        // Merge: several single-color trims -> one multi-color trim.
        int[] mergePalette = { CustomGlint.RED, CustomGlint.BLUE, CustomGlint.CYAN, CustomGlint.YELLOW, CustomGlint.PURPLE, CustomGlint.GREEN };
        for (int n = 2; n <= 6; n++) {
            int[] colors = new int[n];
            System.arraycopy(mergePalette, 0, colors, 0, n);
            addMerge(crafting, factory, "jei_merge_" + (n - 2), CustomGlint.WAVE, colors);
        }

        // Duplicate: one trim -> two of the same trim.
        addDuplicate(crafting, factory, "jei_duplicate_0", CustomGlint.WAVE, CustomGlint.RED);

        // Layer tear: layer tear + two trims -> one two-layer trim.
        addLayer(crafting, factory, "jei_layer_0", CustomGlint.WAVE,    CustomGlint.RED,  CustomGlint.SPARKLE, CustomGlint.BLUE);
        addLayer(crafting, factory, "jei_layer_1", CustomGlint.STRIPES, CustomGlint.LIME, CustomGlint.SWIRL,   CustomGlint.PURPLE);

        // Black tear: black tear + glinted item -> clean item.
        addBlackTear(crafting, factory, "jei_black_0", Items.DIAMOND_SWORD,      CustomGlint.WAVE,    CustomGlint.RED);
        addBlackTear(crafting, factory, "jei_black_1", Items.GOLDEN_CHESTPLATE,  CustomGlint.SPARKLE, CustomGlint.LIGHT_BLUE);
        addBlackTear(crafting, factory, "jei_black_2", Items.BOW,                CustomGlint.STRIPES, CustomGlint.YELLOW);

        // Speed: trim + redstone -> trim with faster animation (count = ticks).
        addAmount(crafting, factory, "jei_speed_2", CustomGlint.WAVE, CustomGlint.ORANGE, Items.REDSTONE, 2, true);
        addAmount(crafting, factory, "jei_speed_5", CustomGlint.WAVE, CustomGlint.ORANGE, Items.REDSTONE, 5, true);

        // Scale: trim + slime ball -> trim with larger pattern (visibly different tiling).
        addAmount(crafting, factory, "jei_scale_1", CustomGlint.SPARKLE, CustomGlint.LIGHT_BLUE, Items.SLIME_BALL, 1, false);
        addAmount(crafting, factory, "jei_scale_3", CustomGlint.SPARKLE, CustomGlint.LIGHT_BLUE, Items.SLIME_BALL, 3, false);
        addAmount(crafting, factory, "jei_scale_6", CustomGlint.SPARKLE, CustomGlint.LIGHT_BLUE, Items.SLIME_BALL, 6, false);

        // Glow trim: trim + glowstone dust -> the glowing variant.
        addGlow(crafting, factory, "jei_glow_0", CustomGlint.WAVE,    CustomGlint.RED);
        addGlow(crafting, factory, "jei_glow_1", CustomGlint.SPARKLE, CustomGlint.LIGHT_BLUE);
        addGlow(crafting, factory, "jei_glow_2", CustomGlint.AURORA,  CustomGlint.YELLOW);

        registration.addRecipes(RecipeTypes.CRAFTING, crafting);

        // Smithing: trim + base item + glowstone dust -> the base item carrying the glint.
        addSmithing(smithing, "jei_smithing_0", CustomGlint.WAVE,    new int[]{CustomGlint.RED},                              Items.DIAMOND_SWORD,      true);
        addSmithing(smithing, "jei_smithing_1", CustomGlint.CRYSTAL, new int[]{CustomGlint.CYAN, CustomGlint.LIGHT_BLUE},     Items.DIAMOND_CHESTPLATE, true);
        addSmithing(smithing, "jei_smithing_2", CustomGlint.AURORA,  new int[]{CustomGlint.ORANGE, CustomGlint.YELLOW},       Items.BOW,                true);
        addSmithing(smithing, "jei_smithing_3", CustomGlint.SWIRL,   new int[]{CustomGlint.RED, CustomGlint.LIME, CustomGlint.BLUE}, Items.ELYTRA,       false);
        addSmithing(smithing, "jei_smithing_4", CustomGlint.VANILLA, new int[]{CustomGlint.ORANGE},                           Items.ENCHANTED_BOOK,     true);

        registration.addRecipes(RecipeTypes.SMITHING, smithing);
    }

    // ---- builders -------------------------------------------------------------------------------

    private void addTear(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                         boolean simultaneous, Identifier design, int[] colors) {
        // A tear flips the render mode: the simultaneous tear takes a sequential trim and outputs a
        // simultaneous one (and vice versa). Input is the opposite mode, output is the tear's mode.
        ItemStack tear = (simultaneous ? ModItems.GLINT_TEAR_SIMULTANEOUS : ModItems.GLINT_TEAR_SEQUENTIAL).get().getDefaultInstance();
        craft(out, f, id, trimMode(design, colors, simultaneous), tear, trimMode(design, colors, !simultaneous));
    }

    private void addDye(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                        Identifier design, Item dye, int color) {
        craft(out, f, id, trim(design, new int[]{color}), trim(design, new int[0]), new ItemStack(dye));
    }

    private void addMerge(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                          Identifier design, int[] colors) {
        ItemStack[] inputs = new ItemStack[colors.length];
        for (int i = 0; i < colors.length; i++) inputs[i] = trim(design, new int[]{colors[i]});
        craft(out, f, id, trim(design, colors), inputs);
    }

    private void addDuplicate(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                              Identifier design, int color) {
        // Mirrors GlintTrimDuplicateRecipe: trim in the center, glowstone dust below it, diamonds elsewhere.
        ItemStack trim = trim(design, new int[]{color});
        ItemStack result = trim.copy();
        result.setCount(2);
        ItemStack d = new ItemStack(Items.DIAMOND);
        ItemStack g = new ItemStack(Items.GLOWSTONE_DUST);
        craft(out, f, id, result, d, d, d, d, trim, d, d, g, d);
    }

    private void addLayer(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                          Identifier d1, int c1, Identifier d2, int c2) {
        // Mirrors GlintLayerTearRecipe.assemble: copy the first trim (keeping its config/colors/tooltip) and
        // overwrite its glint Data with both trims' layers combined. Building a bare trim instead leaves an
        // empty TrimConfig, which renders static and tooltips as "no color or data".
        ItemStack trim1 = trim(d1, new int[]{c1});
        ItemStack trim2 = trim(d2, new int[]{c2});
        CustomGlint.Data data1 = CustomGlint.read(trim1);
        CustomGlint.Data data2 = CustomGlint.read(trim2);
        int total = Math.min(data1.layers().length + data2.layers().length, 8);
        CustomGlint.Layer[] combined = new CustomGlint.Layer[total];
        int fromD1 = Math.min(data1.layers().length, total);
        System.arraycopy(data1.layers(), 0, combined, 0, fromD1);
        int fromD2 = total - fromD1;
        if (fromD2 > 0) System.arraycopy(data2.layers(), 0, combined, fromD1, fromD2);
        ItemStack result = trim1.copy();
        result.setCount(1);
        CustomGlint.write(result, combined);
        craft(out, f, id, result, ModItems.GLINT_LAYER_TEAR.get().getDefaultInstance(), trim1, trim2);
    }

    private void addBlackTear(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                              Item base, Identifier design, int color) {
        ItemStack glinted = CustomGlint.glinted(base, design, new int[]{color});
        craft(out, f, id, new ItemStack(base), ModItems.GLINT_BLACK_TEAR.get().getDefaultInstance(), glinted);
    }

    private void addAmount(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                           Identifier design, int color, Item reagent, int count, boolean speed) {
        ItemStack result = trim(design, new int[]{color});
        if (speed) GlintTrimItem.setSpeed(result, (float) count);
        else GlintTrimItem.setScale(result, count * 0.5f);
        ItemStack[] inputs = new ItemStack[count + 1];
        inputs[0] = trim(design, new int[]{color});
        for (int i = 0; i < count; i++) inputs[i + 1] = new ItemStack(reagent);
        craft(out, f, id, result, inputs);
    }

    private void addGlow(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                         Identifier design, int color) {
        ItemStack result = trim(design, new int[]{color});
        GlintTrimItem.setGlowing(result, true);
        craft(out, f, id, result, trim(design, new int[]{color}), new ItemStack(Items.GLOWSTONE_DUST));
    }

    private void addSmithing(List<RecipeHolder<SmithingRecipe>> out, String id,
                             Identifier design, int[] colors, Item base, boolean simultaneous) {
        ItemStack trim = trim(design, colors);
        ItemStack result = new ItemStack(base);
        CustomGlint.write(result, design, colors, 1.0f, true, 1.0f, simultaneous);
        out.add(new RecipeHolder<SmithingRecipe>(key(id), new GlintSmithingDisplay(trim, base, result)));
    }

    /**
     * Smithing display carrying the colored trim and glinted result for the {@link GlintSmithingExtension}
     * to render. It is a real {@link SmithingTransformRecipe} so JEI routes it through the smithing category;
     * the extension overrides the slots, since the category otherwise fills them from item-only Ingredients.
     */
    private static final class GlintSmithingDisplay extends SmithingTransformRecipe {
        private final ItemStack trimStack;
        private final Item baseItem;
        private final ItemStack resultStack;

        GlintSmithingDisplay(ItemStack trimStack, Item baseItem, ItemStack resultStack) {
            super(new Recipe.CommonInfo(true),
                Optional.of(Ingredient.of(ModItems.GLINT_TRIM.get())),
                Ingredient.of(baseItem),
                Optional.of(Ingredient.of(Items.GLOWSTONE_DUST)),
                ItemStackTemplate.fromNonEmptyStack(resultStack));
            this.trimStack = trimStack;
            this.baseItem = baseItem;
            this.resultStack = resultStack;
        }
    }

    /** Feeds the colored trim into the template slot and the glinted item into the output slot, instead of
     *  the item-only Ingredients the default smithing category would render. */
    private static final class GlintSmithingExtension implements ISmithingCategoryExtension<GlintSmithingDisplay> {
        @Override
        public <T extends IIngredientAcceptor<T>> void setTemplate(GlintSmithingDisplay recipe, T acceptor) {
            acceptor.add(recipe.trimStack);
        }

        @Override
        public <T extends IIngredientAcceptor<T>> void setBase(GlintSmithingDisplay recipe, T acceptor) {
            acceptor.add(new ItemStack(recipe.baseItem));
        }

        @Override
        public <T extends IIngredientAcceptor<T>> void setAddition(GlintSmithingDisplay recipe, T acceptor) {
            acceptor.add(new ItemStack(Items.GLOWSTONE_DUST));
        }

        @Override
        public <T extends IIngredientAcceptor<T>> void setOutput(GlintSmithingDisplay recipe, T acceptor) {
            acceptor.add(recipe.resultStack);
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** A glinted Glint Trim stack for previews (pattern then colors so the preview glint is rewritten). */
    private static ItemStack trim(Identifier design, int[] colors) {
        ItemStack s = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(s, design);
        if (colors.length > 0) GlintTrimItem.setColors(s, colors);
        return s;
    }

    /** A Glint Trim in the given render mode. Mirrors GlintTearApplyRecipe: build a (sequential) trim, then
     *  rewrite its layers with the requested simultaneous flag, which the standard setters never set true. */
    private static ItemStack trimMode(Identifier design, int[] colors, boolean simultaneous) {
        ItemStack s = trim(design, colors);
        CustomGlint.Data data = CustomGlint.read(s);
        if (data != null) {
            CustomGlint.Layer[] src = data.layers();
            CustomGlint.Layer[] out = new CustomGlint.Layer[src.length];
            for (int i = 0; i < src.length; i++) {
                CustomGlint.Layer l = src[i];
                out[i] = new CustomGlint.Layer(l.design(), l.colors(), l.speed(), l.interpolate(), l.patternScale(),
                    simultaneous, l.scrollDir(), l.scrollOffset(), l.seed());
            }
            CustomGlint.write(s, out);
        }
        return s;
    }

    /** A component-carrying slot display, so the glint renders in the JEI slot instead of a blank item. */
    private static SlotDisplay display(ItemStack stack) {
        return new SlotDisplay.ItemStackSlotDisplay(ItemStackTemplate.fromNonEmptyStack(stack));
    }

    private static ResourceKey<Recipe<?>> key(String id) {
        return ResourceKey.create(Registries.RECIPE, CustomGlint.res(id));
    }

    /**
     * Build a shaped crafting display laid out row-major in a 3-wide grid. Each input gets its own
     * pattern key and a {@link SlotDisplay} carrying its full stack, so glinted trims show their colors.
     */
    private void craft(List<RecipeHolder<CraftingRecipe>> out, IVanillaRecipeFactory f, String id,
                       ItemStack result, ItemStack... inputs) {
        IJeiShapedRecipeBuilder builder = f.createShapedRecipeBuilder(CraftingBookCategory.MISC, display(result));
        int cols = Math.min(3, inputs.length);
        int rows = (inputs.length + cols - 1) / cols;
        int idx = 0;
        for (int r = 0; r < rows; r++) {
            StringBuilder row = new StringBuilder();
            for (int c = 0; c < cols; c++) {
                if (idx < inputs.length) {
                    char ch = (char) ('A' + idx);
                    builder.define(ch, Ingredient.of(inputs[idx].getItem()), display(inputs[idx]));
                    row.append(ch);
                    idx++;
                } else {
                    row.append(' ');
                }
            }
            builder.pattern(row.toString());
        }
        out.add(new RecipeHolder<CraftingRecipe>(key(id), builder.build()));
    }
}
