package net.tunamods.customglint.module.advancement;

import net.tunamods.customglint.common.CustomGlint;

/** Fires when a player finishes a Glint Trim with all 8 layers. See {@link SimpleFlagTrigger}. */
public class EightLayerTrimTrigger extends SimpleFlagTrigger {
    public EightLayerTrimTrigger() {
        super(CustomGlint.res("eight_layer_trim"));
    }
}
