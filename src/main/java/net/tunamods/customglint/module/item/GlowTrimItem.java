package net.tunamods.customglint.module.item;

import net.tunamods.customglint.common.CustomGlint;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Glow Trim, applies a colored outline glow only (no glint design).
 * Stores its colors in the ModComponents.GLOW_TRIM data component. When applied via smithing, writes
 * {@code glowColors} + {@code glowing=true} to the target item via CustomGlint.setGlowColors.
 * Mirrors GlintTrimItem's color handling (dye to add, merge to combine, capped at MAX_COLORS_PER_LAYER).
 */
public class GlowTrimItem extends Item {

    /** Storage-library key for the Glow Trim in the Glint Table grid (it has no design pattern of its
     *  own). Distinct from the "glow" texture design in {@link GlintTrimItem#PATTERNS}. */
    public static final String STORAGE_KEY = "glow_trim";

    public GlowTrimItem(Properties pProperties) {
        super(pProperties);
    }

    // The Glow Trim's color list is a typed data component (ModComponents.GLOW_TRIM), value-equal
    // (List<Integer>) so identical trims still stack.

    public static int[] getColors(ItemStack stack) {
        return TrimColors.toIntArray(stack.getOrDefault(ModComponents.GLOW_TRIM.get(), List.of()));
    }

    /** Replaces the color list (and refreshes the preview glow). */
    public static void setColors(ItemStack stack, int[] colors) {
        stack.set(ModComponents.GLOW_TRIM.get(), TrimColors.toList(colors));
        applyPreview(stack, colors);
    }

    public static boolean addColor(ItemStack stack, int color) {
        int[] current = getColors(stack);
        if (current.length >= CustomGlint.MAX_COLORS_PER_LAYER) return false;
        int[] next = Arrays.copyOf(current, current.length + 1);
        next[current.length] = color;
        setColors(stack, next);
        return true;
    }

    public static ItemStack mergeColors(ItemStack first, ItemStack second) {
        ItemStack result = first.copy();
        result.setCount(1);
        setColors(result, TrimColors.merge(getColors(first), getColors(second), CustomGlint.MAX_COLORS_PER_LAYER));
        return result;
    }

    /** Writes glowColors + glowing=true onto the trim itself so it previews as a glowing item. */
    private static void applyPreview(ItemStack stack, int[] colors) {
        if (colors.length == 0) return;
        CustomGlint.setGlowColors(stack, colors);
    }

    @Override
    public void appendHoverText(ItemStack pStack, Item.TooltipContext pContext, TooltipDisplay pDisplay, Consumer<Component> pTooltipComponents, TooltipFlag pIsAdvanced) {
        int[] colors = getColors(pStack);
        if (colors.length == 0) {
            pTooltipComponents.accept(Component.literal("No color, craft with a dye to add one"));
            return;
        }
        pTooltipComponents.accept(Component.literal("Applies a colored outline glow, apply at a smithing table with Glowstone Dust").withStyle(ChatFormatting.YELLOW));
        pTooltipComponents.accept(GlintTrimItem.colorLine("Colors: ", colors));
    }
}
