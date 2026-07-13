package net.tunamods.customglint.module.advancement;

/**
 * Fires when a player finishes a Glint Trim with all 8 layers. Layer count can't be checked by a data-only
 * item predicate, so the trim-producing paths (Glint Table print, a layer-adding craft) call {@code trigger}
 * directly. See {@link SimplePlayerTrigger}; sibling of {@link LayeredTrimTrigger} (which wants any 2+).
 */
public final class EightLayerTrimTrigger extends SimplePlayerTrigger {}
