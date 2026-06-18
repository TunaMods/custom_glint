package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;
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

/** Glow Trim + Dye → Glow Trim with one more color appended (cap 8). Mirrors GlintTrimDyeRecipe. */
public class GlowTrimDyeRecipe extends CustomRecipe {
    private static final GlowTrimDyeRecipe INSTANCE = new GlowTrimDyeRecipe();
    public static final MapCodec<GlowTrimDyeRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, GlowTrimDyeRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<GlowTrimDyeRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public GlowTrimDyeRecipe() {}

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        ItemStack trim = ItemStack.EMPTY;
        ItemStack dye  = ItemStack.EMPTY;
        int filled = 0;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            filled++;
            if (s.getItem() instanceof GlowTrimItem) {
                if (!trim.isEmpty()) return false;
                trim = s;
            } else if (s.getItem() instanceof DyeItem) {
                if (!dye.isEmpty()) return false;
                dye = s;
            } else {
                return false;
            }
        }
        return filled == 2 && !trim.isEmpty() && !dye.isEmpty()
                && GlowTrimItem.getColors(trim).length < 8;
    }

    @Override
    public ItemStack assemble(CraftingInput pInv) {
        ItemStack trim = ItemStack.EMPTY;
        ItemStack dyeStack = ItemStack.EMPTY;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof GlowTrimItem) trim = s;
            else if (s.getItem() instanceof DyeItem) dyeStack = s;
        }
        DyeColor dyeColor = dyeStack.isEmpty() ? null : dyeStack.get(DataComponents.DYE);
        if (trim.isEmpty() || dyeColor == null) return ItemStack.EMPTY;
        ItemStack result = trim.copy();
        result.setCount(1);
        int[] current = GlowTrimItem.getColors(result);
        int[] next = new int[current.length + 1];
        System.arraycopy(current, 0, next, 0, current.length);
        next[current.length] = GlintTrimItem.DYE_COLORS[dyeColor.ordinal()];
        GlowTrimItem.setColors(result, next);
        return result;
    }

    
    @Override
    public boolean isSpecial() { return true; }

    
    
    @Override
    public RecipeSerializer<? extends CustomRecipe> getSerializer() {
        return SERIALIZER;
    }
}
