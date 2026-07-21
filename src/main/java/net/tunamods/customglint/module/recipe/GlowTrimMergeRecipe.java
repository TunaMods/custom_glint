package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.module.item.GlowTrimItem;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.Level;

/** Two or more Glow Trims -> a single Glow Trim with merged colors (cap 8). Mirrors GlintTrimMergeRecipe. */
public class GlowTrimMergeRecipe extends CustomRecipe {
    private static final GlowTrimMergeRecipe INSTANCE = new GlowTrimMergeRecipe();
    public static final MapCodec<GlowTrimMergeRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, GlowTrimMergeRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<GlowTrimMergeRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public GlowTrimMergeRecipe() {}

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        int trimCount = 0;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (!(s.getItem() instanceof GlowTrimItem)) return false;
            if (GlowTrimItem.getColors(s).length == 0) return false;
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
            result = GlowTrimItem.mergeColors(result, s);
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
