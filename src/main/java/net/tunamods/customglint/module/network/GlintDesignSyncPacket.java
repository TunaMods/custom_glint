package net.tunamods.customglint.module.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;

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
                        // Clamp the pre-sized capacity: a crafted server sending count≈MAX_VALUE would
                        // otherwise allocate a multi-GB backing array before any string is read. The loop
                        // still drains the real count and throws cleanly on buffer underflow if it's fake.
                        List<String> designs = new ArrayList<>(Math.max(0, Math.min(count, 1024)));
                        for (int i = 0; i < count; i++) designs.add(buf.readUtf());
                        return new GlintDesignSyncPacket(designs);
                    }
            );

    private static final List<String> clientSyncedDesigns = new ArrayList<>();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GlintDesignSyncPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            GlintTrimItem.PATTERNS.removeAll(clientSyncedDesigns);
            clientSyncedDesigns.clear();
            for (String design : pkt.designs) {
                if (!GlintTrimItem.PATTERNS.contains(design)) {
                    GlintTrimItem.PATTERNS.add(design);
                    clientSyncedDesigns.add(design);
                }
            }
        });
    }
}
