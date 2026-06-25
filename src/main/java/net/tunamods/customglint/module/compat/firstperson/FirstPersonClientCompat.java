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
        // The old fpmPresent / fpmRenderingPlayerGate gates fed the removed shader-pack item-outline
        // sprite branch and no longer exist. The only remaining FPM hook is
        // CustomGlintRenderer.outlineSuppressor, which FirstPersonCompat currently leaves unset on
        // purpose (see the note in FirstPersonCompat.register). Nothing to wire here today.
    }
}
