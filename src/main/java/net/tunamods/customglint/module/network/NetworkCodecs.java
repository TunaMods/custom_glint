package net.tunamods.customglint.module.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.tunamods.customglint.common.CustomGlint;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/**
 * Wire helpers shared by the mod's packets: the single-field payload codecs, and the layer / color array
 * format several payloads embed.
 *
 * Naming convention: {@code write*} / {@code read*} are the raw buffer halves of one format and are always
 * symmetric; the {@code string} / {@code varInt} / {@code stringList} factories build a whole
 * {@link StreamCodec} out of such a pair.
 *
 * The array reads cap what they KEEP but still drain every element the sender wrote, so the buffer stays
 * aligned for the fields that follow. A count past {@link #MAX_WIRE_COUNT} is rejected outright instead,
 * since draining it is what a hostile sender wants.
 */
final class NetworkCodecs {
    private NetworkCodecs() {}

    /** Hard upper bound on any wire-supplied count drained in a read loop (colors, layers, shards). Legit
     *  values are ≤16; a larger count throws DecoderException at decode instead of underflowing the buffer. */
    static final int MAX_WIRE_COUNT = 256;

    /** Layers kept from a trim payload. The editor and the Glint Table both stop at 8 layers, so anything
     *  past that is a malformed or hostile sender. */
    static final int MAX_TRIM_LAYERS = 8;

    /** Codec for a payload whose whole body is one UTF string. */
    static <T> StreamCodec<FriendlyByteBuf, T> string(Function<T, String> getter, Function<String, T> factory) {
        return StreamCodec.of(
                (buf, pkt) -> buf.writeUtf(getter.apply(pkt)),
                buf -> factory.apply(buf.readUtf()));
    }

    /** Codec for a payload whose whole body is one VarInt (a library index, in practice). */
    static <T> StreamCodec<FriendlyByteBuf, T> varInt(ToIntFunction<T> getter, IntFunction<T> factory) {
        return StreamCodec.of(
                (buf, pkt) -> buf.writeVarInt(getter.applyAsInt(pkt)),
                buf -> factory.apply(buf.readVarInt()));
    }

    /** Length-prefixed {@code List<String>} codec. Decode caps the pre-sized capacity at {@code cap} so a
     *  bogus count can't pre-allocate a huge array; the loop still drains the real count. */
    static StreamCodec<FriendlyByteBuf, List<String>> stringList(int cap) {
        return StreamCodec.of(
                (buf, list) -> {
                    buf.writeVarInt(list.size());
                    for (String s : list) buf.writeUtf(s);
                },
                buf -> {
                    int count = buf.readVarInt();
                    List<String> list = new ArrayList<>(Math.max(0, Math.min(count, cap)));
                    for (int i = 0; i < count; i++) list.add(buf.readUtf());
                    return list;
                });
    }

    /** Writes a color array as a VarInt length followed by each ARGB int. Symmetric with {@link #readCappedColors}. */
    static void writeColors(FriendlyByteBuf buf, int[] colors) {
        buf.writeVarInt(colors.length);
        for (int c : colors) buf.writeInt(c);
    }

    /** Reads a color array, keeping at most {@link CustomGlint#MAX_COLORS_PER_LAYER}. */
    static int[] readCappedColors(FriendlyByteBuf buf) {
        int sent = buf.readVarInt();
        if (sent < 0 || sent > MAX_WIRE_COUNT) throw new DecoderException("Bad color count: " + sent);
        int len = Math.min(sent, CustomGlint.MAX_COLORS_PER_LAYER);
        int[] colors = new int[len];
        for (int j = 0; j < sent; j++) {
            int c = buf.readInt();
            if (j < len) colors[j] = c;
        }
        return colors;
    }

    /** Shared layer-array wire format. Symmetric with {@link #readLayers}. */
    static void writeLayers(FriendlyByteBuf buf, CustomGlint.Layer[] layers) {
        buf.writeVarInt(layers.length);
        for (CustomGlint.Layer layer : layers) {
            buf.writeUtf(layer.design().toString());
            writeColors(buf, layer.colors());
            buf.writeFloat(layer.speed());
            buf.writeBoolean(layer.interpolate());
            buf.writeFloat(layer.patternScale());
            buf.writeBoolean(layer.simultaneous());
            buf.writeVarInt(layer.scrollDir());
            buf.writeFloat(layer.scrollOffset());
            buf.writeInt(layer.seed());
        }
    }

    /** Reads a layer array, keeping at most {@code cap}. Malformed design strings fall back to the vanilla
     *  glint and a non-positive speed is clamped to 1, keeping the trailing fields aligned. */
    static CustomGlint.Layer[] readLayers(FriendlyByteBuf buf, int cap) {
        int sent = buf.readVarInt();
        if (sent < 0 || sent > MAX_WIRE_COUNT) throw new DecoderException("Bad layer count: " + sent);
        int keep = Math.max(0, Math.min(sent, cap));
        CustomGlint.Layer[] layers = new CustomGlint.Layer[keep];
        for (int i = 0; i < sent; i++) {
            String design = buf.readUtf();
            int[] colors = readCappedColors(buf);
            float speed = buf.readFloat();
            if (speed <= 0) speed = 1.0f;
            boolean interp = buf.readBoolean();
            float scale = buf.readFloat();
            boolean simultaneous = buf.readBoolean();
            int scrollDir = buf.readVarInt();
            float scrollOffset = buf.readFloat();
            int seed = buf.readInt();
            if (i >= keep) continue; // drained for alignment; not stored
            Identifier designRl = Identifier.tryParse(design);
            if (designRl == null) designRl = CustomGlint.VANILLA;
            layers[i] = new CustomGlint.Layer(designRl, colors, speed, interp, scale, simultaneous, scrollDir, scrollOffset, seed);
        }
        return layers;
    }

    /** VarInt-array read that rejects a negative or oversized count as a {@link DecoderException}. Vanilla
     *  {@link FriendlyByteBuf#readVarIntArray(int)} only checks the upper bound, so a negative size would
     *  otherwise throw {@code NegativeArraySizeException} rather than the clean decode error used elsewhere. */
    static int[] readCappedVarIntArray(FriendlyByteBuf buf, int cap) {
        int size = buf.readVarInt();
        if (size < 0 || size > cap) throw new DecoderException("Bad array size: " + size);
        int[] out = new int[size];
        for (int i = 0; i < size; i++) out[i] = buf.readVarInt();
        return out;
    }
}
