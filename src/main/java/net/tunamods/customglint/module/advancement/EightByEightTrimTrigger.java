package net.tunamods.customglint.module.advancement;

import net.tunamods.customglint.common.CustomGlint;

/**
 * Fires when a player finishes a Glint Trim with all 8 layers, each carrying all 8 colors. Neither the layer
 * count nor the per-layer color count can be checked by a data-only item predicate, so the two places an 8x8
 * trim is produced (the Glint Table print and a color/layer-adding craft) call
 * {@link #trigger(net.minecraft.server.level.ServerPlayer)} directly, mirroring {@link EightLayerTrimTrigger}.
 */
public class EightByEightTrimTrigger extends PlayerOnlyTrigger {

    /** True when a trim carries all 8 layers and every layer holds all 8 colors. */
    public static boolean matches(CustomGlint.Data data) {
        if (data == null || data.layers().length < 8) return false;
        for (CustomGlint.Layer l : data.layers())
            if (l.colors().length < 8) return false;
        return true;
    }
}
