package net.tunamods.customglint.module.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.blueprint.ServerBlueprints;
import net.tunamods.customglint.module.item.GlintWandItem;

/**
 * C→S: the wand editor's "Save Design" saves the current build to the server's shared blueprint pool
 * ({@link ServerBlueprints}), the same store the Glint Table and {@code /glint export} use. Anyone holding
 * the wand may save; the wand itself is the gate, so there is no op check (matching the delete path). The
 * server validates the JSON (well-formed object with a non-empty {@code layers} array, within size/count
 * caps), writes it under a unique filename, and re-syncs the pool so the sender's Import list updates.
 */
public record GlintWandSaveBlueprintPacket(String baseName, String json) implements CustomPacketPayload {

    public static final Type<GlintWandSaveBlueprintPacket> TYPE =
            new Type<>(CustomGlint.res("glint_wand_save_blueprint"));

    /** Reject oversized payloads before they touch disk. A real trim is a few KB. Shared with the sync codec
     *  so a blueprint accepted here can't later exceed the sync's UTF cap. */
    private static final int MAX_JSON = ServerBlueprints.MAX_JSON;

    public static final StreamCodec<FriendlyByteBuf, GlintWandSaveBlueprintPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> { buf.writeUtf(pkt.baseName); buf.writeUtf(pkt.json, MAX_JSON); },
            buf -> new GlintWandSaveBlueprintPacket(buf.readUtf(), buf.readUtf(MAX_JSON)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GlintWandSaveBlueprintPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            // The packet is client-sent, so re-verify the sender holds the wand (the stated gate) before
            // writing a shared file. Mirrors GiveGlintTrimPacket / GlintApplyPacket.
            if (!(sp.getMainHandItem().getItem() instanceof GlintWandItem
                    || sp.getOffhandItem().getItem() instanceof GlintWandItem)) return;
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
    }
}
