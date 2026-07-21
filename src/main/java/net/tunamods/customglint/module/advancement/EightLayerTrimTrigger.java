package net.tunamods.customglint.module.advancement;

/**
 * Fires when a player finishes a Glint Trim with all 8 layers. Layer count can't be checked by a data-only
 * item predicate, so the two places an 8-layer trim is produced (the Glint Table print and a layer-adding
 * craft) call {@link #trigger(net.minecraft.server.level.ServerPlayer)} directly, mirroring
 * {@link EightColorTrimTrigger}.
 */
public class EightLayerTrimTrigger extends PlayerOnlyTrigger {
}
