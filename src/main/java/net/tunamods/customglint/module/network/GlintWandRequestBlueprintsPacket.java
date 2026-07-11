package net.tunamods.customglint.module.network;

import net.minecraft.Util;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.blueprint.ServerBlueprints;

import java.util.Map;

/**
 * C→S: the wand editor asks the server for the current shared blueprint pool when its Import list opens.
 * Unlike the Glint Table, the wand has no server-side menu to hang an on-open sync from, so it requests one
 * explicitly. The server answers with {@link GlintServerBlueprintsSyncPacket}.
 */
public record GlintWandRequestBlueprintsPacket() implements CustomPacketPayload {

    public static final Type<GlintWandRequestBlueprintsPacket> TYPE =
            new Type<>(CustomGlint.res("glint_wand_request_blueprints"));

    public static final StreamCodec<FriendlyByteBuf, GlintWandRequestBlueprintsPacket> STREAM_CODEC =
            StreamCodec.unit(new GlintWandRequestBlueprintsPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GlintWandRequestBlueprintsPacket pkt, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        // Read the shared blueprint pool off the server thread: readAll() opens and reads the full contents
        // of every trim file, and the first read in a world hits a cold file cache — a server-thread stall
        // felt on the client as a lag spike (gone on later opens once the cache is warm). Send the reply back
        // on the server thread once the read finishes.
        Util.ioPool().execute(() -> {
            Map<String, String> all = ServerBlueprints.readAll();
            ctx.enqueueWork(() -> PacketDistributor.sendToPlayer(sp, new GlintServerBlueprintsSyncPacket(all)));
        });
    }
}
