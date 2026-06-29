package net.tunamods.customglint.module.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

/**
 * S→C: the player's "printed" trim library — the finished, painted (colored / glow) trims shown in the Glint
 * Table's right panel. Sent when the table opens and whenever a trim is deposited. The client mirror
 * {@link #CLIENT_PRINTED} drives the right grid.
 */
public class GlintPrintedSyncPacket {

    public final List<ItemStack> trims;

    public GlintPrintedSyncPacket(List<ItemStack> trims) {
        this.trims = trims;
    }

    public static void encode(GlintPrintedSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.trims.size());
        for (ItemStack s : pkt.trims) buf.writeItem(s);
    }

    public static GlintPrintedSyncPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        // Clamp the pre-sized capacity so a crafted server can't trigger a huge eager allocation.
        List<ItemStack> trims = new ArrayList<>(Math.max(0, Math.min(count, 1024)));
        for (int i = 0; i < count; i++) {
            ItemStack s = buf.readItem();
            if (!s.isEmpty()) trims.add(s);
        }
        return new GlintPrintedSyncPacket(trims);
    }

    /** Client-side mirror of the local player's printed-trim library. Read by the Glint Table screen. */
    public static final List<ItemStack> CLIENT_PRINTED = new ArrayList<>();

    public static void clearClient() { CLIENT_PRINTED.clear(); }

    public static void handle(GlintPrintedSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            CLIENT_PRINTED.clear();
            CLIENT_PRINTED.addAll(pkt.trims);
        });
        ctx.get().setPacketHandled(true);
    }
}
