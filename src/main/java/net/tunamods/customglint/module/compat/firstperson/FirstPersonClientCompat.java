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
        // The stencil outline system this compat suppressed in FPM 3.5D has been removed.
        // Left as a no-op placeholder; the post-process glow-outline port will reinstall an
        // FPM suppressor here.
    }
}
