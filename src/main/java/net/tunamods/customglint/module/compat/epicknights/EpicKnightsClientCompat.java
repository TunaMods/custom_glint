package net.tunamods.customglint.module.compat.epicknights;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.tunamods.customglint.common.client.CustomGlintRenderer;

/** Client-only EK wiring; isolated from {@link EpicKnightsCompat} so dedicated servers
 *  never resolve {@code CustomGlintRenderer} transitively.
 *
 *  <p>Previously installed the EK-specific outline hooks (halfarmor arm-hide predicate,
 *  WingedHussar symmetrized outline-mask baking, outline texture remap) for the stencil outline
 *  system. That system has been removed. EK decoration glint is still drawn by
 *  {@link EpicKnightsGlintRT} via {@code ArmorDecorationLayerMixin}.
 *
 *  <p>Also clears {@link EpicKnightsGlintRT}'s per-slot / per-shader RT caches on logout. Those
 *  caches key partly on the per-frame stencil slot, so without a logout clear they accumulate dead
 *  RenderTypes + BufferBuilders for the whole session (reload cleanup alone doesn't cover it). */
public final class EpicKnightsClientCompat {
    private EpicKnightsClientCompat() {}

    public static void wire() {
        MinecraftForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingOut e) -> EpicKnightsGlintRT.clearCaches());
    }
}
