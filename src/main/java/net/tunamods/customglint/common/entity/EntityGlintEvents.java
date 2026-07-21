package net.tunamods.customglint.common.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.network.ApiNetworking;
import net.tunamods.customglint.common.network.GlintEntitySyncPacket;

/**
 * Server-side wiring for per-instance entity glints.
 *
 * <ul>
 *   <li>On start-tracking: push the entity's current glint tag to the new viewer so the client
 *   cache is seeded before the next render.</li>
 *   <li>{@link #broadcast(LivingEntity)}: invoked after any server-side mutation; sends to all
 *   players tracking the entity.</li>
 * </ul>
 *
 * Server-safe: no client classes referenced.
 */
public final class EntityGlintEvents {
    private EntityGlintEvents() {}

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        Entity target = event.getTarget();
        if (!(target instanceof LivingEntity le)) return;
        if (!CustomGlint.hasEntity(le)) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        CompoundTag tag = CustomGlint.entityGlintTag(le);
        ApiNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp),
                new GlintEntitySyncPacket(le.getId(), tag));
    }

    public static void broadcast(LivingEntity entity) {
        if (entity.level().isClientSide) return;
        CompoundTag tag = CustomGlint.entityGlintTag(entity);
        ApiNetworking.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> entity),
                new GlintEntitySyncPacket(entity.getId(), tag));
    }
}
