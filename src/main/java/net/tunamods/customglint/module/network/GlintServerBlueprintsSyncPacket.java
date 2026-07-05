package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * S→C: the dedicated server's shared blueprint trims (the server's own {@code config/customglint/trims/*.json}),
 * pushed to the client when a Glint Table opens. These are the admin-curated build targets every player can
 * import but only ops can delete; they are distinct from the player's personal client-side blueprints (which
 * live in the client's own config dir and never round-trip through the server). Sent as name → raw JSON so the
 * client reuses the same parser it uses for local files. The integrated (single-player) server never sends
 * this — there the client's local scan already covers the same directory.
 */
public class GlintServerBlueprintsSyncPacket {

    public final Map<String, String> blueprints;

    public GlintServerBlueprintsSyncPacket(Map<String, String> blueprints) {
        this.blueprints = blueprints;
    }

    public static void encode(GlintServerBlueprintsSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.blueprints.size());
        for (Map.Entry<String, String> e : pkt.blueprints.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeUtf(e.getValue());
        }
    }

    public static GlintServerBlueprintsSyncPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < Math.max(0, Math.min(count, 4096)); i++) {
            String name = buf.readUtf();
            String json = buf.readUtf();
            map.put(name, json);
        }
        return new GlintServerBlueprintsSyncPacket(map);
    }

    /** Client-side mirror of the connected server's shared blueprints (name → raw JSON). Empty in single-player. */
    public static final Map<String, String> CLIENT_SERVER_BLUEPRINTS = new LinkedHashMap<>();

    /** Drops the mirror on disconnect so a later session can't read the previous server's blueprints. */
    public static void clearClient() { CLIENT_SERVER_BLUEPRINTS.clear(); }

    public static void handle(GlintServerBlueprintsSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            CLIENT_SERVER_BLUEPRINTS.clear();
            CLIENT_SERVER_BLUEPRINTS.putAll(pkt.blueprints);
        });
        ctx.get().setPacketHandled(true);
    }
}
