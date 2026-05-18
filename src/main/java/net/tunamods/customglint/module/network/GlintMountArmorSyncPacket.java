package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.tunamods.customglint.module.compat.iceandfire.MountArmorCache;

import java.util.function.Supplier;

/**
 * S→C: pushes an IaF mount's current armor ItemStack (slot 2 of its internal SimpleContainer)
 * to clients so they can read its CustomGlint NBT for rendering. IaF doesn't sync the stack
 * itself — only the armor-tier int — so we sync it here. Broadcast on inventory change
 * (refreshInventory mixins) and on player-start-tracking (IceAndFireCompat listener).
 */
public class GlintMountArmorSyncPacket {

    private final int entityId;
    private final ItemStack stack;

    public GlintMountArmorSyncPacket(int entityId, ItemStack stack) {
        this.entityId = entityId;
        this.stack = stack;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeItem(stack);
    }

    public static GlintMountArmorSyncPacket decode(FriendlyByteBuf buf) {
        return new GlintMountArmorSyncPacket(buf.readVarInt(), buf.readItem());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> MountArmorCache.put(entityId, stack));
        ctx.get().setPacketHandled(true);
    }
}
