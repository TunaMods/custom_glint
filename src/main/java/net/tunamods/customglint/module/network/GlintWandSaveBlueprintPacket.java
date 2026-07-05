package net.tunamods.customglint.module.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.tunamods.customglint.module.blueprint.ServerBlueprints;

import java.util.function.Supplier;

/**
 * C→S: the wand editor's "Save Design" saves the current build to the server's shared blueprint pool
 * ({@link ServerBlueprints}), the same store the Glint Table and {@code /glint export} use. Anyone holding
 * the wand may save — the wand itself is the gate, so there is no op check (matching the delete path). The
 * server validates the JSON (well-formed object with a non-empty {@code layers} array, within size/count
 * caps), writes it under a unique filename, and re-syncs the pool so the sender's Import list updates.
 */
public class GlintWandSaveBlueprintPacket {

    /** Reject oversized payloads before they touch disk. A real trim is a few KB. */
    private static final int MAX_JSON = 64 * 1024;

    public final String baseName;
    public final String json;

    public GlintWandSaveBlueprintPacket(String baseName, String json) {
        this.baseName = baseName;
        this.json = json;
    }

    public static void encode(GlintWandSaveBlueprintPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.baseName);
        buf.writeUtf(pkt.json, MAX_JSON);
    }

    public static GlintWandSaveBlueprintPacket decode(FriendlyByteBuf buf) {
        return new GlintWandSaveBlueprintPacket(buf.readUtf(), buf.readUtf(MAX_JSON));
    }

    public static void handle(GlintWandSaveBlueprintPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp == null) return;
            if (pkt.json == null || pkt.json.length() > MAX_JSON) return;
            if (ServerBlueprints.count() >= ServerBlueprints.MAX_BLUEPRINTS) return;
            // Validate + normalize the (untrusted) JSON: only a well-formed object carrying at least one
            // layer is written, and it is re-serialized so nothing but clean JSON lands on disk.
            String clean;
            try {
                JsonObject obj = JsonParser.parseString(pkt.json).getAsJsonObject();
                if (!obj.has("layers") || obj.getAsJsonArray("layers").isEmpty()) return;
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                clean = gson.toJson(obj);
            } catch (Exception e) {
                return; // malformed: ignore
            }
            ServerBlueprints.saveUnique(pkt.baseName, clean);
            ServerBlueprints.syncTo(sp);
        });
        ctx.get().setPacketHandled(true);
    }
}
