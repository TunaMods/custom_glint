package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

public class GlintTearApplyRecipe extends CustomRecipe {
    private static final GlintTearApplyRecipe INSTANCE = new GlintTearApplyRecipe();
    public static final MapCodec<GlintTearApplyRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, GlintTearApplyRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<GlintTearApplyRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public GlintTearApplyRecipe() {}

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        boolean hasTear = false;
        boolean hasGlinted = false;
        int filled = 0;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            filled++;
            if (s.getItem() == CustomGlintMod.GLINT_TEAR_SIMULTANEOUS.get()
                    || s.getItem() == CustomGlintMod.GLINT_TEAR_SEQUENTIAL.get()) {
                if (hasTear) return false;
                hasTear = true;
            } else if (CustomGlint.has(s) && !(s.getItem() instanceof GlintTrimItem && GlintTrimItem.getColors(s).length == 0)) {
                if (hasGlinted) return false;
                hasGlinted = true;
            } else {
                return false;
            }
        }
        return filled == 2 && hasTear && hasGlinted;
    }

    @Override
    public ItemStack assemble(CraftingInput pInv) {
        ItemStack glinted = ItemStack.EMPTY;
        Boolean simultaneous = null;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() == CustomGlintMod.GLINT_TEAR_SIMULTANEOUS.get()) simultaneous = true;
            else if (s.getItem() == CustomGlintMod.GLINT_TEAR_SEQUENTIAL.get()) simultaneous = false;
            else if (CustomGlint.has(s)) glinted = s;
        }
        if (glinted.isEmpty() || simultaneous == null) return ItemStack.EMPTY;
        CustomGlint.Data data = CustomGlint.read(glinted);
        if (data == null) return ItemStack.EMPTY;
        ItemStack result = glinted.copy();
        result.setCount(1);
        CustomGlint.Layer[] src = data.layers();
        CustomGlint.Layer[] newLayers = new CustomGlint.Layer[src.length];
        for (int i = 0; i < src.length; i++)
            newLayers[i] = new CustomGlint.Layer(src[i].design(), src[i].colors(), src[i].speed(), src[i].interpolate(), src[i].patternScale(), simultaneous);
        CustomGlint.write(result, newLayers);
        return result;
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public RecipeSerializer<? extends CustomRecipe> getSerializer() {
        return SERIALIZER;
    }
}
