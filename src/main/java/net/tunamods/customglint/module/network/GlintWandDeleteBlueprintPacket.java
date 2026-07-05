package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.tunamods.customglint.module.blueprint.ServerBlueprints;

import java.util.function.Supplier;

/**
 * C→S: the wand editor's Import trash icon deletes a shared blueprint from the server's pool
 * ({@link ServerBlueprints}). No op check — the wand is the gate (matching the save path). The server
 * removes the file and re-syncs so the sender's Import list updates.
 */
public class GlintWandDeleteBlueprintPacket {

    public final String name;

    public GlintWandDeleteBlueprintPacket(String name) {
        this.name = name;
    }

    public static void encode(GlintWandDeleteBlueprintPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.name);
    }

    public static GlintWandDeleteBlueprintPacket decode(FriendlyByteBuf buf) {
        return new GlintWandDeleteBlueprintPacket(buf.readUtf());
    }

    public static void handle(GlintWandDeleteBlueprintPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp == null) return;
            ServerBlueprints.delete(pkt.name);
            ServerBlueprints.syncTo(sp);
        });
        ctx.get().setPacketHandled(true);
    }
}
