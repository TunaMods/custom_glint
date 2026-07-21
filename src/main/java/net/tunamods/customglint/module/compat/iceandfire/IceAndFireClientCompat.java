package net.tunamods.customglint.module.compat.iceandfire;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;

/**
 * Client-only half of the Ice & Fire compat, reached from {@link IceAndFireCompat#register()} via
 * {@code DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> IceAndFireClientCompat::run)}. Everything
 * that touches the renderer lives in the client mixins ({@code RenderTrollWeaponMixin},
 * {@code RenderDeathWormGauntletMixin}, the three armor layer mixins); all this side does is clear
 * {@link MountArmorCache} on logout.
 *
 * <p>Per-entity-leave eviction covers normal play, but a disconnect or dimension change that
 * doesn't cleanly fire {@code EntityLeaveLevelEvent} for every tracked mount would otherwise leave
 * stale stacks keyed by reusable entity ids.
 */
public final class IceAndFireClientCompat {
    private IceAndFireClientCompat() {}

    public static void run() {
        MinecraftForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingOut e) -> MountArmorCache.clear());
    }
}
