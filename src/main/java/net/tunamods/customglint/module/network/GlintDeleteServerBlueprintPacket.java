package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.menu.GlintTableMenu;

/**
 * C→S: an operator asked to delete one of the server's shared blueprint trims from the Glint Table's Import
 * list. The server verifies op permission, removes the matching {@code config/glint-and-glamour/trims/<name>.json},
 * and re-syncs the shared list. Personal client blueprints never use this path; the client deletes those
 * from its own config dir directly.
 */
public record GlintDeleteServerBlueprintPacket(String name) implements CustomPacketPayload {

    public static final Type<GlintDeleteServerBlueprintPacket> TYPE =
            new Type<>(CustomGlint.res("glint_delete_server_blueprint"));

    public static final StreamCodec<FriendlyByteBuf, GlintDeleteServerBlueprintPacket> STREAM_CODEC =
            NetworkCodecs.string(GlintDeleteServerBlueprintPacket::name, GlintDeleteServerBlueprintPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GlintDeleteServerBlueprintPacket pkt, IPayloadContext ctx) {
        GlintTableMenu.withOpenMenu(ctx, (sp, m) -> m.deleteServerBlueprint(sp, pkt.name()));
    }
}
