package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.tunamods.customglint.module.menu.GlintTableMenu;

import java.util.function.Supplier;

/**
 * C→S: an operator asked to delete one of the server's shared blueprint trims from the Glint Table's Import
 * list. The server verifies op permission, removes the matching {@code config/glint-and-glamour/trims/<name>.json},
 * and re-syncs the shared list. Personal client blueprints never use this path: the client deletes those
 * from its own config dir directly.
 */
public class GlintDeleteServerBlueprintPacket {

    public final String name;

    public GlintDeleteServerBlueprintPacket(String name) {
        this.name = name;
    }

    public static void encode(GlintDeleteServerBlueprintPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.name);
    }

    public static GlintDeleteServerBlueprintPacket decode(FriendlyByteBuf buf) {
        return new GlintDeleteServerBlueprintPacket(buf.readUtf());
    }

    public static void handle(GlintDeleteServerBlueprintPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetHandlers.withTableMenu(ctx, (sp, m) -> m.deleteServerBlueprint(sp, pkt.name));
    }
}
