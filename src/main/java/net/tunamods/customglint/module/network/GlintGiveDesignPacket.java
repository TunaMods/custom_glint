package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.menu.GlintTableMenu;

/**
 * C→S: shift-left-click an empty design in the Glint Table's left palette. The server hands the player a
 * free blank trim of that design (the design carries no colors, so it's just a template). If the inventory
 * is full it's a no-op. Carries the grid design name.
 */
public record GlintGiveDesignPacket(String design) implements CustomPacketPayload {

    public static final Type<GlintGiveDesignPacket> TYPE = new Type<>(CustomGlint.res("glint_give_design"));
    public static final StreamCodec<FriendlyByteBuf, GlintGiveDesignPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> buf.writeUtf(pkt.design),
            buf -> new GlintGiveDesignPacket(buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GlintGiveDesignPacket pkt, IPayloadContext ctx) {
        ModNetworking.withGlintTable(ctx, (sp, menu) -> menu.giveDesignCopy(pkt.design()));
    }
}
