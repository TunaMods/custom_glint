package net.tunamods.customglint.module.item;

import net.tunamods.customglint.common.CustomGlint;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

public class GlintTrimItem extends Item {
    public static final String PATTERN_TAG = "pattern";
    public static final String COLORS_TAG  = "colors";

    // ARGB per DyeColor ordinal: WHITE ORANGE MAGENTA LIGHT_BLUE YELLOW LIME PINK
    //                             GRAY  LIGHT_GRAY CYAN PURPLE BLUE BROWN GREEN RED BLACK
    public static final int[] DYE_COLORS = {
        0xFFF9FFFE, 0xFFFF8000, 0xFFFF00FF, 0xFF00AAFF, 0xFFFFFF00, 0xFF00FF00, 0xFFFF80A0,
        0xFF808080, 0xFFAAAAAA, 0xFF00FFFF, 0xFF8800CC, 0xFF0000FF, 0xFF885522, 0xFF008800,
        0xFFFF0000, 0xFF333333
    };

    public static final List<String> PATTERNS = List.of(
        "checker", "crosshatch", "crystal", "diamonds", "dots", "ember", "fire",
        "grid", "hexagon", "pulse", "ripple", "scales", "sparkle", "stars", "stripes",
        "swirl", "vein", "wave", "zigzag", "vanilla"
    );

    public GlintTrimItem(Properties pProperties) {
        super(pProperties);
    }

    @Nullable
    public static ResourceLocation getPattern(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains(PATTERN_TAG)) return null;
        return ResourceLocation.tryParse(stack.getTag().getString(PATTERN_TAG));
    }

    public static void setPattern(ItemStack stack, ResourceLocation pattern) {
        stack.getOrCreateTag().putString(PATTERN_TAG, pattern.toString());
        String name = pattern.equals(CustomGlint.VANILLA) ? "vanilla" : extractPatternName(pattern);
        int idx = PATTERNS.indexOf(name);
        if (idx >= 0) stack.getOrCreateTag().putInt("CustomModelData", idx + 1);
        int[] colors = getColors(stack);
        CustomGlint.write(stack, pattern, colors.length > 0 ? colors : new int[]{0xFFFFFFFF}, 1.0f, true, 1.0f, false);
    }

    public static int[] getColors(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains(COLORS_TAG)) return new int[0];
        return stack.getTag().getIntArray(COLORS_TAG);
    }

    public static boolean addColor(ItemStack stack, int color) {
        int[] current = getColors(stack);
        if (current.length >= 8) return false;
        int[] next = Arrays.copyOf(current, current.length + 1);
        next[current.length] = color;
        stack.getOrCreateTag().put(COLORS_TAG, new IntArrayTag(next));
        ResourceLocation pattern = getPattern(stack);
        if (pattern != null) CustomGlint.write(stack, pattern, next, 1.0f, true, 1.0f, false);
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
        result.getOrCreateTag().put(COLORS_TAG, new IntArrayTag(merged));
        ResourceLocation pattern = getPattern(result);
        if (pattern != null) CustomGlint.write(result, pattern, merged, 1.0f, true, 1.0f, false);
        return result;
    }

    @Override
    public Component getName(ItemStack pStack) {
        ResourceLocation pattern = getPattern(pStack);
        if (pattern == null) return super.getName(pStack);
        String name = pattern.equals(CustomGlint.VANILLA) ? "Vanilla" : capitalize(extractPatternName(pattern));
        return Component.literal(name + " Glint Trim");
    }

    @Override
    public void appendHoverText(ItemStack pStack, @Nullable Level pLevel, List<Component> pTooltipComponents, TooltipFlag pIsAdvanced) {
        int[] colors = getColors(pStack);
        if (colors.length == 0) {
            pTooltipComponents.add(Component.literal("No color — craft with a dye to add one"));
        } else {
            pTooltipComponents.add(Component.literal(colors.length + " color" + (colors.length > 1 ? "s" : "") + " — apply with Glowstone Dust at a smithing table"));
        }
    }

    public static String extractPatternName(ResourceLocation pattern) {
        String path = pattern.getPath();
        int slash = path.lastIndexOf('/');
        int dot   = path.lastIndexOf('.');
        if (dot < 0) dot = path.length();
        return path.substring(slash + 1, dot);
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
