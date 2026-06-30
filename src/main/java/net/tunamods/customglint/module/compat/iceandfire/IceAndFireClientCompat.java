package net.tunamods.customglint.module.compat.iceandfire;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.tunamods.customglint.common.client.CustomGlintRenderer;

/**
 * Client-only half of the Ice & Fire compat. Reached from {@link IceAndFireCompat#register()}
 * via {@code DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> IceAndFireClientCompat::run)} so the
 * JVM never resolves {@link CustomGlintRenderer} on a dedicated server.
 *
 * <p>Previously registered per-item BEWLR outline textures/offsets for the stencil outline system.
 * That system has been removed. The IaF BEWLR glint draws live in {@code RenderTrollWeaponMixin} /
 * {@code RenderDeathWormGauntletMixin}.
 *
 * <p>Clears {@link MountArmorCache} on logout. Per-entity-leave eviction covers normal play, but a
 * disconnect / dimension change that doesn't cleanly fire {@code EntityLeaveLevelEvent} for every
 * tracked mount would otherwise leave stale stacks keyed by reusable entity ids.
 */
public final class IceAndFireClientCompat {
    private IceAndFireClientCompat() {}

    public static void run() {
        MinecraftForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingOut e) -> MountArmorCache.clear());
    }
}
