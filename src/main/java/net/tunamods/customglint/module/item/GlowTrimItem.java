package net.tunamods.customglint.module.item;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.tunamods.customglint.common.CustomGlint;

/**
 * Glow Trim: applies a colored outline glow only (no glint design).
 * Stores its own colors in the {@link ModComponents#GLOW_TRIM} data component. When applied via smithing,
 * writes {@code glowColors} + {@code glowing=true} to the target item via {@link CustomGlint#setGlowColors}.
 * Mirrors GlintTrimItem's color handling (dye to add, merge to combine, max 8 colors).
 */
public class GlowTrimItem extends Item {
    /** Sentinel design-name for a Glow Trim in the Glint Table's per-player stored-design library. */
    public static final String STORAGE_KEY = "glow_trim";

    public GlowTrimItem(Properties pProperties) {
        super(pProperties);
    }

    private static int[] toIntArray(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int n = 0; n < a.length; n++) a[n] = l.get(n);
        return a;
    }

    private static List<Integer> toList(int[] a) {
        List<Integer> l = new ArrayList<>(a.length);
        for (int v : a) l.add(v);
        return l;
    }

    public static int[] getColors(ItemStack stack) {
        List<Integer> c = stack.get(ModComponents.GLOW_TRIM.get());
        return c == null ? new int[0] : toIntArray(c);
    }

    /** Replaces the whole color set (Glint Table build path), refreshing the glow preview. */
    public static void setColors(ItemStack stack, int[] colors) {
        stack.set(ModComponents.GLOW_TRIM.get(), toList(colors));
        applyPreview(stack, colors);
    }

    public static boolean addColor(ItemStack stack, int color) {
        int[] current = getColors(stack);
        if (current.length >= CustomGlint.MAX_COLORS_PER_LAYER) return false;
        int[] next = Arrays.copyOf(current, current.length + 1);
        next[current.length] = color;
        stack.set(ModComponents.GLOW_TRIM.get(), toList(next));
        applyPreview(stack, next);
        return true;
    }

    public static ItemStack mergeColors(ItemStack first, ItemStack second) {
        ItemStack result = first.copy();
        result.setCount(1);
        int[] a = getColors(first);
        int[] b = getColors(second);
        int total = Math.min(CustomGlint.MAX_COLORS_PER_LAYER, a.length + b.length);
        int[] merged = new int[total];
        System.arraycopy(a, 0, merged, 0, Math.min(a.length, total));
        int bCount = total - a.length;
        if (bCount > 0) System.arraycopy(b, 0, merged, a.length, bCount);
        result.set(ModComponents.GLOW_TRIM.get(), toList(merged));
        applyPreview(result, merged);
        return result;
    }

    /** Writes glowColors + glowing=true onto the trim itself so it previews as a glowing item. */
    private static void applyPreview(ItemStack stack, int[] colors) {
        if (colors.length == 0) return;
        CustomGlint.setGlowColors(stack, colors);
    }

    @Override
    public void appendHoverText(ItemStack pStack, Item.TooltipContext pContext, List<Component> pTooltipComponents, TooltipFlag pIsAdvanced) {
        int[] colors = getColors(pStack);
        if (colors.length == 0) {
            pTooltipComponents.add(Component.literal("No color. Craft with a dye to add one"));
            return;
        }
        pTooltipComponents.add(Component.literal("Applies a colored outline glow. Apply at a smithing table with Glowstone Dust").withStyle(ChatFormatting.YELLOW));
        pTooltipComponents.add(GlintTrimItem.colorsLine("Colors: ", colors));
    }
}
