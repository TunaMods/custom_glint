package net.tunamods.customglint.common.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.tunamods.customglint.common.CustomGlint;

import java.util.UUID;

/**
 * Client-side init for entity glints. Installs an instance-resolver hook on
 * {@link EntityGlintRender} that reads from the per-instance sync cache, and clears the cache on
 * logout. Invoked from {@code CustomGlintApiMod} on the client dist.
 *
 * Resolution order on the client:
 *  1. {@link EntityGlintCache} — populated by server broadcasts and start-tracking.
 *  2. Fallback: the entity's persistent NBT directly. Covers client-side mutations, entities
 *     reconstructed from saved NBT (capture/release mods, replay), and previews — anywhere the
 *     server packet path didn't run. On miss-with-NBT we populate the cache so the next frame
 *     hits the fast path; subsequent server broadcasts overwrite as expected.
 */
public final class EntityGlintClientInit {
    private EntityGlintClientInit() {}

    public static void run() {
        EntityGlintRender.instanceResolver = entity -> {
            UUID id = entity.getUUID();
            EntityGlintCache.Entry e = EntityGlintCache.get(id);
            if (e != null) return new EntityGlintRender.Resolution(e.data, e.glowing, e.glowColors, e.seeThrough);
            if (!CustomGlint.hasEntity(entity)) return null;
            CompoundTag tag = CustomGlint.entityGlintTag(entity);
            if (tag.isEmpty()) return null;
            EntityGlintCache.put(id, tag);
            e = EntityGlintCache.get(id);
            if (e == null) return null;
            return new EntityGlintRender.Resolution(e.data, e.glowing, e.glowColors, e.seeThrough);
        };
        NeoForge.EVENT_BUS.register(EntityGlintClientInit.class);
    }

    @SubscribeEvent
    public static void onLeaveLevel(ClientPlayerNetworkEvent.LoggingOut event) {
        EntityGlintCache.clear();
    }

    /**
     * Evict a living entity's cache entry when it leaves the client level (despawn, death, out of
     * tracking range, chunk unload). Without this the UUID→Entry map only ever cleared on logout, so a
     * long session accumulated a permanent entry for every glinted/glowing entity ever rendered — the
     * resolver fallback ({@link #run}) auto-populates on a cache miss, so it grew unbounded. When the
     * entity returns the server re-syncs (start-tracking broadcast) or the NBT fallback repopulates, so
     * eviction is safe.
     */
    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof LivingEntity living)
            EntityGlintCache.remove(living.getUUID());
    }
}
