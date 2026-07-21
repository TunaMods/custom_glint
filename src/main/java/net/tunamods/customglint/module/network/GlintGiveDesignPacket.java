package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.tunamods.customglint.module.menu.GlintTableMenu;

import java.util.function.Supplier;

/**
 * C→S: shift-left-click an empty design in the Glint Table's left palette. The server hands the player a free
 * blank trim of that design (it carries no colors, so it's just a template). The server only hands out a
 * design the player has already stored, and drops the trim at their feet if their inventory is full.
 */
public class GlintGiveDesignPacket {

    public final String design;

    public GlintGiveDesignPacket(String design) {
        this.design = design;
    }

    public static void encode(GlintGiveDesignPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.design);
    }

    public static GlintGiveDesignPacket decode(FriendlyByteBuf buf) {
        return new GlintGiveDesignPacket(buf.readUtf());
    }

    public static void handle(GlintGiveDesignPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetHandlers.withTableMenu(ctx, (sp, m) -> m.giveDesignCopy(pkt.design));
    }
}
