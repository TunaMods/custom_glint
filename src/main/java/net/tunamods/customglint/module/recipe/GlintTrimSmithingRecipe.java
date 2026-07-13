package net.tunamods.customglint.module.recipe;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.level.Level;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;
import net.tunamods.customglint.module.item.ModItems;

public class GlintTrimSmithingRecipe implements SmithingRecipe {
    public static final Serializer SERIALIZER = new Serializer();

    public GlintTrimSmithingRecipe() {}

    @Override
    public boolean isTemplateIngredient(ItemStack stack) {
        return stack.getItem() instanceof GlintTrimItem
                && GlintTrimItem.getPattern(stack) != null
                && GlintTrimItem.getColors(stack).length > 0;
    }

    @Override
    public boolean isBaseIngredient(ItemStack stack) {
        // Glowstone is the addition, never the base. Excluding it here lets the smithing menu's shift-click
        // router fall through to the addition slot instead of short-circuiting on the (occupied) base slot.
        return !stack.isEmpty()
                && !stack.is(Items.GLOWSTONE_DUST)
                && !(stack.getItem() instanceof GlintTrimItem)
                && !(stack.getItem() instanceof GlowTrimItem);
    }

    @Override
    public boolean isAdditionIngredient(ItemStack stack) {
        return stack.is(Items.GLOWSTONE_DUST);
    }

    @Override
    public boolean matches(SmithingRecipeInput pInput, Level pLevel) {
        return isTemplateIngredient(pInput.getItem(0))
                && isBaseIngredient(pInput.getItem(1))
                && isAdditionIngredient(pInput.getItem(2));
    }

    @Override
    public ItemStack assemble(SmithingRecipeInput pInput, HolderLookup.Provider pRegistryAccess) {
        ItemStack template = pInput.getItem(0);
        ItemStack base     = pInput.getItem(1);
        ResourceLocation pattern = GlintTrimItem.getPattern(template);
        int[] colors             = GlintTrimItem.getColors(template);
        if (pattern == null || colors.length == 0) return ItemStack.EMPTY;
        CustomGlint.Data preview = CustomGlint.read(template);
        boolean simultaneous = preview != null && preview.layers().length > 0 && preview.layers()[0].simultaneous();
        float speed          = preview != null && preview.layers().length > 0 ? preview.layers()[0].speed() : 1.0f;
        boolean interpolate  = preview == null || preview.layers().length == 0 || preview.layers()[0].interpolate();
        ItemStack result = base.copy();
        result.setCount(1);
        // Reuse the trim's preview layers whenever it has any, so each layer's scrollDir/scrollOffset/seed
        // carry onto the smithed item. Only fall back to a freshly-built layer when the preview is absent.
        CustomGlint.Layer[] layers = (preview != null && preview.layers().length >= 1)
                ? preview.layers()
                : new CustomGlint.Layer[]{ new CustomGlint.Layer(pattern, colors, speed, interpolate,
                        GlintTrimItem.getScale(template), simultaneous) };
        // Commit a stable oil-slick seed into any unseeded chromatic layer here (the trim/preview carry seed 0).
        // Smithing recomputes the result only when the input slots change, so this is a one-shot commit (the
        // table's print-time equivalent), not a per-frame re-roll that would flicker the pattern.
        CustomGlint.write(result, CustomGlint.ensureChromaticSeeds(layers));
        if (GlintTrimItem.isGlowing(template)) CustomGlint.setGlowing(result, true);
        return result;
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        ItemStack trimExample = new ItemStack(ModItems.GLINT_TRIM.get());
        GlintTrimItem.setPattern(trimExample, CustomGlint.res("textures/glint/wave.png"));
        GlintTrimItem.addColor(trimExample, 0xFFFF0000);
        list.add(Ingredient.of(trimExample));
        list.add(Ingredient.of(Items.DIAMOND_SWORD, Items.DIAMOND_CHESTPLATE, Items.BOW, Items.BOOK, Items.ELYTRA));
        list.add(Ingredient.of(Items.GLOWSTONE_DUST));
        return list;
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(SmithingRecipeInput pInput) {
        return NonNullList.withSize(pInput.size(), ItemStack.EMPTY);
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider pRegistryAccess) {
        return CustomGlint.glinted(Items.DIAMOND_SWORD, CustomGlint.res("textures/glint/wave.png"), new int[]{0xFFFF0000});
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }

    public static class Serializer implements RecipeSerializer<GlintTrimSmithingRecipe> {
        // Stateless recipe: share ONE instance between both codecs. StreamCodec.unit validates on encode that
        // the value == the captured instance (identity, no equals override), so the map codec must decode that
        // SAME singleton, not a fresh `new` each load; otherwise recipe sync throws "Can't encode ...".
        private static final GlintTrimSmithingRecipe INSTANCE = new GlintTrimSmithingRecipe();
        private static final MapCodec<GlintTrimSmithingRecipe> CODEC =
                MapCodec.unit(INSTANCE);
        private static final StreamCodec<RegistryFriendlyByteBuf, GlintTrimSmithingRecipe> STREAM_CODEC =
                StreamCodec.unit(INSTANCE);

        @Override
        public MapCodec<GlintTrimSmithingRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, GlintTrimSmithingRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
