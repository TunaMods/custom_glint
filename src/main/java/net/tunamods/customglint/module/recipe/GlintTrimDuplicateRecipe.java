package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.Level;

public class GlintTrimDuplicateRecipe extends CustomRecipe {
    private static final GlintTrimDuplicateRecipe INSTANCE = new GlintTrimDuplicateRecipe();
    public static final MapCodec<GlintTrimDuplicateRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, GlintTrimDuplicateRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<GlintTrimDuplicateRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public GlintTrimDuplicateRecipe() {}

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        if (pInv.width() != 3 || pInv.height() != 3) return false;
        for (int i = 0; i < 9; i++) {
            ItemStack s = pInv.getItem(i);
            if (i == 4) {
                if (!(s.getItem() instanceof GlintTrimItem)) return false;
                if (GlintTrimItem.getPattern(s) == null) return false;
                if (GlintTrimItem.getColors(s).length == 0) return false;
            } else if (i == 7) {
                if (!s.is(Items.GLOWSTONE_DUST)) return false;
            } else {
                if (!s.is(Items.DIAMOND)) return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack assemble(CraftingInput pInv) {
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.getItem() instanceof GlintTrimItem) {
                ItemStack result = s.copy();
                result.setCount(2);
                return result;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public RecipeSerializer<? extends CustomRecipe> getSerializer() {
        return SERIALIZER;
    }
}
