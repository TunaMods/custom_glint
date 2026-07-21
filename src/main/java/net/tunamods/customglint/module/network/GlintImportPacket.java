package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.menu.GlintTableMenu;

import java.util.function.Supplier;

/**
 * C→S: the player picked a premade trim from the Glint Table's Import list (read from
 * {@code config/glint-and-glamour/trims/*.json} on the client, same source as the wand editor's import). The
 * server rebuilds the trim, stores its designs as owned, and drops it into the printed library as a LOCKED
 * (dimmed, non-withdrawable) entry. The lock clears only when the player prints a matching trim, so importing
 * hands out a build target, not a free finished trim.
 */
public class GlintImportPacket {

    public final CustomGlint.Layer[] layers;
    public final boolean glowing;
    public final int[] glowColors;
    public final String name;
    public final int nameColor;

    public GlintImportPacket(CustomGlint.Layer[] layers, boolean glowing, int[] glowColors, String name, int nameColor) {
        this.layers = layers;
        this.glowing = glowing;
        this.glowColors = glowColors;
        this.name = name;
        this.nameColor = nameColor;
    }

    public static void encode(GlintImportPacket pkt, FriendlyByteBuf buf) {
        GlintApplyPacket.writeLayers(buf, pkt.layers);
        buf.writeBoolean(pkt.glowing);
        GlintApplyPacket.writeColors(buf, pkt.glowColors);
        buf.writeUtf(pkt.name);
        buf.writeInt(pkt.nameColor);
    }

    public static GlintImportPacket decode(FriendlyByteBuf buf) {
        CustomGlint.Layer[] layers = GlintApplyPacket.readLayers(buf, CustomGlint.MAX_LAYERS);
        boolean glowing = buf.readBoolean();
        int[] glowColors = GlintApplyPacket.readCappedColors(buf, CustomGlint.MAX_COLORS_PER_LAYER);
        String name = buf.readUtf();
        int nameColor = buf.readInt();
        return new GlintImportPacket(layers, glowing, glowColors, name, nameColor);
    }

    public static void handle(GlintImportPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetHandlers.withTableMenu(ctx, (sp, m) ->
                m.importTrim(pkt.layers, pkt.glowing, pkt.glowColors, pkt.name, pkt.nameColor));
    }
}
