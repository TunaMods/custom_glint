package net.tunamods.customglint.module.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.ISubtypeRegistration;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.recipe.GlintBlackTearRecipe;
import net.tunamods.customglint.module.recipe.GlintLayerTearRecipe;
import net.tunamods.customglint.module.recipe.GlintTearApplyRecipe;
import net.tunamods.customglint.module.recipe.GlintTrimBlankDuplicateRecipe;
import net.tunamods.customglint.module.recipe.GlintTrimDuplicateRecipe;
import net.tunamods.customglint.module.recipe.GlintTrimDyeRecipe;
import net.tunamods.customglint.module.recipe.GlintTrimMergeRecipe;
import net.tunamods.customglint.module.recipe.GlintTrimSpeedRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.tunamods.customglint.module.recipe.GlintTrimScaleRecipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@JeiPlugin
public class CustomGlintJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID = new ResourceLocation("customglint", "jei_plugin");

    // Display-only subclass: isSpecial()=true on the parent suppresses auto-discovery;
    // this class flips it back to false so JEI renders these explicit paired entries.
    private static class TearDisplay extends GlintTearApplyRecipe {
        private final ResourceLocation design;
        private final int[] colors;
        private final boolean simultaneous;

        TearDisplay(ResourceLocation id, ResourceLocation design, int[] colors, boolean simultaneous) {
            super(id, CraftingBookCategory.MISC);
            this.design = design;
            this.colors = colors;
            this.simultaneous = simultaneous;
        }

        @Override
        public boolean isSpecial() { return false; }

        @Override
        public NonNullList<Ingredient> getIngredients() {
            ItemStack trim = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
            GlintTrimItem.setPattern(trim, design);
            for (int col : colors) GlintTrimItem.addColor(trim, col);
            CustomGlint.write(trim, design, colors, 1.0f, true, 1.0f, !simultaneous);
            NonNullList<Ingredient> list = NonNullList.create();
            list.add(Ingredient.of(simultaneous
                ? CustomGlintMod.GLINT_TEAR_SIMULTANEOUS.get().getDefaultInstance()
                : CustomGlintMod.GLINT_TEAR_SEQUENTIAL.get().getDefaultInstance()));
            list.add(Ingredient.of(trim));
            return list;
        }

        @Override
        public ItemStack getResultItem(RegistryAccess pRegistryAccess) {
            ItemStack result = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
            GlintTrimItem.setPattern(result, design);
            for (int col : colors) GlintTrimItem.addColor(result, col);
            CustomGlint.write(result, design, colors, 1.0f, true, 1.0f, simultaneous);
            return result;
        }
    }

    private static class DyeDisplay extends GlintTrimDyeRecipe {
        private final ResourceLocation design;
        private final Item dye;
        private final int dyeColor;

        DyeDisplay(ResourceLocation id, ResourceLocation design, Item dye, int dyeColor) {
            super(id, CraftingBookCategory.MISC);
            this.design = design; this.dye = dye; this.dyeColor = dyeColor;
        }

        @Override public boolean isSpecial() { return false; }

        @Override
        public NonNullList<Ingredient> getIngredients() {
            ItemStack trim = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
            GlintTrimItem.setPattern(trim, design);
            NonNullList<Ingredient> list = NonNullList.create();
            list.add(Ingredient.of(trim));
            list.add(Ingredient.of(dye));
            return list;
        }

        @Override
        public ItemStack getResultItem(RegistryAccess r) {
            ItemStack result = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
            GlintTrimItem.setPattern(result, design);
            GlintTrimItem.addColor(result, dyeColor);
            return result;
        }
    }

    private static class MergeDisplay extends GlintTrimMergeRecipe {
        private final ResourceLocation design;
        private final int[] colors; // one color per input trim

        MergeDisplay(ResourceLocation id, ResourceLocation design, int[] colors) {
            super(id, CraftingBookCategory.MISC);
            this.design = design; this.colors = colors;
        }

        @Override public boolean isSpecial() { return false; }

        @Override
        public NonNullList<Ingredient> getIngredients() {
            NonNullList<Ingredient> list = NonNullList.create();
            for (int color : colors) {
                ItemStack trim = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
                GlintTrimItem.setPattern(trim, design);
                GlintTrimItem.addColor(trim, color);
                list.add(Ingredient.of(trim));
            }
            return list;
        }

        @Override
        public ItemStack getResultItem(RegistryAccess r) {
            ItemStack result = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
            GlintTrimItem.setPattern(result, design);
            for (int color : colors) GlintTrimItem.addColor(result, color);
            return result;
        }
    }

    private static class DuplicateDisplay extends GlintTrimDuplicateRecipe {
        DuplicateDisplay(ResourceLocation id) { super(id, CraftingBookCategory.MISC); }
        @Override public boolean isSpecial() { return false; }
    }

    private static class BlankDuplicateDisplay extends GlintTrimBlankDuplicateRecipe {
        private final ResourceLocation design;

        BlankDuplicateDisplay(ResourceLocation id, ResourceLocation design) {
            super(id, CraftingBookCategory.MISC);
            this.design = design;
        }

        @Override public boolean isSpecial() { return false; }

        @Override
        public NonNullList<Ingredient> getIngredients() {
            ItemStack trim = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
            GlintTrimItem.setPattern(trim, design);
            NonNullList<Ingredient> list = NonNullList.withSize(9, Ingredient.EMPTY);
            for (int i = 0; i < 9; i++) {
                if (i == 4) list.set(i, Ingredient.of(trim));
                else if (i == 7) list.set(i, Ingredient.of(Items.GLOWSTONE_DUST));
                else list.set(i, Ingredient.of(Items.DIAMOND));
            }
            return list;
        }

        @Override
        public ItemStack getResultItem(RegistryAccess r) {
            ItemStack result = new ItemStack(CustomGlintMod.GLINT_TRIM.get(), 2);
            GlintTrimItem.setPattern(result, design);
            return result;
        }
    }

    private static class LayerTearDisplay extends GlintLayerTearRecipe {
        private final ResourceLocation design1;
        private final int color1;
        private final ResourceLocation design2;
        private final int color2;

        LayerTearDisplay(ResourceLocation id, ResourceLocation d1, int c1, ResourceLocation d2, int c2) {
            super(id, CraftingBookCategory.MISC);
            this.design1 = d1; this.color1 = c1; this.design2 = d2; this.color2 = c2;
        }

        @Override public boolean isSpecial() { return false; }

        @Override
        public NonNullList<Ingredient> getIngredients() {
            ItemStack t1 = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
            GlintTrimItem.setPattern(t1, design1);
            GlintTrimItem.addColor(t1, color1);
            ItemStack t2 = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
            GlintTrimItem.setPattern(t2, design2);
            GlintTrimItem.addColor(t2, color2);
            NonNullList<Ingredient> list = NonNullList.create();
            list.add(Ingredient.of(CustomGlintMod.GLINT_LAYER_TEAR.get().getDefaultInstance()));
            list.add(Ingredient.of(t1));
            list.add(Ingredient.of(t2));
            return list;
        }

        @Override
        public ItemStack getResultItem(RegistryAccess r) {
            ItemStack result = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
            GlintTrimItem.setPattern(result, design1);
            GlintTrimItem.addColor(result, color1);
            CustomGlint.Layer l1 = new CustomGlint.Layer(design1, new int[]{color1}, 1.0f, true, 1.0f, false);
            CustomGlint.Layer l2 = new CustomGlint.Layer(design2, new int[]{color2}, 1.0f, true, 1.0f, false);
            CustomGlint.write(result, new CustomGlint.Layer[]{l1, l2});
            return result;
        }
    }

    private static class SpeedDisplay extends GlintTrimSpeedRecipe {
        private final ResourceLocation design;
        private final int color;
        private final int count;

        SpeedDisplay(ResourceLocation id, ResourceLocation design, int color, int count) {
            super(id, CraftingBookCategory.MISC);
            this.design = design; this.color = color; this.count = count;
        }

        @Override public boolean isSpecial() { return false; }

        @Override
        public NonNullList<Ingredient> getIngredients() {
            ItemStack trim = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
            GlintTrimItem.setPattern(trim, design);
            GlintTrimItem.addColor(trim, color);
            NonNullList<Ingredient> list = NonNullList.create();
            list.add(Ingredient.of(trim));
            for (int i = 0; i < count; i++) list.add(Ingredient.of(Items.REDSTONE));
            return list;
        }

        @Override
        public ItemStack getResultItem(RegistryAccess r) {
            ItemStack result = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
            GlintTrimItem.setPattern(result, design);
            GlintTrimItem.addColor(result, color);
            GlintTrimItem.setSpeed(result, (float) count);
            return result;
        }
    }

    private static class ScaleDisplay extends GlintTrimScaleRecipe {
        private final ResourceLocation design;
        private final int color;
        private final int count;

        ScaleDisplay(ResourceLocation id, ResourceLocation design, int color, int count) {
            super(id, CraftingBookCategory.MISC);
            this.design = design; this.color = color; this.count = count;
        }

        @Override public boolean isSpecial() { return false; }

        @Override
        public NonNullList<Ingredient> getIngredients() {
            ItemStack trim = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
            GlintTrimItem.setPattern(trim, design);
            GlintTrimItem.addColor(trim, color);
            NonNullList<Ingredient> list = NonNullList.create();
            list.add(Ingredient.of(trim));
            for (int i = 0; i < count; i++) list.add(Ingredient.of(Items.SLIME_BALL));
            return list;
        }

        @Override
        public ItemStack getResultItem(RegistryAccess r) {
            ItemStack result = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
            GlintTrimItem.setPattern(result, design);
            GlintTrimItem.addColor(result, color);
            GlintTrimItem.setScale(result, count * 0.5f);
            return result;
        }
    }

    private static class BlackTearDisplay extends GlintBlackTearRecipe {
        private final ItemStack glinted;

        BlackTearDisplay(ResourceLocation id, ItemStack glinted) {
            super(id, CraftingBookCategory.MISC);
            this.glinted = glinted;
        }

        @Override public boolean isSpecial() { return false; }

        @Override
        public NonNullList<Ingredient> getIngredients() {
            NonNullList<Ingredient> list = NonNullList.create();
            list.add(Ingredient.of(CustomGlintMod.GLINT_BLACK_TEAR.get().getDefaultInstance()));
            list.add(Ingredient.of(glinted));
            return list;
        }

        @Override
        public ItemStack getResultItem(RegistryAccess r) {
            ItemStack result = glinted.copy();
            CustomGlint.remove(result);
            return result;
        }
    }

    private static class SmithingDisplay extends SmithingTransformRecipe {
        private final ItemStack glintedResult;

        SmithingDisplay(ResourceLocation id, ItemStack trim, ItemStack glintedResult) {
            super(id, Ingredient.of(trim), Ingredient.of(glintedResult.getItem()), Ingredient.of(Items.GLOWSTONE_DUST), glintedResult);
            this.glintedResult = glintedResult;
        }

        @Override public boolean isSpecial() { return false; }

        @Override
        public ItemStack getResultItem(RegistryAccess r) {
            return glintedResult;
        }
    }

    @Override
    public ResourceLocation getPluginUid() {
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
            ResourceLocation patternRl = patternName.equals("vanilla")
                ? CustomGlint.VANILLA
                : new ResourceLocation("customglint", "textures/glint/" + patternName + ".png");
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

        ResourceLocation wave    = new ResourceLocation("customglint", "textures/glint/wave.png");
        ResourceLocation stripes = new ResourceLocation("customglint", "textures/glint/stripes.png");
        ResourceLocation sparkle = new ResourceLocation("customglint", "textures/glint/sparkle.png");
        ResourceLocation vanilla    = CustomGlint.VANILLA;
        ResourceLocation crystal = new ResourceLocation("customglint", "textures/glint/crystal.png");
        ResourceLocation swirl   = new ResourceLocation("customglint", "textures/glint/swirl.png");

        List<CraftingRecipe> tearDisplays = new ArrayList<>();
        for (boolean sim : new boolean[]{false, true}) {
            tearDisplays.add(new TearDisplay(new ResourceLocation("customglint", "jei_tear_" + (sim ? "sim" : "seq") + "_0"), wave,    new int[]{0xFFFF0000, 0xFF0000FF}, sim));
            tearDisplays.add(new TearDisplay(new ResourceLocation("customglint", "jei_tear_" + (sim ? "sim" : "seq") + "_1"), stripes, new int[]{0xFF00FF00, 0xFFFFFF00}, sim));
            tearDisplays.add(new TearDisplay(new ResourceLocation("customglint", "jei_tear_" + (sim ? "sim" : "seq") + "_2"), sparkle, new int[]{0xFF8800CC, 0xFFFF00FF, 0xFFFF80A0}, sim));
            tearDisplays.add(new TearDisplay(new ResourceLocation("customglint", "jei_tear_" + (sim ? "sim" : "seq") + "_3"), vanilla,    new int[]{0xFFFF0000, 0xFFFF8000, 0xFFFFFF00, 0xFF00FF00}, sim));
            tearDisplays.add(new TearDisplay(new ResourceLocation("customglint", "jei_tear_" + (sim ? "sim" : "seq") + "_4"), crystal, new int[]{0xFF00FFFF, 0xFF00AAFF, 0xFF0000FF, 0xFF8800CC, 0xFFFF80A0}, sim));
            tearDisplays.add(new TearDisplay(new ResourceLocation("customglint", "jei_tear_" + (sim ? "sim" : "seq") + "_5"), swirl,   new int[]{0xFFFF0000, 0xFFFF8000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFF8800CC, 0xFFFF80A0}, sim));
        }
        registration.addRecipes(RecipeTypes.CRAFTING, tearDisplays);

        List<CraftingRecipe> dyeDisplays = new ArrayList<>();
        dyeDisplays.add(new DyeDisplay(new ResourceLocation("customglint", "jei_dye_0"), wave,    Items.RED_DYE,    GlintTrimItem.DYE_COLORS[14]));
        dyeDisplays.add(new DyeDisplay(new ResourceLocation("customglint", "jei_dye_1"), stripes, Items.BLUE_DYE,   GlintTrimItem.DYE_COLORS[11]));
        dyeDisplays.add(new DyeDisplay(new ResourceLocation("customglint", "jei_dye_2"), sparkle, Items.CYAN_DYE,   GlintTrimItem.DYE_COLORS[9]));
        dyeDisplays.add(new DyeDisplay(new ResourceLocation("customglint", "jei_dye_3"), vanilla,    Items.YELLOW_DYE, GlintTrimItem.DYE_COLORS[4]));
        dyeDisplays.add(new DyeDisplay(new ResourceLocation("customglint", "jei_dye_4"), crystal, Items.PURPLE_DYE, GlintTrimItem.DYE_COLORS[10]));
        dyeDisplays.add(new DyeDisplay(new ResourceLocation("customglint", "jei_dye_5"), swirl,   Items.LIME_DYE,   GlintTrimItem.DYE_COLORS[5]));
        registration.addRecipes(RecipeTypes.CRAFTING, dyeDisplays);

        int[] mergeColors = { 0xFFFF0000, 0xFF0000FF, 0xFF00FFFF, 0xFFFFFF00, 0xFF8800CC, 0xFF00FF00, 0xFFFF8000, 0xFFFF80A0 };
        List<CraftingRecipe> mergeDisplays = new ArrayList<>();
        for (int n = 2; n <= 8; n++) {
            mergeDisplays.add(new MergeDisplay(
                new ResourceLocation("customglint", "jei_merge_" + (n - 2)),
                wave, Arrays.copyOfRange(mergeColors, 0, n)
            ));
        }
        registration.addRecipes(RecipeTypes.CRAFTING, mergeDisplays);

        List<CraftingRecipe> duplicateDisplays = new ArrayList<>();
        duplicateDisplays.add(new DuplicateDisplay(new ResourceLocation("customglint", "jei_duplicate_0")));
        duplicateDisplays.add(new BlankDuplicateDisplay(new ResourceLocation("customglint", "jei_duplicate_1"), wave));
        registration.addRecipes(RecipeTypes.CRAFTING, duplicateDisplays);

        List<CraftingRecipe> layerTearDisplays = new ArrayList<>();
        layerTearDisplays.add(new LayerTearDisplay(new ResourceLocation("customglint", "jei_layer_0"), wave,    0xFFFF0000, sparkle, 0xFF0000FF));
        layerTearDisplays.add(new LayerTearDisplay(new ResourceLocation("customglint", "jei_layer_1"), vanilla,    0xFFFF8000, crystal, 0xFF00FFFF));
        layerTearDisplays.add(new LayerTearDisplay(new ResourceLocation("customglint", "jei_layer_2"), stripes, 0xFF00FF00, swirl,   0xFF8800CC));
        registration.addRecipes(RecipeTypes.CRAFTING, layerTearDisplays);

        List<CraftingRecipe> blackTearDisplays = new ArrayList<>();
        blackTearDisplays.add(new BlackTearDisplay(new ResourceLocation("customglint", "jei_black_0"), CustomGlint.glinted(Items.DIAMOND_SWORD,    wave,    new int[]{0xFFFF0000})));
        blackTearDisplays.add(new BlackTearDisplay(new ResourceLocation("customglint", "jei_black_1"), CustomGlint.glinted(Items.GOLDEN_CHESTPLATE, sparkle, new int[]{0xFF00AAFF})));
        blackTearDisplays.add(new BlackTearDisplay(new ResourceLocation("customglint", "jei_black_2"), CustomGlint.glinted(Items.BOW,               stripes, new int[]{0xFFFFFF00})));
        blackTearDisplays.add(new BlackTearDisplay(new ResourceLocation("customglint", "jei_black_3"), CustomGlint.glinted(Items.BOOK,              vanilla,    new int[]{0xFF8800CC})));
        registration.addRecipes(RecipeTypes.CRAFTING, blackTearDisplays);

        List<CraftingRecipe> speedDisplays = new ArrayList<>();
        for (int n = 1; n <= 8; n++) {
            speedDisplays.add(new SpeedDisplay(new ResourceLocation("customglint", "jei_speed_" + n), wave, 0xFFFF4400, n));
        }
        registration.addRecipes(RecipeTypes.CRAFTING, speedDisplays);

        List<CraftingRecipe> scaleDisplays = new ArrayList<>();
        for (int n = 1; n <= 8; n++) {
            scaleDisplays.add(new ScaleDisplay(new ResourceLocation("customglint", "jei_scale_" + n), sparkle, 0xFF00AAFF, n));
        }
        registration.addRecipes(RecipeTypes.CRAFTING, scaleDisplays);

        ItemStack st0 = new ItemStack(CustomGlintMod.GLINT_TRIM.get()); GlintTrimItem.setPattern(st0, wave);    GlintTrimItem.addColor(st0, 0xFFFF0000);
        ItemStack st1 = new ItemStack(CustomGlintMod.GLINT_TRIM.get()); GlintTrimItem.setPattern(st1, crystal); GlintTrimItem.addColor(st1, 0xFF00FFFF); GlintTrimItem.addColor(st1, 0xFF00AAFF);
        ItemStack st2 = new ItemStack(CustomGlintMod.GLINT_TRIM.get()); GlintTrimItem.setPattern(st2, sparkle); GlintTrimItem.addColor(st2, 0xFF8800CC); GlintTrimItem.addColor(st2, 0xFFFF80A0);
        ItemStack st3 = new ItemStack(CustomGlintMod.GLINT_TRIM.get()); GlintTrimItem.setPattern(st3, swirl);   GlintTrimItem.addColor(st3, 0xFFFF0000); GlintTrimItem.addColor(st3, 0xFFFFFF00); GlintTrimItem.addColor(st3, 0xFF00FF00); GlintTrimItem.addColor(st3, 0xFF00FFFF); GlintTrimItem.addColor(st3, 0xFF0000FF);
        ItemStack st4 = new ItemStack(CustomGlintMod.GLINT_TRIM.get()); GlintTrimItem.setPattern(st4, vanilla); GlintTrimItem.addColor(st4, 0xFFFFAA00);
        List<SmithingRecipe> smithingDisplays = new ArrayList<>();
        smithingDisplays.add(new SmithingDisplay(new ResourceLocation("customglint", "jei_smithing_0"), st0, CustomGlint.glinted(Items.DIAMOND_SWORD,    wave,    new int[]{0xFFFF0000})));
        smithingDisplays.add(new SmithingDisplay(new ResourceLocation("customglint", "jei_smithing_1"), st1, CustomGlint.glinted(Items.DIAMOND_CHESTPLATE, crystal, new int[]{0xFF00FFFF, 0xFF00AAFF})));
        smithingDisplays.add(new SmithingDisplay(new ResourceLocation("customglint", "jei_smithing_2"), st2, CustomGlint.glinted(Items.BOW,               sparkle, new int[]{0xFF8800CC, 0xFFFF80A0})));
        smithingDisplays.add(new SmithingDisplay(new ResourceLocation("customglint", "jei_smithing_3"), st3, CustomGlint.glinted(Items.ELYTRA,            swirl,   new int[]{0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF})));
        smithingDisplays.add(new SmithingDisplay(new ResourceLocation("customglint", "jei_smithing_4"), st4, CustomGlint.glinted(Items.ENCHANTED_BOOK,    vanilla, new int[]{0xFFFFAA00})));
        registration.addRecipes(RecipeTypes.SMITHING, smithingDisplays);
    }
}
