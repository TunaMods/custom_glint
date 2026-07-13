package net.tunamods.customglint.module.network;

import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;

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
                        // Cap the pre-sized capacity so a bogus count can't pre-allocate a huge array; the
                        // loop still drains the real count.
                        List<String> designs = new ArrayList<>(Math.max(0, Math.min(count, 1024)));
                        for (int i = 0; i < count; i++) designs.add(buf.readUtf());
                        return new GlintDesignSyncPacket(designs);
                    }
            );

    private static final List<String> clientSyncedDesigns = new ArrayList<>();

    /** Rolls the server-synced data-pack designs back out of {@link GlintTrimItem#PATTERNS} on disconnect,
     *  so they don't linger after leaving the server that sent them (the next server re-syncs its own). */
    public static void clearClient() {
        GlintTrimItem.PATTERNS.removeAll(clientSyncedDesigns);
        clientSyncedDesigns.clear();
        // The GUI glint atlas is stitched from the (now-shrunk) design list — force a re-stitch. Client-only
        // path (logout), so touching the renderer is safe.
        CustomGlintRenderer.invalidateGuiDesignAtlas();
    }

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
            // Data-pack designs just changed on the client — re-stitch the shared GUI glint atlas so they
            // batch too. enqueueWork runs on the client render thread, so releasing the texture here is safe.
            CustomGlintRenderer.invalidateGuiDesignAtlas();
        });
    }
}
