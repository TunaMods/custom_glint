package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;

/** Shared stream-codec helpers for the mod's packets. */
final class NetworkCodecs {
    private NetworkCodecs() {}

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
}
