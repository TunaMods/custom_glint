package net.tunamods.customglint.module.item;

import net.tunamods.customglint.common.CustomGlint;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Glow Trim — applies a colored outline glow only (no glint design).
 * Stores its own colors in the standard COLORS_TAG. When applied via smithing, writes
 * {@code glowColors} + {@code glowing=true} to the target item via CustomGlint.setGlowColors.
 * Mirrors GlintTrimItem's color handling (dye to add, merge to combine, max 8 colors).
 */
public class GlowTrimItem extends Item {
    public static final String COLORS_TAG = "colors";

    public GlowTrimItem(Properties pProperties) {
        super(pProperties);
    }

    @Nullable
    private static CompoundTag dataOrNull(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        return cd == null ? null : cd.copyTag();
    }

    private static void mutateData(ItemStack stack, Consumer<CompoundTag> mutator) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, mutator);
    }

    public static int[] getColors(ItemStack stack) {
        CompoundTag tag = dataOrNull(stack);
        if (tag == null || !tag.contains(COLORS_TAG)) return new int[0];
        return tag.getIntArray(COLORS_TAG).orElse(new int[0]);
    }

    public static boolean addColor(ItemStack stack, int color) {
        int[] current = getColors(stack);
        if (current.length >= 8) return false;
        int[] next = Arrays.copyOf(current, current.length + 1);
        next[current.length] = color;
        mutateData(stack, t -> t.putIntArray(COLORS_TAG, next));
        applyPreview(stack, next);
        return true;
    }

    public static ItemStack mergeColors(ItemStack first, ItemStack second) {
        ItemStack result = first.copy();
        result.setCount(1);
        int[] a = getColors(first);
        int[] b = getColors(second);
        int total = Math.min(8, a.length + b.length);
        int[] merged = new int[total];
        System.arraycopy(a, 0, merged, 0, Math.min(a.length, total));
        int bCount = total - a.length;
        if (bCount > 0) System.arraycopy(b, 0, merged, a.length, bCount);
        mutateData(result, t -> t.putIntArray(COLORS_TAG, merged));
        applyPreview(result, merged);
        return result;
    }

    /** Writes glowColors + glowing=true onto the trim itself so it previews as a glowing item. */
    private static void applyPreview(ItemStack stack, int[] colors) {
        if (colors.length == 0) return;
        CustomGlint.setGlowColors(stack, colors);
    }

    @Override
    public void appendHoverText(ItemStack pStack, Item.TooltipContext pContext, net.minecraft.world.item.component.TooltipDisplay pDisplay, java.util.function.Consumer<Component> pTooltipComponents, TooltipFlag pIsAdvanced) {
        int[] colors = getColors(pStack);
        if (colors.length == 0) {
            pTooltipComponents.accept(Component.literal("No color — craft with a dye to add one"));
            return;
        }
        pTooltipComponents.accept(Component.literal("Applies a colored outline glow — apply at a smithing table with Glowstone Dust").withStyle(ChatFormatting.YELLOW));
        MutableComponent line = Component.literal("Colors: ").withStyle(ChatFormatting.GRAY);
        for (int i = 0; i < colors.length; i++) {
            int rgb = colors[i] & 0xFFFFFF;
            String name = "#" + String.format("%06X", rgb);
            for (int j = 0; j < GlintTrimItem.DYE_COLORS.length; j++) {
                if ((GlintTrimItem.DYE_COLORS[j] & 0xFFFFFF) == rgb) { name = capitalize(DyeColor.values()[j].getName().replace("_", " ")); break; }
            }
            if (i > 0) line = line.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
            line = line.append(Component.literal(name).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))));
        }
        pTooltipComponents.accept(line);
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
