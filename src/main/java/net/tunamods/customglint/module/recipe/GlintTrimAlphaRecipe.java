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

/**
 * GlintTrimItem (with pattern + ≥1 color) + 1–8 Glass → same trim with every color's alpha (the A /
 * transparency channel) set by the glass count: 8 glass = fully opaque (A=255), fewer = more
 * see-through. Mirrors {@link GlintTrimSpeedRecipe} / {@link GlintTrimScaleRecipe}.
 */
public class GlintTrimAlphaRecipe extends CustomRecipe {
    private static final GlintTrimAlphaRecipe INSTANCE = new GlintTrimAlphaRecipe();
    public static final MapCodec<GlintTrimAlphaRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, GlintTrimAlphaRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<GlintTrimAlphaRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public GlintTrimAlphaRecipe() {}

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        ItemStack trim = ItemStack.EMPTY;
        int count = 0;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof GlintTrimItem && GlintTrimItem.getPattern(s) != null) {
                if (!trim.isEmpty()) return false;
                trim = s;
            } else if (s.is(Items.GLASS)) {
                count++;
            } else {
                return false;
            }
        }
        return !trim.isEmpty() && GlintTrimItem.getColors(trim).length > 0 && count >= 1 && count <= 8;
    }

    @Override
    public ItemStack assemble(CraftingInput pInv) {
        ItemStack trim = ItemStack.EMPTY;
        int count = 0;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof GlintTrimItem) trim = s;
            else if (s.is(Items.GLASS)) count++;
        }
        if (trim.isEmpty()) return ItemStack.EMPTY;
        int[] colors = GlintTrimItem.getColors(trim);
        if (colors.length == 0) return ItemStack.EMPTY;
        int alpha = Math.round(count * 255f / 8f);
        int[] out = new int[colors.length];
        for (int i = 0; i < colors.length; i++) out[i] = (alpha << 24) | (colors[i] & 0xFFFFFF);
        ItemStack result = trim.copy();
        result.setCount(1);
        GlintTrimItem.setColors(result, out);
        return result;
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public RecipeSerializer<? extends CustomRecipe> getSerializer() {
        return SERIALIZER;
    }
}
