package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tunamods.customglint.common.CustomGlint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * S→C: the design names this player has "stored" in a Glint Table (the per-player set persisted via
 * the {@code stored_designs} attachment). Sent when a table menu opens and whenever a new design is
 * stored. The client mirror {@link #CLIENT_STORED} drives which trims show un-ghosted in the grid.
 */
public record GlintStoredSyncPacket(List<String> designs) implements CustomPacketPayload {

    public static final Type<GlintStoredSyncPacket> TYPE =
            new Type<>(CustomGlint.res("glint_stored_sync"));

    public static final StreamCodec<FriendlyByteBuf, GlintStoredSyncPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeVarInt(pkt.designs.size());
                        for (String design : pkt.designs) buf.writeUtf(design);
                    },
                    buf -> {
                        int count = buf.readVarInt();
                        // Clamp the pre-sized capacity so a crafted server can't trigger a huge eager
                        // allocation; the loop drains the real count and underflows cleanly if it's fake.
                        List<String> designs = new ArrayList<>(Math.max(0, Math.min(count, 1024)));
                        for (int i = 0; i < count; i++) designs.add(buf.readUtf());
                        return new GlintStoredSyncPacket(designs);
                    }
            );

    /** Client-side mirror of the local player's stored design set. Read by the Glint Table screen. */
    public static final Set<String> CLIENT_STORED = new HashSet<>();

    /** Drops the mirror on disconnect so a later session can't briefly read the previous server's set. */
    public static void clearClient() { CLIENT_STORED.clear(); }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GlintStoredSyncPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            CLIENT_STORED.clear();
            CLIENT_STORED.addAll(pkt.designs);
        });
    }
}
