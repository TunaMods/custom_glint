package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.tunamods.customglint.module.menu.GlintTableMenu;

import java.util.function.Supplier;

/**
 * C→S: the player dropped a trim held on the cursor onto one of the Glint Table's scrollable grids. The
 * server deposits one into the player's design / printed-trim library. No payload — the carried item is read
 * from the open menu.
 */
public class GlintDepositPacket {

    public static void encode(GlintDepositPacket pkt, FriendlyByteBuf buf) {}

    public static GlintDepositPacket decode(FriendlyByteBuf buf) {
        return new GlintDepositPacket();
    }

    public static void handle(GlintDepositPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp != null && sp.containerMenu instanceof GlintTableMenu m) m.depositCarried();
        });
        ctx.get().setPacketHandled(true);
    }
}
