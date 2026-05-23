package net.tunamods.customglint.module.compat.firstperson;

import net.tunamods.customglint.common.client.CustomGlintRenderer;

/**
 * Client-side half of FPM compat. Called via {@code DistExecutor.safeRunWhenOn(Dist.CLIENT, ...)}
 * from {@link FirstPersonCompat#register()} so this class (and its {@link CustomGlintRenderer}
 * import) is never loaded on dedicated servers.
 */
public final class FirstPersonClientCompat {
    private FirstPersonClientCompat() {}

    public static void wireRenderer() {
        CustomGlintRenderer.fpmPresent = true;
        CustomGlintRenderer.fpmRenderingPlayerGate = FirstPersonCompat::shouldSuppress;
    }
}
