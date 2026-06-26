package net.tunamods.customglint.module.network;

import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlintWandItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class GiveGlintTrimPacket implements CustomPacketPayload {

    public static final Type<GiveGlintTrimPacket> TYPE =
            new Type<>(CustomGlint.res("give_glint_trim"));

    public static final StreamCodec<FriendlyByteBuf, GiveGlintTrimPacket> STREAM_CODEC =
            StreamCodec.of(GiveGlintTrimPacket::encode, GiveGlintTrimPacket::decode);

    public final CustomGlint.Layer[] layers;
    public final boolean glowing;
    public final int[] glowColors;
    public final String trimName;
    public final int trimNameColor;

    public GiveGlintTrimPacket(CustomGlint.Layer[] layers, boolean glowing, int[] glowColors, String trimName, int trimNameColor) {
        this.layers = layers;
        this.glowing = glowing;
        this.glowColors = glowColors;
        this.trimName = trimName;
        this.trimNameColor = trimNameColor;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(FriendlyByteBuf buf, GiveGlintTrimPacket pkt) {
        GlintApplyPacket.writeLayers(buf, pkt.layers);
        buf.writeBoolean(pkt.glowing);
        buf.writeVarInt(pkt.glowColors.length);
        for (int c : pkt.glowColors) buf.writeInt(c);
        buf.writeUtf(pkt.trimName);
        buf.writeInt(pkt.trimNameColor);
    }

    public static GiveGlintTrimPacket decode(FriendlyByteBuf buf) {
        CustomGlint.Layer[] layers = GlintApplyPacket.readLayers(buf, 8);
        boolean glowing = buf.readBoolean();
        int[] glowColors = GlintApplyPacket.readCappedColors(buf);
        String trimName = buf.readUtf(32767);
        int trimNameColor = buf.readInt();
        return new GiveGlintTrimPacket(layers, glowing, glowColors, trimName, trimNameColor);
    }

    public static void handle(GiveGlintTrimPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            // Server-authoritative gate. The give-trim button is reached only through the wand editor, and
            // the wand is creative/command-only (no recipe). The packet is client-sent, so re-verify the
            // player actually holds the wand before minting a finished trim; without this a modified client
            // could send the packet with no wand and get free painted trims. (Mirrors GlintApplyPacket's
            // gate; that path also requires creative because it can spawn ARBITRARY items, while this one
            // only ever produces a craftable Glint Trim.)
            boolean holdingWand = player.getMainHandItem().getItem() instanceof GlintWandItem
                    || player.getOffhandItem().getItem() instanceof GlintWandItem;
            if (!holdingWand) return;

            ItemStack trim = new ItemStack(ModItems.GLINT_TRIM.get());

            if (pkt.layers.length > 0) {
                // Roll a unique seed into any unseeded chromatic layer (the editor builds layers without one).
                CustomGlint.Layer[] seeded = CustomGlint.ensureChromaticSeeds(pkt.layers);
                CustomGlint.Layer layer0 = seeded[0];
                for (int color : layer0.colors()) GlintTrimItem.addColor(trim, color);
                GlintTrimItem.setSpeed(trim, layer0.speed());
                GlintTrimItem.setScale(trim, layer0.patternScale());
                GlintTrimItem.setScrollDir(trim, layer0.scrollDir());
                GlintTrimItem.setScrollOffset(trim, layer0.scrollOffset());
                GlintTrimItem.setPattern(trim, layer0.design());
                GlintTrimItem.setGlowing(trim, pkt.glowing);

                // The GlintTrimItem setters above only seed the trim's display config + CustomModelData;
                // each of them rewrites the glint Data as a single SEQUENTIAL layer. Write the real Data
                // from the packet layers last so every layer's simultaneous flag (and any extra layers)
                // is preserved, otherwise a single-layer simultaneous trim always came out sequential.
                CustomGlint.write(trim, seeded);
                CustomGlint.setGlowing(trim, pkt.glowing);
                // Keep the TrimConfig seed in sync with the Data we just wrote (setPattern rolled its own,
                // independent seed; align both so the preview, smithing transfer, and later edits agree).
                GlintTrimItem.setSeed(trim, layer0.seed());
            }

            // Apply custom name and color if provided
            if (!pkt.trimName.isEmpty()) {
                Component displayName = Component.literal(pkt.trimName)
                    .withStyle(s -> s.withColor(TextColor.fromRgb((pkt.trimNameColor >>> 8) & 0xFFFFFF)));
                trim.set(DataComponents.CUSTOM_NAME, displayName);
            }

            player.addItem(trim);
        });
    }
}
