package net.tunamods.customglint.common.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
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
            if (e != null) return new EntityGlintRender.Resolution(e.data, e.glowing, e.glowColors);
            if (!CustomGlint.hasEntity(entity)) return null;
            CompoundTag tag = CustomGlint.entityGlintTag(entity);
            if (tag.isEmpty()) return null;
            EntityGlintCache.put(id, tag);
            e = EntityGlintCache.get(id);
            if (e == null) return null;
            return new EntityGlintRender.Resolution(e.data, e.glowing, e.glowColors);
        };
        MinecraftForge.EVENT_BUS.register(EntityGlintClientInit.class);
    }

    @SubscribeEvent
    public static void onLeaveLevel(ClientPlayerNetworkEvent.LoggingOut event) {
        EntityGlintCache.clear();
    }
}
