package net.tunamods.customglint.module.compat.iceandfire;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;
import net.tunamods.customglint.module.network.GlintMountArmorSyncPacket;
import net.tunamods.customglint.module.network.ModNetworking;

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

    /** Reflectively reads the armor stack at slot 2 of the entity's inventory field. */
    public static ItemStack readArmorStack(Entity entity, String invFieldName) {
        try {
            Field f = INV_FIELD_CACHE.get(entity.getClass());
            if (f == null) {
                f = entity.getClass().getField(invFieldName);
                f.setAccessible(true);
                INV_FIELD_CACHE.put(entity.getClass(), f);
            }
            Container inv = (Container) f.get(entity);
            return inv == null ? ItemStack.EMPTY : inv.getItem(2);
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /** Broadcasts to all players tracking the entity. */
    public static void broadcast(Entity entity, ItemStack stack) {
        if (entity.level().isClientSide) return;
        ModNetworking.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY.with(() -> entity),
                new GlintMountArmorSyncPacket(entity.getId(), stack));
    }

    /** Sends current state to a single newly-tracking player. */
    public static void sendTo(ServerPlayer player, Entity entity, ItemStack stack) {
        ModNetworking.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new GlintMountArmorSyncPacket(entity.getId(), stack));
    }
}
