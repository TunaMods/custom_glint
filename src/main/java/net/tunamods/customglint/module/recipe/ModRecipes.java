package net.tunamods.customglint.module.recipe;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.tunamods.customglint.CustomGlintMod;

public final class ModRecipes {
    private ModRecipes() {}

    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, CustomGlintMod.MOD_ID);

    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintTearApplyRecipe>> GLINT_TEAR_APPLY_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_tear_apply", () -> GlintTearApplyRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintTrimDyeRecipe>> GLINT_TRIM_DYE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_dye", () -> GlintTrimDyeRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintTrimDuplicateRecipe>> GLINT_TRIM_DUPLICATE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_duplicate", () -> GlintTrimDuplicateRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintTrimBlankDuplicateRecipe>> GLINT_TRIM_BLANK_DUPLICATE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_blank_duplicate", () -> GlintTrimBlankDuplicateRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintTrimMergeRecipe>> GLINT_TRIM_MERGE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_merge", () -> GlintTrimMergeRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintTrimSmithingRecipe>> GLINT_TRIM_SMITHING_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_smithing", () -> GlintTrimSmithingRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintLayerTearRecipe>> GLINT_LAYER_TEAR_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_layer_tear", () -> GlintLayerTearRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintBlackTearRecipe>> GLINT_BLACK_TEAR_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_black_tear", () -> GlintBlackTearRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintTrimSpeedRecipe>> GLINT_TRIM_SPEED_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_speed", () -> GlintTrimSpeedRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintTrimScaleRecipe>> GLINT_TRIM_SCALE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_scale", () -> GlintTrimScaleRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintTrimAlphaRecipe>> GLINT_TRIM_ALPHA_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_alpha", () -> GlintTrimAlphaRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintGlowTrimRecipe>> GLINT_GLOW_TRIM_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_glow_trim", () -> GlintGlowTrimRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlowTrimDyeRecipe>> GLOW_TRIM_DYE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glow_trim_dye", () -> GlowTrimDyeRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlowTrimMergeRecipe>> GLOW_TRIM_MERGE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glow_trim_merge", () -> GlowTrimMergeRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlowTrimSmithingRecipe>> GLOW_TRIM_SMITHING_SERIALIZER =
            RECIPE_SERIALIZERS.register("glow_trim_smithing", () -> GlowTrimSmithingRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<TrimPowderRecipe>> TRIM_POWDER_CRAFT_SERIALIZER =
            RECIPE_SERIALIZERS.register("trim_powder_craft", () -> TrimPowderRecipe.SERIALIZER);

    public static void register(IEventBus bus) {
        RECIPE_SERIALIZERS.register(bus);
    }
}
