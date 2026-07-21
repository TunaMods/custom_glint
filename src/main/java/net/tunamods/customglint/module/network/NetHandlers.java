package net.tunamods.customglint.module.network;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.tunamods.customglint.module.menu.GlintTableMenu;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Handler boilerplate shared by the packets in this package: hop off the network thread, resolve the sender, mark handled. */
final class NetHandlers {
    private NetHandlers() {}

    /** Run {@code action} on the receiving side's main thread and mark the packet handled. */
    static void work(Supplier<NetworkEvent.Context> ctx, Runnable action) {
        ctx.get().enqueueWork(action);
        ctx.get().setPacketHandled(true);
    }

    /** Run {@code action} on the server thread with the sender (skipped if there is none). */
    static void withSender(Supplier<NetworkEvent.Context> ctx, Consumer<ServerPlayer> action) {
        work(ctx, () -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp != null) action.accept(sp);
        });
    }

    /** Run {@code action} on the server thread with the sender's open Glint Table menu (skipped if neither is present). */
    static void withTableMenu(Supplier<NetworkEvent.Context> ctx, BiConsumer<ServerPlayer, GlintTableMenu> action) {
        work(ctx, () -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp != null && sp.containerMenu instanceof GlintTableMenu m) action.accept(sp, m);
        });
    }
}
