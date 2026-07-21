package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.menu.GlintTableMenu;

/**
 * C→S: the player picked a premade trim from the Glint Table's Import list (read from
 * {@code config/customglint/trims/*.json} on the client, same source as the wand editor's import). The
 * server rebuilds the trim, stores its designs as owned, and drops it into the printed library as a LOCKED
 * (dimmed, non-withdrawable) entry. The lock clears only when the player prints a matching trim, so importing
 * hands out a build target, not a free finished trim.
 */
public record GlintImportPacket(CustomGlint.Layer[] layers, boolean glowing, int[] glowColors,
                                String name, int nameColor) implements CustomPacketPayload {

    public static final Type<GlintImportPacket> TYPE = new Type<>(CustomGlint.res("glint_import"));

    public static final StreamCodec<FriendlyByteBuf, GlintImportPacket> STREAM_CODEC =
            StreamCodec.of(GlintImportPacket::encode, GlintImportPacket::decode);

    private static void encode(FriendlyByteBuf buf, GlintImportPacket pkt) {
        GlintApplyPacket.writeLayers(buf, pkt.layers);
        GlintApplyPacket.writeGlowAndName(buf, pkt.glowing, pkt.glowColors, pkt.name, pkt.nameColor);
    }

    private static GlintImportPacket decode(FriendlyByteBuf buf) {
        CustomGlint.Layer[] layers = GlintApplyPacket.readLayers(buf, GlintApplyPacket.MAX_LAYERS);
        GlintApplyPacket.GlowName gn = GlintApplyPacket.readGlowAndName(buf);
        return new GlintImportPacket(layers, gn.glowing(), gn.glowColors(), gn.name(), gn.nameColor());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GlintImportPacket pkt, IPayloadContext ctx) {
        ModNetworking.withGlintTable(ctx, (sp, menu) ->
                menu.importTrim(pkt.layers, pkt.glowing, pkt.glowColors, pkt.name, pkt.nameColor));
    }
}
