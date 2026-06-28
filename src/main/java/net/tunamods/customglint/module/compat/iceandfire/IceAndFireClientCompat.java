package net.tunamods.customglint.module.compat.iceandfire;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
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
        // Bulk-clear the client mount-armor cache on world unload. Per-entity eviction runs on
        // EntityLeaveLevelEvent, but entity ids are reused across world loads, so a stale entry could
        // briefly mis-resolve hippogryph/hippocampus armor on a reused id without this.
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload e) -> {
            if (e.getLevel().isClientSide()) MountArmorCache.clear();
        });
    }
}
