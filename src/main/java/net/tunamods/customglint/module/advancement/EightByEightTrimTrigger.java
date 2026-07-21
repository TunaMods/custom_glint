package net.tunamods.customglint.module.advancement;

import net.tunamods.customglint.common.CustomGlint;

/**
 * Fires when a player finishes a Glint Trim with all 8 layers, each carrying all 8 colors. See
 * {@link SimpleFlagTrigger}; the {@link #matches} helper is what the producing paths test before firing.
 */
public class EightByEightTrimTrigger extends SimpleFlagTrigger {
    public EightByEightTrimTrigger() {
        super(CustomGlint.res("eight_by_eight_trim"));
    }

    /** True when a trim carries all 8 layers and every layer holds all 8 colors. */
    public static boolean matches(CustomGlint.Data data) {
        if (data == null || data.layers().length < 8) return false;
        for (CustomGlint.Layer l : data.layers())
            if (l.colors().length < 8) return false;
        return true;
    }
}
