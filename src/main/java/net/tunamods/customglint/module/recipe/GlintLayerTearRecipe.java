package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintLayerTearItem;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.Level;

public class GlintLayerTearRecipe extends CustomRecipe {

    private static final GlintLayerTearRecipe INSTANCE = new GlintLayerTearRecipe();
    public static final MapCodec<GlintLayerTearRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, GlintLayerTearRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<GlintLayerTearRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public GlintLayerTearRecipe() {}

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        boolean hasTear = false;
        ItemStack glint1 = ItemStack.EMPTY;
        ItemStack glint2 = ItemStack.EMPTY;
        int filled = 0;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            filled++;
            if (s.getItem() instanceof GlintLayerTearItem) {
                if (hasTear) return false;
                hasTear = true;
            } else if (s.getItem() instanceof GlintTrimItem && CustomGlint.has(s) && GlintTrimItem.getColors(s).length > 0) {
                if (glint1.isEmpty()) glint1 = s;
                else if (glint2.isEmpty()) glint2 = s;
                else return false;
            } else {
                return false;
            }
        }
        return filled == 3 && hasTear && !glint1.isEmpty() && !glint2.isEmpty();
    }

    @Override
    public ItemStack assemble(CraftingInput pInv) {
        ItemStack glint1 = ItemStack.EMPTY;
        ItemStack glint2 = ItemStack.EMPTY;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty() || s.getItem() instanceof GlintLayerTearItem) continue;
            if (s.getItem() instanceof GlintTrimItem) {
                if (glint1.isEmpty()) glint1 = s;
                else glint2 = s;
            }
        }
        if (glint1.isEmpty() || glint2.isEmpty()) return ItemStack.EMPTY;
        CustomGlint.Data d1 = CustomGlint.read(glint1);
        CustomGlint.Data d2 = CustomGlint.read(glint2);
        if (d1 == null || d2 == null) return ItemStack.EMPTY;
        int total = Math.min(d1.layers().length + d2.layers().length, 8);
        CustomGlint.Layer[] combined = new CustomGlint.Layer[total];
        int fromD1 = Math.min(d1.layers().length, total);
        System.arraycopy(d1.layers(), 0, combined, 0, fromD1);
        int fromD2 = total - fromD1;
        if (fromD2 > 0) System.arraycopy(d2.layers(), 0, combined, fromD1, fromD2);
        ItemStack result = glint1.copy();
        result.setCount(1);
        CustomGlint.write(result, combined);
        return result;
    }

    
    @Override
    public boolean isSpecial() { return true; }

    
    
    @Override
    public RecipeSerializer<? extends CustomRecipe> getSerializer() {
        return SERIALIZER;
    }
}
