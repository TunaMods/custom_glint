package net.tunamods.customglint.module.advancement;

/**
 * Fires when a player finishes a Glint Trim carrying all 8 colors. There's no data-only way to count the
 * colors inside our {@code customglint:trim} component, so the two places an 8-color trim is produced (the
 * Glint Table print and a color-adding craft) call {@link #trigger(net.minecraft.server.level.ServerPlayer)}
 * directly.
 */
public class EightColorTrimTrigger extends PlayerOnlyTrigger {
}
