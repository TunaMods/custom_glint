package net.tunamods.customglint.module.compat.iceandfire;

import net.minecraft.world.item.ItemStack;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of IaF mount armor ItemStacks keyed by entity id. IaF stores hippogryph and
 * hippocampus armor in a SimpleContainer that doesn't auto-sync to clients (only the armor tier
 * int syncs via EntityDataAccessor) — so without this cache the client has no access to the
 * actual ItemStack's CustomGlint NBT. Populated by GlintMountArmorSyncPacket handler; consumed
 * by LayerHippogryph/HippocampusArmorMixin.
 */
public final class MountArmorCache {
    private static final ConcurrentHashMap<Integer, ItemStack> CACHE = new ConcurrentHashMap<>();
    private MountArmorCache() {}

    public static void put(int entityId, ItemStack stack) {
        if (stack == null || stack.isEmpty()) CACHE.remove(entityId);
        else CACHE.put(entityId, stack);
    }

    public static ItemStack get(int entityId) {
        ItemStack s = CACHE.get(entityId);
        return s == null ? ItemStack.EMPTY : s;
    }

    public static void remove(int entityId) { CACHE.remove(entityId); }
    public static void clear() { CACHE.clear(); }
}
