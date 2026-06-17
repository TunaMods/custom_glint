package net.tunamods.customglint.common.network;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.CustomGlintApiMod;
import net.tunamods.customglint.common.client.EntityGlintCache;

/**
 * S→C: pushes a LivingEntity's per-instance glint NBT (the inner {@code customglint} compound)
 * to tracking players. Empty tag clears the cache entry. Broadcast on start-tracking and after
 * any server-side mutation.
 */
public record GlintEntitySyncPacket(int entityId, CompoundTag glintTag) implements CustomPacketPayload {

    public GlintEntitySyncPacket {
        if (glintTag == null) glintTag = new CompoundTag();
    }

    public static final Type<GlintEntitySyncPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(CustomGlintApiMod.MOD_ID, "entity_glint_sync"));

    public static final StreamCodec<FriendlyByteBuf, GlintEntitySyncPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, GlintEntitySyncPacket::entityId,
                    ByteBufCodecs.COMPOUND_TAG, GlintEntitySyncPacket::glintTag,
                    GlintEntitySyncPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GlintEntitySyncPacket pkt, IPayloadContext ctx) {
        // Registered playToClient only, so this runs client-side; Minecraft is referenced solely
        // inside applyClient, which is never reached on a dedicated server.
        ctx.enqueueWork(() -> applyClient(pkt));
    }

    private static void applyClient(GlintEntitySyncPacket pkt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity e = mc.level.getEntity(pkt.entityId());
        if (!(e instanceof LivingEntity le)) return;
        CustomGlint.writeEntityTag(le, pkt.glintTag());
        EntityGlintCache.put(le.getUUID(), pkt.glintTag());
    }
}
