package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.menu.GlintTableMenu;

/**
 * C→S: shift-left-click on a still-locked imported trim in the Glint Table's printed library. The server
 * removes that entry from the library outright (only import-locked, un-crafted entries can be deleted this
 * way; a real printed trim is withdrawn instead). Carries the library index of the clicked trim.
 */
public record GlintDeletePrintedPacket(int index) implements CustomPacketPayload {

    public static final Type<GlintDeletePrintedPacket> TYPE = new Type<>(CustomGlint.res("glint_delete_printed"));
    public static final StreamCodec<FriendlyByteBuf, GlintDeletePrintedPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> buf.writeVarInt(pkt.index),
            buf -> new GlintDeletePrintedPacket(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GlintDeletePrintedPacket pkt, IPayloadContext ctx) {
        ModNetworking.withGlintTable(ctx, (sp, menu) -> menu.deletePrinted(pkt.index()));
    }
}
