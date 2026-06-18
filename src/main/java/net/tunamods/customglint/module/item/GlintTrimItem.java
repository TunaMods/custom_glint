package net.tunamods.customglint.module.item;

import com.mojang.blaze3d.platform.InputConstants;
import net.tunamods.customglint.common.CustomGlint;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.TooltipDisplay;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class GlintTrimItem extends Item {
    // ARGB per DyeColor ordinal: WHITE ORANGE MAGENTA LIGHT_BLUE YELLOW LIME PINK
    //                             GRAY  LIGHT_GRAY CYAN PURPLE BLUE BROWN GREEN RED BLACK
    public static final int[] DYE_COLORS = {
        0xFFF9FFFE, 0xFFFF8000, 0xFFFF00FF, 0xFF00AAFF, 0xFFFFFF00, 0xFF00FF00, 0xFFFF80A0,
        0xFF808080, 0xFFAAAAAA, 0xFF00FFFF, 0xFF8800CC, 0xFF0000FF, 0xFF885522, 0xFF008800,
        0xFFFF0000, 0xFF333333
    };

    public static final List<String> PATTERNS = new ArrayList<>(List.of(
        "arcs", "aurora", "blobs", "cascade", "checker", "chevron", "coral", "cracks",
        "crosshatch", "crystal", "debris", "diamonds", "dunes", "ember", "feather", "fire",
        "frost", "glitch", "glow", "grid", "halo", "hexagon", "lightning", "marble",
        "matrix", "mesh", "mosaic", "net", "oil", "petal", "plasma", "plate",
        "prism", "pulse", "ripple", "sand", "scales", "sheen", "shimmer", "silk",
        "slash", "smoke", "solid", "sparkle", "stars", "static", "stripes", "swirl",
        "tide", "tile", "vanilla", "vein", "wave", "weave", "zigzag"
    ));

    public GlintTrimItem(Properties pProperties) {
        super(pProperties);
    }

    // The Trim's editable config (pattern/colors/speed/scale/glowing) is a typed data component
    // (ModComponents.TRIM), independent of the preview glint CustomGlint stores in its own glint
    // component. CustomModelData (set in setPattern) is vanilla's own component.

    private static ModComponents.TrimConfig cfg(ItemStack stack) {
        ModComponents.TrimConfig c = stack.get(ModComponents.TRIM.get());
        return c == null ? ModComponents.TrimConfig.EMPTY : c;
    }

    private static void setCfg(ItemStack stack, ModComponents.TrimConfig c) {
        stack.set(ModComponents.TRIM.get(), c);
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

    /** 26.1.2 removed the static {@code Screen.hasShiftDown()}; query the window directly.
     *  Only invoked from {@code appendHoverText} (client-side tooltip rendering). */
    private static boolean cgHasShiftDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
            || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    @Nullable
    public static Identifier getPattern(ItemStack stack) {
        return cfg(stack).pattern().orElse(null);
    }

    public static void setPattern(ItemStack stack, Identifier pattern) {
        ModComponents.TrimConfig c = cfg(stack);
        setCfg(stack, new ModComponents.TrimConfig(Optional.of(pattern), c.colors(), c.speed(), c.scale(), c.glowing()));
        String name = pattern.equals(CustomGlint.VANILLA) ? "vanilla" : extractPatternName(pattern);
        int idx = PATTERNS.indexOf(name);
        if (idx >= 0) stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(
                java.util.List.of((float) ((isGlowing(stack) ? 1000 : 0) + idx + 1)),
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        int[] colors = getColors(stack);
        CustomGlint.write(stack, pattern, colors.length > 0 ? colors : new int[]{0xFFFFFFFF}, getSpeed(stack), true, getScale(stack), false);
    }

    public static int[] getColors(ItemStack stack) {
        return toIntArray(cfg(stack).colors());
    }

    /** Replaces the color list (and refreshes the preview glint). Used by the dye recipe. */
    public static void setColors(ItemStack stack, int[] colors) {
        ModComponents.TrimConfig c = cfg(stack);
        setCfg(stack, new ModComponents.TrimConfig(c.pattern(), toList(colors), c.speed(), c.scale(), c.glowing()));
        Identifier pattern = getPattern(stack);
        if (pattern != null) CustomGlint.write(stack, pattern, colors, getSpeed(stack), true, getScale(stack), false);
    }

    /** Sets the display config (pattern/speed/scale/glowing) directly, preserving the current colors and
     *  WITHOUT rewriting the preview glint — for {@code /glint extract} on a multi-layer glint, where the
     *  full multi-layer tag is copied separately and must not be clobbered by a single-layer write. */
    public static void setConfig(ItemStack stack, Identifier pattern, float speed, float scale, boolean glowing) {
        ModComponents.TrimConfig c = cfg(stack);
        setCfg(stack, new ModComponents.TrimConfig(Optional.of(pattern), c.colors(), speed, scale, glowing));
    }

    public static boolean addColor(ItemStack stack, int color) {
        int[] current = getColors(stack);
        if (current.length >= 8) return false;
        int[] next = Arrays.copyOf(current, current.length + 1);
        next[current.length] = color;
        ModComponents.TrimConfig c = cfg(stack);
        setCfg(stack, new ModComponents.TrimConfig(c.pattern(), toList(next), c.speed(), c.scale(), c.glowing()));
        Identifier pattern = getPattern(stack);
        if (pattern != null) CustomGlint.write(stack, pattern, next, getSpeed(stack), true, getScale(stack), false);
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
        ModComponents.TrimConfig c = cfg(result);
        setCfg(result, new ModComponents.TrimConfig(c.pattern(), toList(merged), c.speed(), c.scale(), c.glowing()));
        Identifier pattern = getPattern(result);
        if (pattern != null) CustomGlint.write(result, pattern, merged, getSpeed(result), true, getScale(result), false);
        return result;
    }

    public static float getSpeed(ItemStack stack) {
        return cfg(stack).speed();
    }

    public static void setSpeed(ItemStack stack, float speed) {
        ModComponents.TrimConfig c = cfg(stack);
        setCfg(stack, new ModComponents.TrimConfig(c.pattern(), c.colors(), speed, c.scale(), c.glowing()));
        CustomGlint.Data preview = CustomGlint.read(stack);
        if (preview == null || preview.layers().length <= 1) {
            Identifier pattern = getPattern(stack);
            int[] colors = getColors(stack);
            if (pattern != null) CustomGlint.write(stack, pattern, colors.length > 0 ? colors : new int[]{0xFFFFFFFF}, speed, true, getScale(stack), false);
        }
    }

    public static boolean isGlowing(ItemStack stack) {
        return cfg(stack).glowing();
    }

    public static void setGlowing(ItemStack stack, boolean glowing) {
        ModComponents.TrimConfig c = cfg(stack);
        setCfg(stack, new ModComponents.TrimConfig(c.pattern(), c.colors(), c.speed(), c.scale(), glowing));
        Identifier pattern = getPattern(stack);
        if (pattern != null) setPattern(stack, pattern);
    }

    public static float getScale(ItemStack stack) {
        return cfg(stack).scale();
    }

    public static void setScale(ItemStack stack, float scale) {
        ModComponents.TrimConfig c = cfg(stack);
        setCfg(stack, new ModComponents.TrimConfig(c.pattern(), c.colors(), c.speed(), scale, c.glowing()));
        CustomGlint.Data preview = CustomGlint.read(stack);
        if (preview == null || preview.layers().length <= 1) {
            Identifier pattern = getPattern(stack);
            int[] colors = getColors(stack);
            if (pattern != null) CustomGlint.write(stack, pattern, colors.length > 0 ? colors : new int[]{0xFFFFFFFF}, getSpeed(stack), true, scale, false);
        }
    }

    @Override
    public Component getName(ItemStack pStack) {
        Identifier pattern = getPattern(pStack);
        if (pattern == null) return super.getName(pStack);
        String name = pattern.equals(CustomGlint.VANILLA) ? "Vanilla" : capitalize(extractPatternName(pattern));
        String prefix = isGlowing(pStack) ? "Glowing " : "";
        return Component.literal(prefix + name + " Glint Trim");
    }

    @Override
    public void appendHoverText(ItemStack pStack, Item.TooltipContext pContext, TooltipDisplay pDisplay, Consumer<Component> pTooltipComponents, TooltipFlag pIsAdvanced) {
        int[] colors = getColors(pStack);
        if (isGlowing(pStack)) {
            pTooltipComponents.accept(Component.literal("Glowing — wear full set for player outline; held items glow").withStyle(ChatFormatting.YELLOW));
        }
        if (colors.length == 0) {
            pTooltipComponents.accept(Component.literal("No color — craft with a dye to add one"));
            return;
        }
        CustomGlint.Data data = CustomGlint.read(pStack);
        if (data != null && data.layers().length > 1) {
            int n = data.layers().length;
            pTooltipComponents.accept(Component.literal("Apply with Glowstone Dust at a smithing table"));
            if (!cgHasShiftDown()) {
                pTooltipComponents.accept(Component.literal(n + " layers").withStyle(ChatFormatting.DARK_AQUA));
                pTooltipComponents.accept(Component.literal("Hold Shift").withStyle(ChatFormatting.GOLD));
            } else {
                for (int i = 0; i < n; i++) {
                    CustomGlint.Layer layer = data.layers()[i];
                    String dname = layer.design().equals(CustomGlint.VANILLA) ? "Vanilla" : capitalize(extractPatternName(layer.design()));
                    pTooltipComponents.accept(Component.literal("Layer " + (i + 1)).withStyle(ChatFormatting.WHITE));
                    pTooltipComponents.accept(Component.literal("  " + dname).withStyle(ChatFormatting.GRAY));
                    if (layer.colors().length > 0) {
                        MutableComponent lc = Component.literal("  Colors: ").withStyle(ChatFormatting.GRAY);
                        for (int k = 0; k < layer.colors().length; k++) {
                            int rgb = layer.colors()[k] & 0xFFFFFF;
                            String cname = "#" + String.format("%06X", rgb);
                            for (int j = 0; j < DYE_COLORS.length; j++) {
                                if ((DYE_COLORS[j] & 0xFFFFFF) == rgb) { cname = capitalize(DyeColor.values()[j].getName().replace("_", " ")); break; }
                            }
                            if (k > 0) lc = lc.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
                            lc = lc.append(Component.literal(cname).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))));
                        }
                        pTooltipComponents.accept(lc);
                    }
                }
            }
        } else {
            pTooltipComponents.accept(Component.literal(colors.length + " color" + (colors.length > 1 ? "s" : "") + " — apply with Glowstone Dust at a smithing table"));
            float speed = getSpeed(pStack);
            float scale = getScale(pStack);
            if (speed != 1.0f) pTooltipComponents.accept(Component.literal("Speed: " + (int) speed + "×").withStyle(ChatFormatting.AQUA));
            if (scale != 1.0f) pTooltipComponents.accept(Component.literal("Scale: " + scale + "×").withStyle(ChatFormatting.AQUA));
            MutableComponent line = Component.literal("Colors: ").withStyle(ChatFormatting.GRAY);
            for (int i = 0; i < colors.length; i++) {
                int rgb = colors[i] & 0xFFFFFF;
                String name = "#" + String.format("%06X", rgb);
                for (int j = 0; j < DYE_COLORS.length; j++) {
                    if ((DYE_COLORS[j] & 0xFFFFFF) == rgb) { name = capitalize(DyeColor.values()[j].getName().replace("_", " ")); break; }
                }
                if (i > 0) line = line.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
                line = line.append(Component.literal(name).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))));
            }
            pTooltipComponents.accept(line);
        }
    }

    public static String extractPatternName(Identifier pattern) {
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
