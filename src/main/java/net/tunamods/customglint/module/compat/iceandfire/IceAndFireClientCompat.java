package net.tunamods.customglint.module.compat.iceandfire;

import net.tunamods.customglint.common.client.CustomGlintRenderer;

/**
 * Client-only half of the Ice & Fire compat. Reached from {@link IceAndFireCompat#register()}
 * via {@code DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> IceAndFireClientCompat::run)} so the
 * JVM never resolves {@link CustomGlintRenderer} on a dedicated server.
 *
 * <p>Previously registered per-item BEWLR outline textures/offsets for the stencil outline system.
 * That system has been removed; this is now a no-op placeholder. The IaF BEWLR glint draws live in
 * {@code RenderTrollWeaponMixin} / {@code RenderDeathWormGauntletMixin}.
 */
public final class IceAndFireClientCompat {
    private IceAndFireClientCompat() {}

    public static void run() {
    }
}
