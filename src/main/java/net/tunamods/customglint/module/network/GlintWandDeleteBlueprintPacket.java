package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.blueprint.ServerBlueprints;
import net.tunamods.customglint.module.item.GlintWandItem;

/**
 * C→S: the wand editor's Import trash icon deletes a shared blueprint from the server's pool
 * ({@link ServerBlueprints}). No op check: the wand is the gate (matching the save path). The server
 * removes the file and re-syncs so the sender's Import list updates.
 */
public record GlintWandDeleteBlueprintPacket(String name) implements CustomPacketPayload {

    public static final Type<GlintWandDeleteBlueprintPacket> TYPE =
            new Type<>(CustomGlint.res("glint_wand_delete_blueprint"));

    public static final StreamCodec<FriendlyByteBuf, GlintWandDeleteBlueprintPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> buf.writeUtf(pkt.name),
            buf -> new GlintWandDeleteBlueprintPacket(buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GlintWandDeleteBlueprintPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            // Client-sent: re-verify the sender holds the wand (the stated gate) before removing a shared
            // file, else a scripted client could wipe every server blueprint. Mirrors the save path.
            if (!(sp.getMainHandItem().getItem() instanceof GlintWandItem
                    || sp.getOffhandItem().getItem() instanceof GlintWandItem)) return;
            ServerBlueprints.delete(pkt.name());
            ServerBlueprints.syncTo(sp);
        });
    }
}
