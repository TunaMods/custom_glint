package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tunamods.customglint.common.CustomGlint;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S→C: the dedicated server's shared blueprint trims (the server's own {@code config/glint-and-glamour/trims/*.json}),
 * pushed to the client when a Glint Table opens. These are the admin-curated build targets every player can
 * import but only ops can delete; they are distinct from the player's personal client-side blueprints (which
 * live in the client's own config dir and never round-trip through the server). Sent as name → raw JSON so the
 * client reuses the same parser it uses for local files. The integrated (single-player) server never sends
 * this; there the client's local scan already covers the same directory.
 */
public record GlintServerBlueprintsSyncPacket(Map<String, String> blueprints) implements CustomPacketPayload {

    public static final Type<GlintServerBlueprintsSyncPacket> TYPE =
            new Type<>(CustomGlint.res("glint_server_blueprints"));

    /** Decode ceiling on blueprint entries, four times {@code ServerBlueprints.MAX_BLUEPRINTS} so a legitimate
     *  full pool always fits. */
    private static final int MAX_ENTRIES = 4096;

    public static final StreamCodec<FriendlyByteBuf, GlintServerBlueprintsSyncPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeVarInt(pkt.blueprints.size());
                        for (Map.Entry<String, String> e : pkt.blueprints.entrySet()) {
                            buf.writeUtf(e.getKey());
                            buf.writeUtf(e.getValue());
                        }
                    },
                    buf -> {
                        int count = buf.readVarInt();
                        Map<String, String> map = new LinkedHashMap<>();
                        // Read at most MAX_ENTRIES pairs. This is the payload's only field, so stopping short
                        // of an absurd count can't misalign anything after it.
                        for (int i = 0; i < Math.max(0, Math.min(count, MAX_ENTRIES)); i++) {
                            String name = buf.readUtf();
                            String json = buf.readUtf();
                            map.put(name, json);
                        }
                        return new GlintServerBlueprintsSyncPacket(map);
                    }
            );

    /** Client-side mirror of the connected server's shared blueprints (name → raw JSON). Empty in single-player. */
    public static final Map<String, String> CLIENT_SERVER_BLUEPRINTS = new LinkedHashMap<>();

    /** Drops the mirror on disconnect so a later session can't read the previous server's blueprints. */
    public static void clearClient() { CLIENT_SERVER_BLUEPRINTS.clear(); }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GlintServerBlueprintsSyncPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            CLIENT_SERVER_BLUEPRINTS.clear();
            CLIENT_SERVER_BLUEPRINTS.putAll(pkt.blueprints);
        });
    }
}
