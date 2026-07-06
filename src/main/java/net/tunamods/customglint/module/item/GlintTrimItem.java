package net.tunamods.customglint.module.item;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomModelData;
import net.tunamods.customglint.common.CustomGlint;

public class GlintTrimItem extends Item {
    // ARGB per DyeColor ordinal: WHITE ORANGE MAGENTA LIGHT_BLUE YELLOW LIME PINK
    //                             GRAY  LIGHT_GRAY CYAN PURPLE BLUE BROWN GREEN RED BLACK
    public static final int[] DYE_COLORS = {
        0xFFF9FFFE, 0xFFFF8000, 0xFFFF00FF, 0xFF00AAFF, 0xFFFFFF00, 0xFF00FF00, 0xFFFF80A0,
        0xFF808080, 0xFFAAAAAA, 0xFF00FFFF, 0xFF8800CC, 0xFF0000FF, 0xFF885522, 0xFF008800,
        0xFFFF0000, 0xFF000000
    };

    // CopyOnWriteArrayList: the design-sync handler (client thread) mutates this via removeAll/add
    // while loot/creative-tab/GUI code iterates it on the server/render thread. In singleplayer a
    // datapack /reload while a wand editor or Glint Table is open races those, so the list must be
    // snapshot-iterating. Writes are rare (join / reload) and the list is tiny, so the copy is cheap.
    public static final List<String> PATTERNS = new CopyOnWriteArrayList<>(List.of(
        "arcs", "aurora", "blobs", "cascade", "checker", "chevron", "coral", "cracks",
        "crosshatch", "crystal", "debris", "diamonds", "dunes", "ember", "feather", "fire",
        "frost", "glitch", "glow", "grid", "halo", "hexagon", "lightning", "marble",
        "matrix", "mesh", "mosaic", "net", "oil", "petal", "plasma", "plate",
        "prism", "pulse", "ripple", "sand", "scales", "sheen", "shimmer", "silk",
        "slash", "smoke", "solid", "sparkle", "stars", "static", "stripes", "swirl",
        "tide", "tile", "vanilla", "vein", "wave", "weave", "zigzag", "chromatic"
    ));

    public GlintTrimItem(Properties pProperties) {
        super(pProperties);
    }

    // The Trim's editable config (pattern/colors/speed/scale/scroll/offset/glowing/seed) is a typed data
    // component (ModComponents.TRIM), independent of the preview glint CustomGlint stores in its own glint
    // component. CustomModelData (set in setPattern) is vanilla's own component.

    private static ModComponents.TrimConfig cfg(ItemStack stack) {
        ModComponents.TrimConfig c = stack.get(ModComponents.TRIM.get());
        return c == null ? ModComponents.TrimConfig.EMPTY : c;
    }

    private static void setCfg(ItemStack stack, ModComponents.TrimConfig c) {
        stack.set(ModComponents.TRIM.get(), c);
    }

    /** Removes the trim config entirely (used by the Black Tear, which then re-sets just the pattern). */
    public static void clear(ItemStack stack) {
        stack.remove(ModComponents.TRIM.get());
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

    /** Procedural-chromatic seed (0 = not chromatic / not yet rolled). */
    public static int getSeed(ItemStack stack) {
        return cfg(stack).seed();
    }

    /** Forces the trim's stored seed (keeps the TrimConfig in sync with a glint Data written separately,
     *  e.g. the give-trim packet / Glint Table print that overwrite the Data with seeded layers). */
    public static void setSeed(ItemStack stack, int seed) {
        ModComponents.TrimConfig c = cfg(stack);
        setCfg(stack, new ModComponents.TrimConfig(c.pattern(), c.colors(), c.speed(), c.scale(),
                c.scroll(), c.offset(), c.glowing(), seed));
    }

    /** The color array to bake into the preview glint: chromatic passes the raw list (an empty list renders
     *  with the white/grey/dark-grey "empty" palette), every other design needs at least one color. */
    public static int[] writeColors(ResourceLocation pattern, int[] colors) {
        if (CustomGlint.isChromatic(pattern)) return colors;
        return colors.length > 0 ? colors : new int[]{0xFFFFFFFF};
    }

    /** Re-emit the single-layer preview glint from the current config (no-op for a multi-layer trim, whose
     *  glint Data is authoritative and carries its own per-layer scroll). */
    private static void rewritePreview(ItemStack stack) {
        CustomGlint.Data preview = CustomGlint.read(stack);
        if (preview != null && preview.layers().length > 1) return;
        ResourceLocation pattern = getPattern(stack);
        if (pattern == null) return;
        CustomGlint.write(stack, pattern, writeColors(pattern, getColors(stack)), getSpeed(stack), true,
                getScale(stack), false, getScrollDir(stack), getScrollOffset(stack), getSeed(stack));
    }

    @Nullable
    public static ResourceLocation getPattern(ItemStack stack) {
        return cfg(stack).pattern().orElse(null);
    }

    public static void setPattern(ItemStack stack, ResourceLocation pattern) {
        ModComponents.TrimConfig c = cfg(stack);
        // Roll a stable per-trim seed the first time a trim becomes chromatic; keep it on later edits so the
        // oil-slick pattern doesn't reshuffle every time the player dyes / re-speeds the trim.
        int seed = c.seed();
        if (CustomGlint.isChromatic(pattern) && seed == 0) seed = CustomGlint.randomChromaticSeed();
        setCfg(stack, new ModComponents.TrimConfig(Optional.of(pattern), c.colors(), c.speed(), c.scale(), c.scroll(), c.offset(), c.glowing(), seed));
        String name = pattern.equals(CustomGlint.VANILLA) ? "vanilla" : extractPatternName(pattern);
        int idx = PATTERNS.indexOf(name);
        if (idx >= 0) stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData((isGlowing(stack) ? 1000 : 0) + idx + 1));
        CustomGlint.write(stack, pattern, writeColors(pattern, getColors(stack)), getSpeed(stack), true, getScale(stack), false, getScrollDir(stack), getScrollOffset(stack), seed);
    }

    /** Sets the display config (pattern/speed/scale/scroll/offset/glowing) directly, preserving the current
     *  colors and WITHOUT rewriting the preview glint — for {@code /glint extract} on a multi-layer glint,
     *  where the full multi-layer tag is copied separately and must not be clobbered by a single-layer write. */
    public static void setConfig(ItemStack stack, ResourceLocation pattern, float speed, float scale, int scroll, float offset, boolean glowing) {
        ModComponents.TrimConfig c = cfg(stack);
        setCfg(stack, new ModComponents.TrimConfig(Optional.of(pattern), c.colors(), speed, scale, scroll, offset, glowing, c.seed()));
    }

    public static int[] getColors(ItemStack stack) {
        return toIntArray(cfg(stack).colors());
    }

    /** Replaces the whole color set (Glint Table build / dye recipe), re-emitting the preview glint. */
    public static void setColors(ItemStack stack, int[] colors) {
        ModComponents.TrimConfig c = cfg(stack);
        setCfg(stack, new ModComponents.TrimConfig(c.pattern(), toList(colors), c.speed(), c.scale(), c.scroll(), c.offset(), c.glowing(), c.seed()));
        rewritePreview(stack);
    }

    public static boolean addColor(ItemStack stack, int color) {
        int[] current = getColors(stack);
        if (current.length >= 8) return false;
        int[] next = Arrays.copyOf(current, current.length + 1);
        next[current.length] = color;
        ModComponents.TrimConfig c = cfg(stack);
        setCfg(stack, new ModComponents.TrimConfig(c.pattern(), toList(next), c.speed(), c.scale(), c.scroll(), c.offset(), c.glowing(), c.seed()));
        rewritePreview(stack);
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
        setCfg(result, new ModComponents.TrimConfig(c.pattern(), toList(merged), c.speed(), c.scale(), c.scroll(), c.offset(), c.glowing(), c.seed()));
        rewritePreview(result);
        return result;
    }

    public static float getSpeed(ItemStack stack) {
        return cfg(stack).speed();
    }

    public static void setSpeed(ItemStack stack, float speed) {
        ModComponents.TrimConfig c = cfg(stack);
        setCfg(stack, new ModComponents.TrimConfig(c.pattern(), c.colors(), speed, c.scale(), c.scroll(), c.offset(), c.glowing(), c.seed()));
        rewritePreview(stack);
    }

    public static boolean isGlowing(ItemStack stack) {
        return cfg(stack).glowing();
    }

    public static void setGlowing(ItemStack stack, boolean glowing) {
        ModComponents.TrimConfig c = cfg(stack);
        setCfg(stack, new ModComponents.TrimConfig(c.pattern(), c.colors(), c.speed(), c.scale(), c.scroll(), c.offset(), glowing, c.seed()));
        ResourceLocation pattern = getPattern(stack);
        if (pattern == null) return;
        // A multi-layer trim's glint Data is authoritative (from /glint extract or the Glint Table).
        // setPattern re-emits a SINGLE-layer preview, which would silently drop the extra layers, so for
        // multi-layer trims only refresh the CustomModelData glow offset and leave the Data intact.
        CustomGlint.Data data = CustomGlint.read(stack);
        if (data != null && data.layers().length > 1) {
            String name = pattern.equals(CustomGlint.VANILLA) ? "vanilla" : extractPatternName(pattern);
            int idx = PATTERNS.indexOf(name);
            if (idx >= 0) stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData((glowing ? 1000 : 0) + idx + 1));
        } else {
            setPattern(stack, pattern);
        }
    }

    public static float getScale(ItemStack stack) {
        return cfg(stack).scale();
    }

    public static void setScale(ItemStack stack, float scale) {
        ModComponents.TrimConfig c = cfg(stack);
        setCfg(stack, new ModComponents.TrimConfig(c.pattern(), c.colors(), c.speed(), scale, c.scroll(), c.offset(), c.glowing(), c.seed()));
        rewritePreview(stack);
    }

    // ── Scroll direction / static offset ───────────────────────────────────────

    public static int getScrollDir(ItemStack stack) {
        return cfg(stack).scroll();
    }

    public static void setScrollDir(ItemStack stack, int scrollDir) {
        ModComponents.TrimConfig c = cfg(stack);
        setCfg(stack, new ModComponents.TrimConfig(c.pattern(), c.colors(), c.speed(), c.scale(), scrollDir, c.offset(), c.glowing(), c.seed()));
        rewritePreview(stack);
    }

    public static float getScrollOffset(ItemStack stack) {
        return cfg(stack).offset();
    }

    public static void setScrollOffset(ItemStack stack, float offset) {
        ModComponents.TrimConfig c = cfg(stack);
        setCfg(stack, new ModComponents.TrimConfig(c.pattern(), c.colors(), c.speed(), c.scale(), c.scroll(), offset, c.glowing(), c.seed()));
        rewritePreview(stack);
    }

    /** Stable internal name for a {@code SCROLL_*} value, for tooltips/commands/JSON. */
    public static String scrollName(int scroll) {
        return switch (scroll) {
            case CustomGlint.SCROLL_STATIC -> "static";
            case CustomGlint.SCROLL_E  -> "east";
            case CustomGlint.SCROLL_NE -> "northeast";
            case CustomGlint.SCROLL_N  -> "north";
            case CustomGlint.SCROLL_NW -> "northwest";
            case CustomGlint.SCROLL_W  -> "west";
            case CustomGlint.SCROLL_SW -> "southwest";
            case CustomGlint.SCROLL_S  -> "south";
            case CustomGlint.SCROLL_SE -> "southeast";
            default -> "east";
        };
    }

    /** Localized direction label for GUI buttons, keyed on the stable {@link #scrollName} code. */
    public static Component scrollLabel(int scroll) {
        return Component.translatable("screen.customglint.scroll." + scrollName(scroll));
    }

    /** Inverse of {@link #scrollName}: parses a direction name to a {@code SCROLL_*} value (default East). */
    public static int scrollFromName(String name) {
        return switch (name.toLowerCase()) {
            case "static" -> CustomGlint.SCROLL_STATIC;
            case "ne", "northeast" -> CustomGlint.SCROLL_NE;
            case "n", "north" -> CustomGlint.SCROLL_N;
            case "nw", "northwest" -> CustomGlint.SCROLL_NW;
            case "w", "west" -> CustomGlint.SCROLL_W;
            case "sw", "southwest" -> CustomGlint.SCROLL_SW;
            case "s", "south" -> CustomGlint.SCROLL_S;
            case "se", "southeast" -> CustomGlint.SCROLL_SE;
            default -> CustomGlint.SCROLL_E;
        };
    }

    @Override
    public Component getName(ItemStack pStack) {
        ResourceLocation pattern = getPattern(pStack);
        if (pattern == null) return super.getName(pStack);
        String name = pattern.equals(CustomGlint.VANILLA) ? "Vanilla" : capitalize(extractPatternName(pattern));
        String prefix = isGlowing(pStack) ? "Glowing " : "";
        return Component.literal(prefix + name + " Glint Trim");
    }

    @Override
    public void appendHoverText(ItemStack pStack, Item.TooltipContext pContext, List<Component> pTooltipComponents, TooltipFlag pIsAdvanced) {
        int[] colors = getColors(pStack);
        if (isGlowing(pStack)) {
            pTooltipComponents.add(Component.literal("Glowing — wear full set for player outline; held items glow").withStyle(ChatFormatting.YELLOW));
        }
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
                                if ((DYE_COLORS[j] & 0xFFFFFF) == rgb) { cname = capitalize(DyeColor.values()[j].getName().replace("_", " ")); break; }
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
            float speed = getSpeed(pStack);
            float scale = getScale(pStack);
            if (speed != 1.0f) pTooltipComponents.add(Component.literal("Speed: " + (int) speed + "×").withStyle(ChatFormatting.AQUA));
            if (scale != 1.0f) pTooltipComponents.add(Component.literal("Scale: " + scale + "×").withStyle(ChatFormatting.AQUA));
            int scroll = getScrollDir(pStack);
            if (scroll != CustomGlint.SCROLL_E)
                pTooltipComponents.add(Component.literal("Scroll: ").append(scrollLabel(scroll)).withStyle(ChatFormatting.AQUA));
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
            pTooltipComponents.add(line);
        }
    }

    public static String extractPatternName(ResourceLocation pattern) {
        String path = pattern.getPath();
        int slash = path.lastIndexOf('/');
        int dot   = path.lastIndexOf('.');
        // A ResourceLocation path may legally contain both '.' and '/'. If the last dot precedes the
        // last slash (e.g. "tex.png/x"), substring(slash+1, dot) would underflow — clamp to the end.
        if (dot <= slash) dot = path.length();
        return path.substring(slash + 1, dot);
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
