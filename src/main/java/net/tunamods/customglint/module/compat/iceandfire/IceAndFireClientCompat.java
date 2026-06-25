package net.tunamods.customglint.module.compat.iceandfire;

import net.tunamods.customglint.common.client.CustomGlintRenderer;

/**
 * Client-only half of the Ice & Fire compat. Reached from {@link IceAndFireCompat#register()}
 * behind a {@code FMLEnvironment.dist == Dist.CLIENT} guard so the JVM never resolves
 * {@link CustomGlintRenderer} on a dedicated server.
 *
 * <p>The old BEWLR outline hooks (per-item outline texture / offset / flat-on-ground tables) are
 * gone: the post-process glow mask captures each item's silhouette by re-rendering it through its
 * own bound RenderType texture, so per-variant textures (troll weapons, the tide trident) are
 * traced automatically with no registration. The IaF mount/weapon glint itself lives in the
 * Layer*ArmorMixin / Render*Mixin classes.
 */
public final class IceAndFireClientCompat {
    private IceAndFireClientCompat() {}

    public static void run() {
        // Nothing to wire on the client today; kept as the Dist.CLIENT entry point in case
        // future IaF render compat needs client-only setup.
    }
}
