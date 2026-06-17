package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;

public class GlintBlackTearRecipe extends CustomRecipe {

    private static final GlintBlackTearRecipe INSTANCE = new GlintBlackTearRecipe();
    public static final MapCodec<GlintBlackTearRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, GlintBlackTearRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<GlintBlackTearRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public GlintBlackTearRecipe() {}

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        boolean hasTear = false;
        boolean hasGlinted = false;
        int filled = 0;
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            filled++;
            if (s.getItem() == CustomGlintMod.GLINT_BLACK_TEAR.get()) {
                if (hasTear) return false;
                hasTear = true;
            } else if (CustomGlint.has(s)) {
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
        for (int i = 0; i < pInv.size(); i++) {
            ItemStack s = pInv.getItem(i);
            if (!s.isEmpty() && s.getItem() != CustomGlintMod.GLINT_BLACK_TEAR.get() && CustomGlint.has(s)) {
                glinted = s;
                break;
            }
        }
        if (glinted.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = glinted.copy();
        result.setCount(1);
        if (result.getItem() instanceof GlintTrimItem) {
            Identifier pattern = GlintTrimItem.getPattern(result);
            if (pattern == null) {
                CustomGlint.Data data = CustomGlint.read(result);
                if (data != null && data.layers().length > 0) pattern = data.layers()[0].design();
            }
            CustomData.update(DataComponents.CUSTOM_DATA, result, t -> {
                t.remove(GlintTrimItem.COLORS_TAG);
                t.remove(GlintTrimItem.SPEED_TAG);
                t.remove(GlintTrimItem.SCALE_TAG);
                t.remove(GlintTrimItem.GLOWING_TAG);
            });
            CustomGlint.remove(result);
            if (pattern != null) GlintTrimItem.setPattern(result, pattern);
        } else {
            CustomGlint.remove(result);
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
