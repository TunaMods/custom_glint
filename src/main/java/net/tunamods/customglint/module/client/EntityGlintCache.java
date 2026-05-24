package net.tunamods.customglint.module.client;

import net.minecraft.nbt.CompoundTag;
import net.tunamods.customglint.common.CustomGlint;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Client-side UUID→glint-tag cache for LivingEntities. Populated by {@link
 * net.tunamods.customglint.module.network.GlintEntitySyncPacket} on start-tracking and after
 * server-side mutations. Cleared on level unload.
 *
 * Entries store both the raw inner glint CompoundTag and a pre-decoded {@link CustomGlint.Data}
 * so {@link EntityGlintRenderer} doesn't allocate a vehicle ItemStack every frame per entity.
 */
public final class EntityGlintCache {
    private EntityGlintCache() {}

    public static final class Entry {
        @Nullable public final CustomGlint.Data data;
        public final boolean glowing;
        public final int[] glowColors;
        public Entry(@Nullable CustomGlint.Data data, boolean glowing, int[] glowColors) {
            this.data = data;
            this.glowing = glowing;
            this.glowColors = glowColors;
        }
    }

    private static final ConcurrentMap<UUID, Entry> CACHE = new ConcurrentHashMap<>();

    public static void put(UUID id, @Nullable CompoundTag glintTag) {
        if (glintTag == null || glintTag.isEmpty()) {
            CACHE.remove(id);
            return;
        }
        CustomGlint.Data data = CustomGlint.fromTag(glintTag);
        boolean glowing = CustomGlint.tagGlowing(glintTag);
        int[] glow = CustomGlint.tagGlowColors(glintTag);
        if (data == null && !glowing && glow.length == 0) {
            CACHE.remove(id);
            return;
        }
        CACHE.put(id, new Entry(data, glowing, glow));
    }

    @Nullable
    public static Entry get(UUID id) {
        return CACHE.get(id);
    }

    public static void remove(UUID id) {
        CACHE.remove(id);
    }

    public static void clear() {
        CACHE.clear();
    }
}
