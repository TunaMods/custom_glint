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
import java.util.concurrent.CopyOnWriteArrayList;

public class GlintTrimItem extends Item {
    public static final String PATTERN_TAG  = "pattern";
    public static final String COLORS_TAG   = "colors";
    public static final String SPEED_TAG    = "speed";
    public static final String SCALE_TAG    = "scale";
    public static final String GLOWING_TAG  = "glowing";
    public static final String SCROLL_TAG   = "scroll";
    public static final String OFFSET_TAG   = "offset";
    public static final String SEED_TAG     = "seed";

    // ARGB per DyeColor ordinal: WHITE ORANGE MAGENTA LIGHT_BLUE YELLOW LIME PINK
    //                             GRAY  LIGHT_GRAY CYAN PURPLE BLUE BROWN GREEN RED BLACK
    public static final int[] DYE_COLORS = {
        0xFFF9FFFE, 0xFFFF8000, 0xFFFF00FF, 0xFF00AAFF, 0xFFFFFF00, 0xFF00FF00, 0xFFFF80A0,
        0xFF808080, 0xFFAAAAAA, 0xFF00FFFF, 0xFF8800CC, 0xFF0000FF, 0xFF885522, 0xFF008800,
        0xFFFF0000, 0xFF000000
    };

    // CopyOnWriteArrayList: the datapack reload listener (CustomGlintMod) mutates this on the server
    // thread while the client thread iterates it (creative-tab build) in single-player/LAN - a plain
    // ArrayList would throw ConcurrentModificationException on the timing overlap.
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

    @Nullable
    public static ResourceLocation getPattern(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains(PATTERN_TAG)) return null;
        return ResourceLocation.tryParse(stack.getTag().getString(PATTERN_TAG));
    }

    /** Synthetic colour a non-chromatic empty layer carries so it stays renderable (read() rejects a
     *  zero-colour non-chromatic layer). It is NOT a player-chosen colour: alpha 0xFE sits outside the
     *  discrete opacity-step alphas any real colour carries, so it stays distinguishable from a genuine
     *  full-opacity white (0xFFFFFFFF) the player picks via hex - which must survive on printed/imported
     *  trims. Renders as plain white (254/255 brightness). Tested exactly, never by RGB alone. */
    public static final int EMPTY_FILL = 0xFEFFFFFF;

    /** The color array to bake into the preview glint: chromatic passes the raw list (an empty list renders
     *  with the white/grey/dark-grey "empty" palette), every other design needs at least one color. */
    public static int[] writeColors(ResourceLocation pattern, int[] colors) {
        if (CustomGlint.isChromatic(pattern)) return colors;
        return colors.length > 0 ? colors : new int[]{EMPTY_FILL};
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

    public static void setPattern(ItemStack stack, ResourceLocation pattern) {
        stack.getOrCreateTag().putString(PATTERN_TAG, pattern.toString());
        // Roll a stable per-trim seed the first time a trim becomes chromatic; keep it on later edits so the
        // oil-slick pattern doesn't reshuffle every time the player dyes / re-speeds the trim.
        if (CustomGlint.isChromatic(pattern) && getSeed(stack) == 0)
            stack.getOrCreateTag().putInt(SEED_TAG, CustomGlint.randomChromaticSeed());
        String name = pattern.equals(CustomGlint.VANILLA) ? "vanilla" : extractPatternName(pattern);
        int idx = PATTERNS.indexOf(name);
        // CustomModelData: glowing variants sit in the +1000 band; +1 keeps 0 meaning "no override".
        if (idx >= 0) stack.getOrCreateTag().putInt("CustomModelData", (isGlowing(stack) ? 1000 : 0) + idx + 1);
        CustomGlint.write(stack, pattern, writeColors(pattern, getColors(stack)), getSpeed(stack), true,
                getScale(stack), false, getScrollDir(stack), getScrollOffset(stack), getSeed(stack));
    }

    /** Replaces the whole color set (Glint Table build / dye recipe), re-emitting the preview glint. */
    public static void setColors(ItemStack stack, int[] colors) {
        // Cap at 8 colors (design limit); addColor/mergeColors already cap on their paths.
        if (colors.length > 8) colors = Arrays.copyOf(colors, 8);
        stack.getOrCreateTag().put(COLORS_TAG, new IntArrayTag(colors));
        rewritePreview(stack);
    }

    /** A preview/display trim with the given pattern and colors - for recipe {@code getResultItem}/{@code
     *  getIngredients} and JEI sample stacks. Not a gameplay item. */
    public static ItemStack example(ResourceLocation pattern, int... colors) {
        ItemStack stack = new ItemStack(ModItems.GLINT_TRIM.get());
        setPattern(stack, pattern);
        for (int c : colors) addColor(stack, c);
        return stack;
    }

    /** Procedural-chromatic seed (0 = not chromatic / not yet rolled). */
    public static int getSeed(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains(SEED_TAG)) return 0;
        return stack.getTag().getInt(SEED_TAG);
    }

    /** Forces the trim's stored seed (keeps the trim NBT in sync with a glint Data written separately, e.g.
     *  the give-trim packet / Glint Table print that overwrite the Data with seeded layers). */
    public static void setSeed(ItemStack stack, int seed) {
        stack.getOrCreateTag().putInt(SEED_TAG, seed);
    }

    public static int getScrollDir(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains(SCROLL_TAG)) return CustomGlint.SCROLL_E;
        return stack.getTag().getInt(SCROLL_TAG);
    }

    public static void setScrollDir(ItemStack stack, int scrollDir) {
        stack.getOrCreateTag().putInt(SCROLL_TAG, scrollDir);
        rewritePreview(stack);
    }

    public static float getScrollOffset(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains(OFFSET_TAG)) return 0.0f;
        return stack.getTag().getFloat(OFFSET_TAG);
    }

    public static void setScrollOffset(ItemStack stack, float offset) {
        stack.getOrCreateTag().putFloat(OFFSET_TAG, offset);
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

    public static int[] getColors(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains(COLORS_TAG)) return new int[0];
        return stack.getTag().getIntArray(COLORS_TAG);
    }

    /** Colours of the first glint layer (layer 1 in the editor's 1-indexed terms), read from the
     *  authoritative multi-layer preview Data. The flat {@link #getColors} array tracks whichever layer is
     *  focused in the editor, so the auto-glow tint must not use it - the glow always follows layer 1.
     *  Falls back to the flat colours for a bare trim that has no preview Data yet. */
    public static int[] getBaseLayerColors(ItemStack stack) {
        CustomGlint.Data data = CustomGlint.read(stack);
        if (data != null && data.layers().length > 0) return data.layers()[0].colors();
        return getColors(stack);
    }

    public static boolean addColor(ItemStack stack, int color) {
        int[] current = getColors(stack);
        if (current.length >= 8) return false;
        int[] next = Arrays.copyOf(current, current.length + 1);
        next[current.length] = color;
        stack.getOrCreateTag().put(COLORS_TAG, new IntArrayTag(next));
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
        result.getOrCreateTag().put(COLORS_TAG, new IntArrayTag(merged));
        rewritePreview(result);
        return result;
    }

    public static float getSpeed(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains(SPEED_TAG)) return 1.0f;
        return stack.getTag().getFloat(SPEED_TAG);
    }

    public static void setSpeed(ItemStack stack, float speed) {
        stack.getOrCreateTag().putFloat(SPEED_TAG, speed);
        rewritePreview(stack);
    }

    public static boolean isGlowing(ItemStack stack) {
        if (!stack.hasTag()) return false;
        return stack.getTag().getBoolean(GLOWING_TAG);
    }

    public static void setGlowing(ItemStack stack, boolean glowing) {
        stack.getOrCreateTag().putBoolean(GLOWING_TAG, glowing);
        ResourceLocation pattern = getPattern(stack);
        if (pattern == null) return;
        // A multi-layer trim's glint Data is authoritative (from /glint extract or the Glint Table).
        // setPattern re-emits a SINGLE-layer preview, which would silently drop the extra layers, so for
        // multi-layer trims only refresh the CustomModelData glow offset and leave the Data intact.
        CustomGlint.Data data = CustomGlint.read(stack);
        if (data != null && data.layers().length > 1) {
            String name = pattern.equals(CustomGlint.VANILLA) ? "vanilla" : extractPatternName(pattern);
            int idx = PATTERNS.indexOf(name);
            if (idx >= 0) stack.getOrCreateTag().putInt("CustomModelData", (glowing ? 1000 : 0) + idx + 1);
        } else {
            setPattern(stack, pattern);
        }
    }

    public static float getScale(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains(SCALE_TAG)) return 1.0f;
        return stack.getTag().getFloat(SCALE_TAG);
    }

    public static void setScale(ItemStack stack, float scale) {
        stack.getOrCreateTag().putFloat(SCALE_TAG, scale);
        rewritePreview(stack);
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
    public void appendHoverText(ItemStack pStack, @Nullable Level pLevel, List<Component> pTooltipComponents, TooltipFlag pIsAdvanced) {
        int[] colors = getColors(pStack);
        if (isGlowing(pStack)) {
            pTooltipComponents.add(Component.literal("Glowing. Wear full set for player outline; held items glow").withStyle(ChatFormatting.YELLOW));
        }
        if (colors.length == 0) {
            pTooltipComponents.add(Component.literal("No color. Craft with a dye to add one"));
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
                    if (layer.colors().length > 0)
                        pTooltipComponents.add(colorLine("  Colors: ", layer.colors()));
                }
            }
        } else {
            pTooltipComponents.add(Component.literal(colors.length + " color" + (colors.length > 1 ? "s" : "") + ", apply with Glowstone Dust at a smithing table"));
            float speed = getSpeed(pStack);
            float scale = getScale(pStack);
            if (speed != 1.0f) pTooltipComponents.add(Component.literal("Speed: " + (int) speed + "×").withStyle(ChatFormatting.AQUA));
            if (scale != 1.0f) pTooltipComponents.add(Component.literal("Scale: " + scale + "×").withStyle(ChatFormatting.AQUA));
            int scroll = getScrollDir(pStack);
            if (scroll != CustomGlint.SCROLL_E)
                pTooltipComponents.add(Component.literal("Scroll: ").append(scrollLabel(scroll)).withStyle(ChatFormatting.AQUA));
            pTooltipComponents.add(colorLine("Colors: ", colors));
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

    /** A named trim's hover name tinted to {@code rgb}, for wand / Glint Table prints and imports.
     *  Callers derive rgb themselves (the wire packs it as {@code (rgb << 8) | alpha}). */
    public static Component coloredName(String name, int rgb) {
        return Component.literal(name).withStyle(s -> s.withColor(TextColor.fromRgb(rgb)));
    }

    /** Tooltip line "prefix c1, c2, ..." with each color name tinted to its own RGB.
     *  Shared by the single-layer, per-layer, and Glow Trim tooltips. */
    public static MutableComponent colorLine(String prefix, int[] colors) {
        MutableComponent line = Component.literal(prefix).withStyle(ChatFormatting.GRAY);
        for (int i = 0; i < colors.length; i++) {
            int rgb = colors[i] & 0xFFFFFF;
            if (i > 0) line = line.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
            line = line.append(Component.literal(dyeName(rgb)).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))));
        }
        return line;
    }

    /** Display name for a color: the matching vanilla dye name (capitalized), else "#RRGGBB". */
    public static String dyeName(int argb) {
        int rgb = argb & 0xFFFFFF;
        for (int j = 0; j < DYE_COLORS.length; j++) {
            if ((DYE_COLORS[j] & 0xFFFFFF) == rgb) return capitalize(DyeColor.values()[j].getName().replace("_", " "));
        }
        return "#" + String.format("%06X", rgb);
    }
}
