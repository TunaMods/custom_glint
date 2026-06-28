package net.tunamods.customglint.module.compat.iceandfire;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.tunamods.customglint.module.network.GlintMountArmorSyncPacket;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Server-side helpers for syncing IaF mount armor stacks to clients. Reflection-based so we
 * don't need compileOnly on IaF. Field-name lookup is cached per concrete entity class.
 */
public final class MountArmorSync {
    private MountArmorSync() {}

    private static final Map<Class<?>, Field> INV_FIELD_CACHE = new ConcurrentHashMap<>();

    /** Last armor stack broadcast per entity id, for tick-based change detection. */
    private static final Map<Integer, ItemStack> LAST_SYNCED = new ConcurrentHashMap<>();

    /** Reflectively reads the armor stack at slot 2 of the entity's inventory field. */
    public static ItemStack readArmorStack(Entity entity, String invFieldName) {
        try {
            Field f = INV_FIELD_CACHE.get(entity.getClass());
            if (f == null) {
                f = entity.getClass().getDeclaredField(invFieldName);
                f.setAccessible(true);
                INV_FIELD_CACHE.put(entity.getClass(), f);
            }
            Container inv = (Container) f.get(entity);
            return inv == null ? ItemStack.EMPTY : inv.getItem(2);
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /** Records the latest armor stack for an entity id and reports whether it changed since last sync. */
    public static boolean changedSinceLast(int entityId, ItemStack stack) {
        ItemStack last = LAST_SYNCED.get(entityId);
        if (last != null && ItemStack.matches(last, stack)) return false;
        LAST_SYNCED.put(entityId, stack.copy());
        return true;
    }

    /** Drops the change-detection entry for an entity id (call on entity unload/death). */
    public static void forget(int entityId) {
        LAST_SYNCED.remove(entityId);
    }

    /** Broadcasts to all players tracking the entity. */
    public static void broadcast(Entity entity, ItemStack stack) {
        if (entity.level().isClientSide) return;
        PacketDistributor.sendToPlayersTrackingEntity(entity,
                new GlintMountArmorSyncPacket(entity.getId(), stack));
    }

    /** Sends current state to a single newly-tracking player. */
    public static void sendTo(ServerPlayer player, Entity entity, ItemStack stack) {
        PacketDistributor.sendToPlayer(player,
                new GlintMountArmorSyncPacket(entity.getId(), stack));
    }
}
