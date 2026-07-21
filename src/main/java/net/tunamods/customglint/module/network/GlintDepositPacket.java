package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.menu.GlintTableMenu;

/**
 * C→S: the player dropped a trim held on the cursor onto one of the Glint Table's scrollable grids. The
 * server deposits one into the player's design / printed-trim library (the click target isn't a real slot,
 * so this is the drag-in equivalent of shift-clicking a trim into the table). No payload, the carried item
 * is read from the open menu.
 */
public record GlintDepositPacket() implements CustomPacketPayload {

    public static final GlintDepositPacket INSTANCE = new GlintDepositPacket();
    public static final Type<GlintDepositPacket> TYPE = new Type<>(CustomGlint.res("glint_deposit"));
    public static final StreamCodec<FriendlyByteBuf, GlintDepositPacket> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GlintDepositPacket pkt, IPayloadContext ctx) {
        ModNetworking.withGlintTable(ctx, (sp, menu) -> menu.depositCarried());
    }
}
