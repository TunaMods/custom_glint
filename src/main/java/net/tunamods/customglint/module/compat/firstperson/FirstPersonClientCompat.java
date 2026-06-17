package net.tunamods.customglint.module.compat.firstperson;

import net.tunamods.customglint.common.client.CustomGlintRenderer;

/**
 * Client-side half of FPM compat. Called from {@link FirstPersonCompat#register()} only on the
 * client dist (behind a {@code FMLEnvironment.getDist()} check) so this class — and its
 * {@link CustomGlintRenderer} import — is never loaded on dedicated servers.
 */
public final class FirstPersonClientCompat {
    private FirstPersonClientCompat() {}

    public static void wireRenderer() {
        CustomGlintRenderer.fpmPresent = true;
        CustomGlintRenderer.fpmRenderingPlayerGate = FirstPersonCompat::shouldSuppress;
    }
}
