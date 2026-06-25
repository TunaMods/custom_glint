package net.tunamods.customglint.module.compat.epicknights;

import net.tunamods.customglint.common.client.CustomGlintRenderer;

/** Client-only EK wiring; isolated from {@link EpicKnightsCompat} so dedicated servers
 *  never resolve {@code CustomGlintRenderer} transitively. */
public final class EpicKnightsClientCompat {
    private EpicKnightsClientCompat() {}

    public static void wire() {
        // Release EpicKnightsGlintRT's cached decoration RenderTypes + their native fixed buffers on
        // reload, otherwise they leak and point at freed design textures.
        CustomGlintRenderer.additionalReloadCleanup.add(EpicKnightsGlintRT::releaseCaches);
    }
}
