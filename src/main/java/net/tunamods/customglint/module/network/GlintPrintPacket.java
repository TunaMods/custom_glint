package net.tunamods.customglint.module.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.menu.GlintTableMenu;

/**
 * C→S: the Glint Table's "Print" button. Carries the build parameters that aren't in slots (design,
 * speed, scale, opacity, glow/name flags + name text). The server reads the dye/material slots, validates
 * the cost, consumes everything, and outputs the finished trim.
 */
public record GlintPrintPacket(String design, float speed, float scale, int opacity,
                               boolean glow, boolean glowAuto, boolean named, String name,
                               boolean simultaneous, int scrollDir, float scrollOffset, boolean interpolate,
                               int glowHex, int nameHex, int[][] shardDyes, int[] donorColors,
                               CustomGlint.Layer[] belowLayers, CustomGlint.Layer[] aboveLayers, boolean sourceSimultaneous,
                               boolean glowBase, int[][] glowShardDyes)
        implements CustomPacketPayload {

    /** Cap on each of the below/above extra-layer arrays (mirrors GlintTableMenu's own decode cap). */
    private static final int MAX_EXTRA_LAYERS = 16;

    /** Shards kept, and colours kept per shard. print() caps the finished trim's colour layers at 8. */
    private static final int MAX_SHARD_COLORS = 8;

    public static final Type<GlintPrintPacket> TYPE = new Type<>(CustomGlint.res("glint_print"));

    public static final StreamCodec<FriendlyByteBuf, GlintPrintPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
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
                NetworkCodecs.writeLayers(buf, pkt.belowLayers);
                NetworkCodecs.writeLayers(buf, pkt.aboveLayers);
                buf.writeBoolean(pkt.sourceSimultaneous);
                buf.writeBoolean(pkt.glowBase);
                buf.writeVarInt(pkt.glowShardDyes.length);
                for (int[] shard : pkt.glowShardDyes) buf.writeVarIntArray(shard);
            },
            buf -> new GlintPrintPacket(buf.readUtf(), buf.readFloat(), buf.readFloat(), buf.readVarInt(),
                    buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readUtf(32767), buf.readBoolean(),
                    buf.readVarInt(), buf.readFloat(), buf.readBoolean(),
                    buf.readInt(), buf.readInt(),
                    readShardDyes(buf), NetworkCodecs.readCappedVarIntArray(buf, MAX_SHARD_COLORS),
                    NetworkCodecs.readLayers(buf, MAX_EXTRA_LAYERS),
                    NetworkCodecs.readLayers(buf, MAX_EXTRA_LAYERS), buf.readBoolean(),
                    buf.readBoolean(), readShardDyes(buf))
    );

    private static int[][] readShardDyes(FriendlyByteBuf buf) {
        // Consume EVERY shard the sender wrote (the encoder writes shardDyes.length) so the buffer stays
        // aligned with the fields after it.
        int sent = buf.readVarInt();
        if (sent < 0 || sent > NetworkCodecs.MAX_WIRE_COUNT) throw new DecoderException("Bad shard count: " + sent);
        int keep = Math.max(0, Math.min(sent, MAX_SHARD_COLORS));
        int[][] shards = new int[keep][];
        for (int i = 0; i < sent; i++) {
            int[] shard = NetworkCodecs.readCappedVarIntArray(buf, MAX_SHARD_COLORS);
            if (i < keep) shards[i] = shard;
        }
        return shards;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GlintPrintPacket pkt, IPayloadContext ctx) {
        GlintTableMenu.withOpenMenu(ctx, (sp, m) -> {
            if (pkt.glowBase) {
                m.printGlow(pkt.shardDyes, pkt.speed, pkt.interpolate, pkt.named, pkt.name, pkt.nameHex);
            } else {
                m.print(pkt.design, pkt.speed, pkt.scale, pkt.opacity, pkt.glow, pkt.glowAuto, pkt.named, pkt.name, pkt.simultaneous, pkt.scrollDir, pkt.scrollOffset, pkt.interpolate, pkt.glowHex, pkt.nameHex, pkt.shardDyes, pkt.donorColors, pkt.belowLayers, pkt.aboveLayers, pkt.sourceSimultaneous, pkt.glowShardDyes);
            }
        });
    }
}
