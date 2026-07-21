package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.tunamods.customglint.module.menu.GlintTableMenu;

import java.util.function.Supplier;

/**
 * C→S: shift-left-click on a trim in the Glint Table's printed (painted) library. The server pulls that trim
 * out of the library and into the player's inventory. Carries the library index of the clicked trim.
 */
public class GlintWithdrawPacket {

    public final int index;

    public GlintWithdrawPacket(int index) {
        this.index = index;
    }

    public static void encode(GlintWithdrawPacket pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.index);
    }

    public static GlintWithdrawPacket decode(FriendlyByteBuf buf) {
        return new GlintWithdrawPacket(buf.readVarInt());
    }

    public static void handle(GlintWithdrawPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetHandlers.withTableMenu(ctx, (sp, m) -> m.withdrawPrinted(pkt.index));
    }
}
