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
        ServerBlueprints.syncToOffThread(sp);
    }
}
