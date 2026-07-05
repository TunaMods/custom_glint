package net.tunamods.customglint.module.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.menu.GlintTableMenu;

import java.util.function.Supplier;

/**
 * C→S: the Glint Table's "Print" button. Carries the build parameters that aren't in slots (design, speed,
 * scale, opacity, glow/name flags + name text, scroll). The server reads the dye/material slots, validates
 * the cost, consumes everything, and outputs the finished trim.
 */
public class GlintPrintPacket {

    /** Anti-DoS cap on each of the below/above extra-layer arrays. */
    private static final int MAX_EXTRA_LAYERS = 16;

    public final String design;
    public final float speed, scale, scrollOffset;
    public final int opacity, scrollDir, glowHex, nameHex;
    public final boolean glow, glowAuto, named, simultaneous, interpolate, sourceSimultaneous;
    public final String name;
    public final int[][] shardDyes;
    public final int[] donorColors;
    public final CustomGlint.Layer[] belowLayers, aboveLayers;

    public GlintPrintPacket(String design, float speed, float scale, int opacity,
                            boolean glow, boolean glowAuto, boolean named, String name,
                            boolean simultaneous, int scrollDir, float scrollOffset, boolean interpolate,
                            int glowHex, int nameHex, int[][] shardDyes, int[] donorColors,
                            CustomGlint.Layer[] belowLayers, CustomGlint.Layer[] aboveLayers, boolean sourceSimultaneous) {
        this.design = design; this.speed = speed; this.scale = scale; this.opacity = opacity;
        this.glow = glow; this.glowAuto = glowAuto; this.named = named; this.name = name;
        this.simultaneous = simultaneous; this.scrollDir = scrollDir; this.scrollOffset = scrollOffset;
        this.interpolate = interpolate; this.glowHex = glowHex; this.nameHex = nameHex; this.shardDyes = shardDyes;
        this.donorColors = donorColors;
        this.belowLayers = belowLayers; this.aboveLayers = aboveLayers; this.sourceSimultaneous = sourceSimultaneous;
    }

    public static void encode(GlintPrintPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.design);
        buf.writeFloat(pkt.speed);
        buf.writeFloat(pkt.scale);
        buf.writeVarInt(pkt.opacity);
        buf.writeBoolean(pkt.glow);
        buf.writeBoolean(pkt.glowAuto);
        buf.writeBoolean(pkt.named);
        buf.writeUtf(pkt.name);
        buf.writeBoolean(pkt.simultaneous);
        buf.writeVarInt(pkt.scrollDir);
        buf.writeFloat(pkt.scrollOffset);
        buf.writeBoolean(pkt.interpolate);
        buf.writeInt(pkt.glowHex);
        buf.writeInt(pkt.nameHex);
        buf.writeVarInt(pkt.shardDyes.length);
        for (int[] shard : pkt.shardDyes) buf.writeVarIntArray(shard);
        buf.writeVarIntArray(pkt.donorColors);
        GlintApplyPacket.writeLayers(buf, pkt.belowLayers);
        GlintApplyPacket.writeLayers(buf, pkt.aboveLayers);
        buf.writeBoolean(pkt.sourceSimultaneous);
    }

    public static GlintPrintPacket decode(FriendlyByteBuf buf) {
        return new GlintPrintPacket(buf.readUtf(), buf.readFloat(), buf.readFloat(), buf.readVarInt(),
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readUtf(32767), buf.readBoolean(),
                buf.readVarInt(), buf.readFloat(), buf.readBoolean(),
                buf.readInt(), buf.readInt(),
                readShardDyes(buf), readCappedVarIntArray(buf, 8),
                GlintApplyPacket.readLayers(buf, MAX_EXTRA_LAYERS),
                GlintApplyPacket.readLayers(buf, MAX_EXTRA_LAYERS), buf.readBoolean());
    }

    private static int[][] readShardDyes(FriendlyByteBuf buf) {
        int sent = buf.readVarInt();
        if (sent < 0 || sent > GlintApplyPacket.MAX_WIRE_COUNT) throw new DecoderException("Bad shard count: " + sent);
        int keep = Math.max(0, Math.min(sent, 8));
        int[][] shards = new int[keep][];
        for (int i = 0; i < sent; i++) {
            int[] shard = readCappedVarIntArray(buf, 8);
            if (i < keep) shards[i] = shard;
        }
        return shards;
    }

    private static int[] readCappedVarIntArray(FriendlyByteBuf buf, int cap) {
        int size = buf.readVarInt();
        if (size < 0 || size > cap) throw new DecoderException("Bad array size: " + size);
        int[] out = new int[size];
        for (int i = 0; i < size; i++) out[i] = buf.readVarInt();
        return out;
    }

    public static void handle(GlintPrintPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp != null && sp.containerMenu instanceof GlintTableMenu m) {
                m.print(pkt.design, pkt.speed, pkt.scale, pkt.opacity, pkt.glow, pkt.glowAuto, pkt.named, pkt.name,
                        pkt.simultaneous, pkt.scrollDir, pkt.scrollOffset, pkt.interpolate, pkt.glowHex, pkt.nameHex,
                        pkt.shardDyes, pkt.donorColors, pkt.belowLayers, pkt.aboveLayers, pkt.sourceSimultaneous);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
