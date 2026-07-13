package net.tunamods.customglint.module.advancement;

/**
 * Fires when a player finishes a Glint Trim carrying all 8 colors. The color count inside our
 * {@code customglint:trim} component isn't data-expressible, so the two places an 8-color trim is produced
 * (the Glint Table print and a color-adding craft) call {@code trigger} directly. See {@link SimplePlayerTrigger}.
 */
public final class EightColorTrimTrigger extends SimplePlayerTrigger {}
