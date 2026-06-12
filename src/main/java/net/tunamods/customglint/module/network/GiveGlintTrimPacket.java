package net.tunamods.customglint.module.network;

import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static net.tunamods.customglint.CustomGlintMod.MOD_ID;

public class GiveGlintTrimPacket implements CustomPacketPayload {

    public static final Type<GiveGlintTrimPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "give_glint_trim"));

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
        buf.writeVarInt(pkt.layers.length);
        for (CustomGlint.Layer layer : pkt.layers) {
            buf.writeUtf(layer.design().toString());
            buf.writeVarInt(layer.colors().length);
            for (int c : layer.colors()) buf.writeInt(c);
            buf.writeFloat(layer.speed());
            buf.writeBoolean(layer.interpolate());
            buf.writeFloat(layer.patternScale());
            buf.writeBoolean(layer.simultaneous());
        }
        buf.writeBoolean(pkt.glowing);
        buf.writeVarInt(pkt.glowColors.length);
        for (int c : pkt.glowColors) buf.writeInt(c);
        buf.writeUtf(pkt.trimName);
        buf.writeInt(pkt.trimNameColor);
    }

    public static GiveGlintTrimPacket decode(FriendlyByteBuf buf) {
        int layerCount = Math.min(buf.readVarInt(), 8);
        CustomGlint.Layer[] layers = new CustomGlint.Layer[layerCount];
        for (int i = 0; i < layerCount; i++) {
            String design = buf.readUtf();
            int colorLen = Math.min(buf.readVarInt(), 8);
            int[] colors = new int[colorLen];
            for (int j = 0; j < colorLen; j++) colors[j] = buf.readInt();
            float speed = buf.readFloat();
            if (speed <= 0) speed = 1.0f;
            boolean interp = buf.readBoolean();
            float scale = buf.readFloat();
            boolean simultaneous = buf.readBoolean();
            layers[i] = new CustomGlint.Layer(ResourceLocation.parse(design), colors, speed, interp, scale, simultaneous);
        }
        boolean glowing = buf.readBoolean();
        int gcLen = Math.min(buf.readVarInt(), 8);
        int[] glowColors = new int[gcLen];
        for (int i = 0; i < gcLen; i++) glowColors[i] = buf.readInt();
        String trimName = buf.readUtf(32767);
        int trimNameColor = buf.readInt();
        return new GiveGlintTrimPacket(layers, glowing, glowColors, trimName, trimNameColor);
    }

    public static void handle(GiveGlintTrimPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            ItemStack trim = new ItemStack(CustomGlintMod.GLINT_TRIM.get());

            if (pkt.layers.length > 0) {
                CustomGlint.Layer layer0 = pkt.layers[0];
                for (int color : layer0.colors()) GlintTrimItem.addColor(trim, color);
                GlintTrimItem.setSpeed(trim, layer0.speed());
                GlintTrimItem.setScale(trim, layer0.patternScale());
                GlintTrimItem.setPattern(trim, layer0.design());
                GlintTrimItem.setGlowing(trim, pkt.glowing);
                CustomGlint.setGlowing(trim, pkt.glowing);

                if (pkt.layers.length > 1) {
                    CustomGlint.remove(trim);
                    CustomGlint.write(trim, pkt.layers);
                    CustomGlint.setGlowing(trim, pkt.glowing);
                }
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
