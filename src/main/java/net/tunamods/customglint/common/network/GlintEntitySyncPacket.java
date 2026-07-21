package net.tunamods.customglint.common.network;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.EntityGlintCache;

import java.util.function.Supplier;

/**
 * S→C: pushes a LivingEntity's per-instance glint NBT (the inner {@code customglint} compound)
 * to tracking players. Empty tag clears the cache entry. Broadcast on start-tracking and after
 * any server-side mutation.
 */
public class GlintEntitySyncPacket {

    private final int entityId;
    private final CompoundTag glintTag;

    public GlintEntitySyncPacket(int entityId, CompoundTag glintTag) {
        this.entityId = entityId;
        this.glintTag = glintTag == null ? new CompoundTag() : glintTag;
    }

    public static void encode(GlintEntitySyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.entityId);
        buf.writeNbt(pkt.glintTag);
    }

    public static GlintEntitySyncPacket decode(FriendlyByteBuf buf) {
        return new GlintEntitySyncPacket(buf.readVarInt(), buf.readNbt()); // ctor substitutes an empty tag for null
    }

    public static void handle(GlintEntitySyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> applyClient(pkt)));
        ctx.get().setPacketHandled(true);
    }

    private static void applyClient(GlintEntitySyncPacket pkt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity e = mc.level.getEntity(pkt.entityId);
        if (!(e instanceof LivingEntity le)) return;
        CustomGlint.writeEntityTag(le, pkt.glintTag);
        EntityGlintCache.put(le.getUUID(), pkt.glintTag);
    }
}
