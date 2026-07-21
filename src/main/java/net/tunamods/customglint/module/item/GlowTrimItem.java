package net.tunamods.customglint.module.item;

import net.tunamods.customglint.common.CustomGlint;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

/**
 * Glow Trim: applies a colored outline glow only (no glint design).
 * Stores its own colors in the standard COLORS_TAG. When applied via smithing, writes
 * {@code glowColors} + {@code glowing=true} to the target item via CustomGlint.setGlowColors.
 * Mirrors GlintTrimItem's color handling: dye to add, merge to combine, capped at
 * {@link CustomGlint#MAX_COLORS_PER_LAYER} colors.
 */
public class GlowTrimItem extends Item {
    public static final String COLORS_TAG = "colors";

    /** Library key for the Glint Table's design palette: a Glow Trim has no glint pattern, so it stores
     *  under this sentinel name instead of a design name. */
    public static final String STORAGE_KEY = "glow_trim";

    public GlowTrimItem(Properties pProperties) {
        super(pProperties);
    }

    public static int[] getColors(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains(COLORS_TAG)) return new int[0];
        return stack.getTag().getIntArray(COLORS_TAG);
    }

    public static boolean addColor(ItemStack stack, int color) {
        int[] current = getColors(stack);
        if (current.length >= CustomGlint.MAX_COLORS_PER_LAYER) return false;
        int[] next = Arrays.copyOf(current, current.length + 1);
        next[current.length] = color;
        stack.getOrCreateTag().put(COLORS_TAG, new IntArrayTag(next));
        applyPreview(stack, next);
        return true;
    }

    public static ItemStack mergeColors(ItemStack first, ItemStack second) {
        ItemStack result = first.copy();
        result.setCount(1);
        int[] merged = GlintTrimItem.mergedColors(getColors(first), getColors(second));
        result.getOrCreateTag().put(COLORS_TAG, new IntArrayTag(merged));
        applyPreview(result, merged);
        return result;
    }

    /** Writes glowColors + glowing=true onto the trim itself so it previews as a glowing item. */
    private static void applyPreview(ItemStack stack, int[] colors) {
        if (colors.length == 0) return;
        CustomGlint.setGlowColors(stack, colors);
    }

    @Override
    public void appendHoverText(ItemStack pStack, @Nullable Level pLevel, List<Component> pTooltipComponents, TooltipFlag pIsAdvanced) {
        int[] colors = getColors(pStack);
        if (colors.length == 0) {
            pTooltipComponents.add(Component.literal("No color. Craft with a dye to add one"));
            return;
        }
        pTooltipComponents.add(Component.literal("Applies a colored outline glow. Apply at a smithing table with Glowstone Dust").withStyle(ChatFormatting.YELLOW));
        pTooltipComponents.add(GlintTrimItem.colorLine("Colors: ", colors));
    }
}
