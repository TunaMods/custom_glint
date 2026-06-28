package net.tunamods.customglint.module.compat.firstperson;

import net.tunamods.customglint.common.client.CustomGlintRenderer;

/**
 * Client-side half of FPM compat. Called from {@link FirstPersonCompat#register()} behind a
 * {@code FMLEnvironment.dist == Dist.CLIENT} guard so this class (and its {@link CustomGlintRenderer}
 * import) is never loaded on dedicated servers.
 */
public final class FirstPersonClientCompat {
    private FirstPersonClientCompat() {}

    public static void wireRenderer() {
        // The old fpmPresent / fpmRenderingPlayerGate / outlineSuppressor gates fed the previous
        // stencil/shader outline's shader-pack item-outline sprite branch, which was replaced by the
        // post-process GlowOutlineRenderer. That renderer skips first-person capture entirely today
        // (first-person hand is a deferred milestone), so FPM needs no gate. Nothing to wire here.
    }
}
