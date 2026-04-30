package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import com.google.gson.JsonObject;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.level.Level;

public class GlintTrimSmithingRecipe implements SmithingRecipe {
    public static final Serializer SERIALIZER = new Serializer();

    private final ResourceLocation id;

    public GlintTrimSmithingRecipe(ResourceLocation id) {
        this.id = id;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public boolean isTemplateIngredient(ItemStack stack) {
        return stack.getItem() instanceof GlintTrimItem
                && GlintTrimItem.getPattern(stack) != null
                && GlintTrimItem.getColors(stack).length > 0;
    }

    @Override
    public boolean isBaseIngredient(ItemStack stack) {
        return !stack.isEmpty();
    }

    @Override
    public boolean isAdditionIngredient(ItemStack stack) {
        return stack.is(Items.GLOWSTONE_DUST);
    }

    @Override
    public boolean matches(Container pContainer, Level pLevel) {
        return isTemplateIngredient(pContainer.getItem(0))
                && isBaseIngredient(pContainer.getItem(1))
                && isAdditionIngredient(pContainer.getItem(2));
    }

    @Override
    public ItemStack assemble(Container pContainer, RegistryAccess pRegistryAccess) {
        ItemStack template = pContainer.getItem(0);
        ItemStack base     = pContainer.getItem(1);
        ResourceLocation pattern = GlintTrimItem.getPattern(template);
        int[] colors             = GlintTrimItem.getColors(template);
        if (pattern == null || colors.length == 0) return ItemStack.EMPTY;
        ItemStack result = base.copy();
        result.setCount(1);
        CustomGlint.write(result, pattern, colors, 1.0f, true, 1.0f, false);
        return result;
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(Container pContainer) {
        return NonNullList.withSize(pContainer.getContainerSize(), ItemStack.EMPTY);
    }

    @Override
    public ItemStack getResultItem(RegistryAccess pRegistryAccess) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canCraftInDimensions(int pWidth, int pHeight) {
        return true;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }

    @Override
    public RecipeType<?> getType() {
        return RecipeType.SMITHING;
    }

    public static class Serializer implements RecipeSerializer<GlintTrimSmithingRecipe> {
        @Override
        public GlintTrimSmithingRecipe fromJson(ResourceLocation pRecipeId, JsonObject pSerializedRecipe) {
            return new GlintTrimSmithingRecipe(pRecipeId);
        }

        @Override
        public GlintTrimSmithingRecipe fromNetwork(ResourceLocation pRecipeId, FriendlyByteBuf pBuffer) {
            return new GlintTrimSmithingRecipe(pRecipeId);
        }

        @Override
        public void toNetwork(FriendlyByteBuf pBuffer, GlintTrimSmithingRecipe pRecipe) {
        }
    }
}
