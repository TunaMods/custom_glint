package net.tunamods.customglint.module.network;

import net.tunamods.customglint.common.CustomGlint;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tunamods.customglint.module.compat.iceandfire.MountArmorCache;

/**
 * S→C: pushes an IaF mount's current armor ItemStack (slot 2 of its internal SimpleContainer)
 * to clients so they can read its CustomGlint NBT for rendering. IaF doesn't sync the stack
 * itself — only the armor-tier int — so we sync it here. Broadcast on inventory change
 * (refreshInventory mixins) and on player-start-tracking (IceAndFireCompat listener).
 */
public record GlintMountArmorSyncPacket(int entityId, ItemStack stack) implements CustomPacketPayload {

    public static final Type<GlintMountArmorSyncPacket> TYPE =
            new Type<>(CustomGlint.res("mount_armor_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GlintMountArmorSyncPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, GlintMountArmorSyncPacket::entityId,
                    ItemStack.OPTIONAL_STREAM_CODEC, GlintMountArmorSyncPacket::stack,
                    GlintMountArmorSyncPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GlintMountArmorSyncPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> MountArmorCache.put(pkt.entityId(), pkt.stack()));
    }
}
