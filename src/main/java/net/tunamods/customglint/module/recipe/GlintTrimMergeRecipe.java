package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.Level;

public class GlintTrimMergeRecipe extends CustomRecipe {
    private static final GlintTrimMergeRecipe INSTANCE = new GlintTrimMergeRecipe();
    public static final MapCodec<GlintTrimMergeRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, GlintTrimMergeRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<GlintTrimMergeRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public GlintTrimMergeRecipe() {}

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        int trimCount = 0;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (!(s.getItem() instanceof GlintTrimItem)) return false;
            if (GlintTrimItem.getPattern(s) == null) return false;
            if (GlintTrimItem.getColors(s).length == 0) return false;
            trimCount++;
        }
        return trimCount >= 2;
    }

    @Override
    public ItemStack assemble(CraftingInput pInv) {
        ItemStack result = ItemStack.EMPTY;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (result.isEmpty()) { result = s; continue; }
            result = GlintTrimItem.mergeColors(result, s);
        }
        return result;
    }

    
    @Override
    public boolean isSpecial() { return true; }

    
    
    @Override
    public RecipeSerializer<? extends CustomRecipe> getSerializer() {
        return SERIALIZER;
    }
}
