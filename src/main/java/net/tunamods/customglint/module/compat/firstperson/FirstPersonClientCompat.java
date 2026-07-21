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
        // No-op: the stencil outline this compat once suppressed in FPM 3.5D was removed. Kept as a wired
        // DistExecutor entrypoint so an FPM suppressor can be reinstalled here without re-plumbing the dist split.
    }
}
