package net.tunamods.customglint.module.compat.epicknights;

import net.tunamods.customglint.common.client.CustomGlintRenderer;

/** Client-only EK wiring; isolated from {@link EpicKnightsCompat} so dedicated servers
 *  never resolve {@code CustomGlintRenderer} transitively.
 *
 *  <p>Previously installed the EK-specific outline hooks (halfarmor arm-hide predicate,
 *  WingedHussar symmetrized outline-mask baking, outline texture remap) for the stencil outline
 *  system. That system has been removed; this is now a no-op placeholder. EK decoration glint is
 *  still drawn by {@link EpicKnightsGlintRT} via {@code ArmorDecorationLayerMixin}. */
public final class EpicKnightsClientCompat {
    private EpicKnightsClientCompat() {}

    public static void wire() {
    }
}
