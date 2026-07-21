package net.tunamods.customglint.module.advancement;

import net.tunamods.customglint.common.CustomGlint;

/** Fires when a player finishes a Glint Trim with more than one layer. See {@link SimpleFlagTrigger}. */
public class LayeredTrimTrigger extends SimpleFlagTrigger {
    public LayeredTrimTrigger() {
        super(CustomGlint.res("layered_trim"));
    }
}
