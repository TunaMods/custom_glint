package net.tunamods.customglint.module.network;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * S→C: the design names this player has "stored" in a Glint Table (the per-player set persisted in
 * persistentData). Sent when a table menu opens and whenever a new design is stored. The client mirror
 * {@link #CLIENT_STORED} drives which trims show un-ghosted in the grid.
 */
public class GlintStoredSyncPacket {

    /** Client-side mirror of the local player's stored design set. Read by the Glint Table screen. */
    public static final Set<String> CLIENT_STORED = new HashSet<>();

    public final List<String> designs;

    public GlintStoredSyncPacket(List<String> designs) {
        this.designs = designs;
    }

    public static void encode(GlintStoredSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.designs.size());
        for (String design : pkt.designs) buf.writeUtf(design);
    }

    public static GlintStoredSyncPacket decode(FriendlyByteBuf buf) {
        // Cap the wire count (legit set is ≤128).
        int count = Math.max(0, Math.min(buf.readVarInt(), 1024));
        List<String> designs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) designs.add(buf.readUtf());
        return new GlintStoredSyncPacket(designs);
    }

    public static void handle(GlintStoredSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetHandlers.work(ctx, () -> {
            CLIENT_STORED.clear();
            CLIENT_STORED.addAll(pkt.designs);
        });
    }

    /** Drops the mirror on disconnect so a later session can't read the previous server's set. */
    public static void clearClient() { CLIENT_STORED.clear(); }
}
