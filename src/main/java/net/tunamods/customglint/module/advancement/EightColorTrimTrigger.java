package net.tunamods.customglint.module.advancement;

import net.tunamods.customglint.common.CustomGlint;

/** Fires when a player finishes a Glint Trim carrying all 8 colors. See {@link SimpleFlagTrigger}. */
public class EightColorTrimTrigger extends SimpleFlagTrigger {
    public EightColorTrimTrigger() {
        super(CustomGlint.res("eight_color_trim"));
    }
}
