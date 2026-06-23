package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.common.CustomGlint;
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

/** Crafting: GlintTrimItem (center, with pattern + ≥1 color) + 8 Glowstone Dust → same trim with glowing=true. */
public class GlintGlowTrimRecipe extends CustomRecipe {
    private static final GlintGlowTrimRecipe INSTANCE = new GlintGlowTrimRecipe();
    public static final MapCodec<GlintGlowTrimRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, GlintGlowTrimRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<GlintGlowTrimRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public GlintGlowTrimRecipe() {}

    @Override
    public boolean matches(CraftingInput pInv, Level pLevel) {
        if (pInv.width() != 3 || pInv.height() != 3) return false;
        for (int i = 0; i < 9; i++) {
            ItemStack s = pInv.getItem(i);
            if (i == 4) {
                if (!(s.getItem() instanceof GlintTrimItem)) return false;
                if (GlintTrimItem.getPattern(s) == null) return false;
                if (GlintTrimItem.getColors(s).length == 0) return false;
            } else {
                if (!s.is(Items.GLOWSTONE_DUST)) return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack assemble(CraftingInput pInv) {
        ItemStack trim = pInv.getItem(4);
        if (trim.isEmpty() || !(trim.getItem() instanceof GlintTrimItem)) return ItemStack.EMPTY;
        ItemStack result = trim.copy();
        result.setCount(1);
        GlintTrimItem.setGlowing(result, true);
        CustomGlint.setGlowing(result, true);
        return result;
    }

    @Override
    public boolean isSpecial() { return true; }

    @Override
    public RecipeSerializer<? extends CustomRecipe> getSerializer() {
        return SERIALIZER;
    }
}
