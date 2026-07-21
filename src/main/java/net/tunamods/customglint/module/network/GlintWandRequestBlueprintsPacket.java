package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.tunamods.customglint.module.blueprint.ServerBlueprints;

import java.util.function.Supplier;

/**
 * C→S: the wand editor asks the server for the current shared blueprint pool when its Import list opens.
 * Unlike the Glint Table, the wand has no server-side menu to hang an on-open sync from, so it requests one
 * explicitly. The server answers with {@link GlintServerBlueprintsSyncPacket}.
 */
public class GlintWandRequestBlueprintsPacket {

    public static void encode(GlintWandRequestBlueprintsPacket pkt, FriendlyByteBuf buf) {}

    public static GlintWandRequestBlueprintsPacket decode(FriendlyByteBuf buf) {
        return new GlintWandRequestBlueprintsPacket();
    }

    public static void handle(GlintWandRequestBlueprintsPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetHandlers.withSender(ctx, ServerBlueprints::syncTo);
    }
}
