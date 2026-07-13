package net.tunamods.customglint.module.advancement;

import net.tunamods.customglint.common.CustomGlint;

/**
 * Fires when a player finishes a Glint Trim with all 8 layers, each carrying all 8 colors. Neither count is
 * data-expressible in an item predicate, so the trim-producing paths (Glint Table print, a color/layer-adding
 * craft) test {@link #matches(CustomGlint.Data)} and call {@code trigger} directly. See {@link SimplePlayerTrigger}.
 */
public final class EightByEightTrimTrigger extends SimplePlayerTrigger {

    /** True when a trim carries all 8 layers and every layer holds all 8 colors. */
    public static boolean matches(CustomGlint.Data data) {
        if (data == null || data.layers().length < 8) return false;
        for (CustomGlint.Layer l : data.layers())
            if (l.colors().length < 8) return false;
        return true;
    }
}
