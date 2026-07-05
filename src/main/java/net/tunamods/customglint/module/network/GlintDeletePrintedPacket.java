package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.tunamods.customglint.module.menu.GlintTableMenu;

import java.util.function.Supplier;

/**
 * C→S: shift-left-click on a still-locked imported trim in the Glint Table's printed library. The server
 * removes that entry from the library outright (only import-locked, un-crafted entries can be deleted this
 * way; a real printed trim is withdrawn instead). Carries the library index of the clicked trim.
 */
public class GlintDeletePrintedPacket {

    public final int index;

    public GlintDeletePrintedPacket(int index) {
        this.index = index;
    }

    public static void encode(GlintDeletePrintedPacket pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.index);
    }

    public static GlintDeletePrintedPacket decode(FriendlyByteBuf buf) {
        return new GlintDeletePrintedPacket(buf.readVarInt());
    }

    public static void handle(GlintDeletePrintedPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp != null && sp.containerMenu instanceof GlintTableMenu m) m.deletePrinted(pkt.index);
        });
        ctx.get().setPacketHandled(true);
    }
}
