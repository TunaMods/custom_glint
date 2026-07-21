package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.tunamods.customglint.module.compat.iceandfire.MountArmorCache;

import java.util.function.Supplier;

/**
 * S→C: pushes an IaF mount's current armor ItemStack (slot 2 of its internal SimpleContainer)
 * to clients so they can read its CustomGlint NBT for rendering. IaF doesn't sync the stack
 * itself, only the armor-tier int, so we sync it here. Broadcast on inventory change
 * (refreshInventory mixins) and on player-start-tracking (IceAndFireCompat listener).
 */
public class GlintMountArmorSyncPacket {

    private final int entityId;
    private final ItemStack stack;

    public GlintMountArmorSyncPacket(int entityId, ItemStack stack) {
        this.entityId = entityId;
        this.stack = stack;
    }

    public static void encode(GlintMountArmorSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.entityId);
        buf.writeItem(pkt.stack);
    }

    public static GlintMountArmorSyncPacket decode(FriendlyByteBuf buf) {
        return new GlintMountArmorSyncPacket(buf.readVarInt(), buf.readItem());
    }

    public static void handle(GlintMountArmorSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetHandlers.work(ctx, () -> MountArmorCache.put(pkt.entityId, pkt.stack));
    }
}
