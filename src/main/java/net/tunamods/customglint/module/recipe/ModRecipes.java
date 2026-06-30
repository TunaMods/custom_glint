package net.tunamods.customglint.module.recipe;

import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.tunamods.customglint.CustomGlintMod;

public final class ModRecipes {
    private ModRecipes() {}

    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, CustomGlintMod.MOD_ID);

    public static final RegistryObject<RecipeSerializer<GlintTearApplyRecipe>> GLINT_TEAR_APPLY_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_tear_apply", () -> GlintTearApplyRecipe.SERIALIZER);
    public static final RegistryObject<RecipeSerializer<GlintTrimDyeRecipe>> GLINT_TRIM_DYE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_dye", () -> GlintTrimDyeRecipe.SERIALIZER);
    public static final RegistryObject<RecipeSerializer<GlintTrimDuplicateRecipe>> GLINT_TRIM_DUPLICATE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_duplicate", () -> GlintTrimDuplicateRecipe.SERIALIZER);
    public static final RegistryObject<RecipeSerializer<GlintTrimBlankDuplicateRecipe>> GLINT_TRIM_BLANK_DUPLICATE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_blank_duplicate", () -> GlintTrimBlankDuplicateRecipe.SERIALIZER);
    public static final RegistryObject<RecipeSerializer<GlintTrimMergeRecipe>> GLINT_TRIM_MERGE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_merge", () -> GlintTrimMergeRecipe.SERIALIZER);
    public static final RegistryObject<RecipeSerializer<GlintTrimSmithingRecipe>> GLINT_TRIM_SMITHING_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_smithing", () -> GlintTrimSmithingRecipe.SERIALIZER);
    public static final RegistryObject<RecipeSerializer<GlintLayerTearRecipe>> GLINT_LAYER_TEAR_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_layer_tear", () -> GlintLayerTearRecipe.SERIALIZER);
    public static final RegistryObject<RecipeSerializer<GlintBlackTearRecipe>> GLINT_BLACK_TEAR_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_black_tear", () -> GlintBlackTearRecipe.SERIALIZER);
    public static final RegistryObject<RecipeSerializer<GlintTrimSpeedRecipe>> GLINT_TRIM_SPEED_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_speed", () -> GlintTrimSpeedRecipe.SERIALIZER);
    public static final RegistryObject<RecipeSerializer<GlintTrimScaleRecipe>> GLINT_TRIM_SCALE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_scale", () -> GlintTrimScaleRecipe.SERIALIZER);
    public static final RegistryObject<RecipeSerializer<GlintTrimAlphaRecipe>> GLINT_TRIM_ALPHA_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_alpha", () -> GlintTrimAlphaRecipe.SERIALIZER);
    public static final RegistryObject<RecipeSerializer<GlintGlowTrimRecipe>> GLINT_GLOW_TRIM_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_glow_trim", () -> GlintGlowTrimRecipe.SERIALIZER);
    public static final RegistryObject<RecipeSerializer<GlowTrimDyeRecipe>> GLOW_TRIM_DYE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glow_trim_dye", () -> GlowTrimDyeRecipe.SERIALIZER);
    public static final RegistryObject<RecipeSerializer<GlowTrimMergeRecipe>> GLOW_TRIM_MERGE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glow_trim_merge", () -> GlowTrimMergeRecipe.SERIALIZER);
    public static final RegistryObject<RecipeSerializer<GlowTrimSmithingRecipe>> GLOW_TRIM_SMITHING_SERIALIZER =
            RECIPE_SERIALIZERS.register("glow_trim_smithing", () -> GlowTrimSmithingRecipe.SERIALIZER);

    public static void register(IEventBus bus) {
        RECIPE_SERIALIZERS.register(bus);
    }
}
