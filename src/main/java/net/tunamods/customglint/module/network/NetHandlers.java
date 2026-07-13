package net.tunamods.customglint.module.network;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.tunamods.customglint.module.menu.GlintTableMenu;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Boilerplate shared by the C→S packet handlers: hop to the server thread, resolve the sender, mark handled. */
final class NetHandlers {
    private NetHandlers() {}

    /** Run {@code action} on the server thread with the sender's open Glint Table menu (skipped if neither is present). */
    static void withTableMenu(Supplier<NetworkEvent.Context> ctx, BiConsumer<ServerPlayer, GlintTableMenu> action) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp != null && sp.containerMenu instanceof GlintTableMenu m) action.accept(sp, m);
        });
        ctx.get().setPacketHandled(true);
    }

    /** Run {@code action} on the server thread with the sender (skipped if there is none). */
    static void withSender(Supplier<NetworkEvent.Context> ctx, Consumer<ServerPlayer> action) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp != null) action.accept(sp);
        });
        ctx.get().setPacketHandled(true);
    }
}
