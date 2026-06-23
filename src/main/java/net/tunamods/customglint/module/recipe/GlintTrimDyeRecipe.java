package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.Level;

public class GlintTrimDyeRecipe extends CustomRecipe {
    private static final GlintTrimDyeRecipe INSTANCE = new GlintTrimDyeRecipe();
    public static final MapCodec<GlintTrimDyeRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, GlintTrimDyeRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<GlintTrimDyeRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public GlintTrimDyeRecipe() {}

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        ItemStack trim = ItemStack.EMPTY;
        ItemStack dye  = ItemStack.EMPTY;
        int filled = 0;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            filled++;
            if (s.getItem() instanceof GlintTrimItem && GlintTrimItem.getPattern(s) != null) {
                if (!trim.isEmpty()) return false;
                trim = s;
            } else if (s.getItem() instanceof DyeItem) {
                if (!dye.isEmpty()) return false;
                dye = s;
            } else {
                return false;
            }
        }
        return filled == 2 && !trim.isEmpty() && !dye.isEmpty();
    }

    @Override
    public ItemStack assemble(CraftingInput pInv) {
        ItemStack trim = ItemStack.EMPTY;
        ItemStack dyeStack = ItemStack.EMPTY;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof GlintTrimItem) trim = s;
            else if (s.getItem() instanceof DyeItem) dyeStack = s;
        }
        DyeColor dyeColor = dyeStack.isEmpty() ? null : dyeStack.get(DataComponents.DYE);
        if (trim.isEmpty() || dyeColor == null) return ItemStack.EMPTY;
        ItemStack result = trim.copy();
        result.setCount(1);
        int[] colors = new int[]{ GlintTrimItem.DYE_COLORS[dyeColor.ordinal()] };
        GlintTrimItem.setColors(result, colors);
        return result;
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public RecipeSerializer<? extends CustomRecipe> getSerializer() {
        return SERIALIZER;
    }
}
