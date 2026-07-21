package net.tunamods.customglint.module.network;

import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;

public record GlintDesignSyncPacket(List<String> designs) implements CustomPacketPayload {

    public static final Type<GlintDesignSyncPacket> TYPE =
            new Type<>(CustomGlint.res("glint_design_sync"));

    public static final StreamCodec<FriendlyByteBuf, GlintDesignSyncPacket> STREAM_CODEC =
            NetworkCodecs.stringList(1024).map(GlintDesignSyncPacket::new, GlintDesignSyncPacket::designs);

    private static final List<String> clientSyncedDesigns = new ArrayList<>();

    /** Rolls the server-synced data-pack designs back out of {@link GlintTrimItem#PATTERNS} on disconnect,
     *  so they don't linger after leaving the server that sent them (the next server re-syncs its own). */
    public static void clearClient() {
        GlintTrimItem.PATTERNS.removeAll(clientSyncedDesigns);
        clientSyncedDesigns.clear();
        // The GUI glint atlas is stitched from the (now-shrunk) design list, so force a re-stitch. This class
        // is server-reachable (the payload is registered on both sides), so the renderer call sits behind the
        // dist guard and a dedicated server never resolves the client class.
        if (FMLEnvironment.getDist() == Dist.CLIENT) CustomGlintRenderer.invalidateGuiDesignAtlas();
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
            // Data-pack designs just changed on the client, so re-stitch the shared GUI glint atlas so they
            // batch too. enqueueWork runs on the client render thread, so releasing the texture here is safe.
            // Dist-guarded for the same reason as clearClient above.
            if (FMLEnvironment.getDist() == Dist.CLIENT) CustomGlintRenderer.invalidateGuiDesignAtlas();
        });
    }
}
