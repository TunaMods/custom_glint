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
                               CustomGlint.Layer[] belowLayers, CustomGlint.Layer[] aboveLayers, boolean sourceSimultaneous)
        implements CustomPacketPayload {

    /** Cap on each of the below/above extra-layer arrays (mirrors GlintTableMenu's own decode cap). */
    private static final int MAX_EXTRA_LAYERS = 16;

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
                GlintApplyPacket.writeLayers(buf, pkt.belowLayers);
                GlintApplyPacket.writeLayers(buf, pkt.aboveLayers);
                buf.writeBoolean(pkt.sourceSimultaneous);
            },
            buf -> new GlintPrintPacket(buf.readUtf(), buf.readFloat(), buf.readFloat(), buf.readVarInt(),
                    buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readUtf(32767), buf.readBoolean(),
                    buf.readVarInt(), buf.readFloat(), buf.readBoolean(),
                    buf.readInt(), buf.readInt(),
                    readShardDyes(buf), readCappedVarIntArray(buf, 8),
                    GlintApplyPacket.readLayers(buf, MAX_EXTRA_LAYERS),
                    GlintApplyPacket.readLayers(buf, MAX_EXTRA_LAYERS), buf.readBoolean())
    );

    private static int[][] readShardDyes(FriendlyByteBuf buf) {
        // Consume EVERY shard the sender wrote (the encoder writes shardDyes.length) so the buffer stays
        // aligned with the fields after it, capping the read count at 8 left a crafted packet's extra shard
        // arrays unread, desyncing the rest of the decode. Only the first 8 are kept (print() caps colour
        // layers at 8); per-shard length is capped to block a crafted multi-million-int allocation.
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

    /** VarInt-array read that rejects a negative or oversized count as a {@link DecoderException}. Vanilla
     *  {@link FriendlyByteBuf#readVarIntArray(int)} only checks the UPPER bound, so a crafted negative size
     *  reaches {@code new int[size]} and throws {@code NegativeArraySizeException} on the netty thread instead
     *  of the clean decode error the rest of this packet uses. */
    private static int[] readCappedVarIntArray(FriendlyByteBuf buf, int cap) {
        int size = buf.readVarInt();
        if (size < 0 || size > cap) throw new DecoderException("Bad array size: " + size);
        int[] out = new int[size];
        for (int i = 0; i < size; i++) out[i] = buf.readVarInt();
        return out;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GlintPrintPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp && sp.containerMenu instanceof GlintTableMenu m) {
                m.print(pkt.design, pkt.speed, pkt.scale, pkt.opacity, pkt.glow, pkt.glowAuto, pkt.named, pkt.name, pkt.simultaneous, pkt.scrollDir, pkt.scrollOffset, pkt.interpolate, pkt.glowHex, pkt.nameHex, pkt.shardDyes, pkt.donorColors, pkt.belowLayers, pkt.aboveLayers, pkt.sourceSimultaneous);
            }
        });
    }
}
