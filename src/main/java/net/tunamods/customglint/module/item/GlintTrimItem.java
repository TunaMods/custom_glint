package net.tunamods.customglint.module.item;

import net.tunamods.customglint.common.CustomGlint;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
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
        "swirl", "vein", "wave", "zigzag", "vanilla", "solid", "skulls"
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
            return;
        }
        CustomGlint.Data data = CustomGlint.read(pStack);
        if (data != null && data.layers().length > 1) {
            int n = data.layers().length;
            pTooltipComponents.add(Component.literal("Apply with Glowstone Dust at a smithing table"));
            if (!Screen.hasShiftDown()) {
                pTooltipComponents.add(Component.literal(n + " layers").withStyle(ChatFormatting.DARK_AQUA));
                pTooltipComponents.add(Component.literal("Hold Shift").withStyle(ChatFormatting.GOLD));
            } else {
                for (int i = 0; i < n; i++) {
                    CustomGlint.Layer layer = data.layers()[i];
                    String dname = layer.design().equals(CustomGlint.VANILLA) ? "Vanilla" : capitalize(extractPatternName(layer.design()));
                    pTooltipComponents.add(Component.literal("Layer " + (i + 1)).withStyle(ChatFormatting.WHITE));
                    pTooltipComponents.add(Component.literal("  " + dname).withStyle(ChatFormatting.GRAY));
                    if (layer.colors().length > 0) {
                        MutableComponent lc = Component.literal("  Colors: ").withStyle(ChatFormatting.GRAY);
                        for (int k = 0; k < layer.colors().length; k++) {
                            int rgb = layer.colors()[k] & 0xFFFFFF;
                            String cname = "#" + String.format("%06X", rgb);
                            for (int j = 0; j < DYE_COLORS.length; j++) {
                                if (DYE_COLORS[j] == layer.colors()[k]) { cname = capitalize(DyeColor.values()[j].getName().replace("_", " ")); break; }
                            }
                            if (k > 0) lc = lc.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
                            lc = lc.append(Component.literal(cname).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))));
                        }
                        pTooltipComponents.add(lc);
                    }
                }
            }
        } else {
            pTooltipComponents.add(Component.literal(colors.length + " color" + (colors.length > 1 ? "s" : "") + " — apply with Glowstone Dust at a smithing table"));
            MutableComponent line = Component.literal("Colors: ").withStyle(ChatFormatting.GRAY);
            for (int i = 0; i < colors.length; i++) {
                int rgb = colors[i] & 0xFFFFFF;
                String name = "#" + String.format("%06X", rgb);
                for (int j = 0; j < DYE_COLORS.length; j++) {
                    if (DYE_COLORS[j] == colors[i]) { name = capitalize(DyeColor.values()[j].getName().replace("_", " ")); break; }
                }
                if (i > 0) line = line.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
                line = line.append(Component.literal(name).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))));
            }
            pTooltipComponents.add(line);
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
