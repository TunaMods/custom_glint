package net.tunamods.customglint.module.advancement;

/**
 * Fires when a player finishes a Glint Trim with more than one layer (any layered trim). Layer count isn't
 * data-expressible in an item predicate, so the trim-producing paths call
 * {@link #trigger(net.minecraft.server.level.ServerPlayer)} directly. Sibling of {@link EightLayerTrimTrigger}
 * (which wants the full 8).
 */
public class LayeredTrimTrigger extends PlayerOnlyTrigger {
}
