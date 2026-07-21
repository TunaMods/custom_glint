package net.tunamods.customglint.module.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Locale;

/** Drawing and value-formatting helpers shared by the Glint Table and wand editor screens. */
final class GuiHelpers {
    private GuiHelpers() {}

    /** Step a speed/scale value up: 0.10 increments below 1×, 0.5 above; capped at 8. */
    static float stepUp(float v) {
        float nv = v < 1.0f ? v + 0.10f : v + 0.5f;
        return Math.min(8.0f, Math.round(nv * 100f) / 100f);
    }

    /** Step a speed/scale value down: 0.5 decrements above 1×, 0.10 below; floored at 0.10. */
    static float stepDown(float v) {
        float nv = v <= 1.0f ? v - 0.10f : v - 0.5f;
        return Math.max(0.10f, Math.round(nv * 100f) / 100f);
    }

    /** Value for a stepper caption: whole numbers show as ints, fractions to at most two places with any
     *  trailing zero trimmed, so 2.0 reads "2" and 1.50 reads "1.5". */
    static String fmtVal(float v) {
        // Locale.ROOT: a comma-decimal locale would render 1.5 as "1,5" and the trailing-zero trim below
        // (and the GUI's fixed-width value fields) both assume a '.' separator.
        return v == Math.rint(v) ? String.valueOf((int) v)
                : String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "");
    }

    /** Tiny 7×8 trash-can glyph drawn with fills (the font has no trash glyph). Origin = top-left. */
    static void drawTrashIcon(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x + 2, y, x + 5, y + 1, color);          // handle nub
        g.fill(x, y + 1, x + 7, y + 2, color);          // lid
        g.fill(x + 1, y + 3, x + 6, y + 8, color);      // can body
        int slot = 0xEE111111;                          // stripes cut back to the panel colour
        g.fill(x + 2, y + 4, x + 3, y + 7, slot);
        g.fill(x + 4, y + 4, x + 5, y + 7, slot);
    }
}
