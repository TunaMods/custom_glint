package net.tunamods.customglint.module.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;

/**
 * S→C: the design names contributed by data packs, merged into {@link GlintTrimItem#PATTERNS} on the client.
 * Sent on join and after every reload. The names added by the previous sync are dropped first, so a reload
 * that removes a data-pack design removes it from the client's palette too.
 */
public record GlintDesignSyncPacket(List<String> designs) implements CustomPacketPayload {

    public static final Type<GlintDesignSyncPacket> TYPE =
            new Type<>(CustomGlint.res("glint_design_sync"));

    public static final StreamCodec<FriendlyByteBuf, GlintDesignSyncPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeVarInt(pkt.designs.size());
                        for (String design : pkt.designs) buf.writeUtf(design);
                    },
                    buf -> {
                        int count = buf.readVarInt();
                        List<String> designs = new ArrayList<>(ModNetworking.syncListCapacity(count));
                        for (int i = 0; i < count; i++) designs.add(buf.readUtf());
                        return new GlintDesignSyncPacket(designs);
                    }
            );

    /** The names this client added to {@link GlintTrimItem#PATTERNS} from the last sync, so the next one can
     *  take them back out again. */
    private static final List<String> CLIENT_SYNCED_DESIGNS = new ArrayList<>();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GlintDesignSyncPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            GlintTrimItem.PATTERNS.removeAll(CLIENT_SYNCED_DESIGNS);
            CLIENT_SYNCED_DESIGNS.clear();
            for (String design : pkt.designs) {
                if (!GlintTrimItem.PATTERNS.contains(design)) {
                    GlintTrimItem.PATTERNS.add(design);
                    CLIENT_SYNCED_DESIGNS.add(design);
                }
            }
        });
    }
}
