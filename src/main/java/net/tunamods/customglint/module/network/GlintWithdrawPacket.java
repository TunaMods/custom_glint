package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.menu.GlintTableMenu;

/**
 * C→S: shift-left-click on a trim in the Glint Table's printed (painted) library. The server pulls that
 * trim out of the library and into the player's inventory; if the inventory is full it stays in the library
 * (the click is a no-op). Carries the library index of the clicked trim.
 */
public record GlintWithdrawPacket(int index) implements CustomPacketPayload {

    public static final Type<GlintWithdrawPacket> TYPE = new Type<>(CustomGlint.res("glint_withdraw"));
    public static final StreamCodec<FriendlyByteBuf, GlintWithdrawPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> buf.writeVarInt(pkt.index),
            buf -> new GlintWithdrawPacket(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GlintWithdrawPacket pkt, IPayloadContext ctx) {
        GlintTableMenu.withOpenMenu(ctx, (sp, m) -> m.withdrawPrinted(pkt.index()));
    }
}
