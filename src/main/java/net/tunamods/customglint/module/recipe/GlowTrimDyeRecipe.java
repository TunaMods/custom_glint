package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Glow Trim + Dye -> Glow Trim with one more color appended (cap 8). The Glint counterpart replaces the
 *  color instead of appending. */
public class GlowTrimDyeRecipe extends AbstractTrimDyeRecipe {
    private static final GlowTrimDyeRecipe INSTANCE = new GlowTrimDyeRecipe();
    public static final MapCodec<GlowTrimDyeRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, GlowTrimDyeRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<GlowTrimDyeRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public GlowTrimDyeRecipe() {}

    @Override
    protected boolean isTrim(ItemStack s) {
        return s.getItem() instanceof GlowTrimItem;
    }

    @Override
    protected boolean trimQualifies(ItemStack trim) {
        return GlowTrimItem.getColors(trim).length < CustomGlint.MAX_COLORS_PER_LAYER;
    }

    @Override
    protected ItemStack applyDye(ItemStack trimCopy, DyeColor dye) {
        GlowTrimItem.addColor(trimCopy, GlintTrimItem.DYE_COLORS[dye.ordinal()]);
        return trimCopy;
    }

    @Override
    public RecipeSerializer<? extends CustomRecipe> getSerializer() {
        return SERIALIZER;
    }
}
