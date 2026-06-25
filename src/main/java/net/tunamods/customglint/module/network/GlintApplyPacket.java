package net.tunamods.customglint.module.network;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintWandItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static net.tunamods.customglint.CustomGlintMod.MOD_ID;

public class GlintApplyPacket implements CustomPacketPayload {

    public static final Type<GlintApplyPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "glint_apply"));

    public static final StreamCodec<FriendlyByteBuf, GlintApplyPacket> STREAM_CODEC =
            StreamCodec.of(GlintApplyPacket::encode, GlintApplyPacket::decode);

    public final InteractionHand wandHand;
    public final boolean remove;
    public final CustomGlint.Layer[] layers;
    public final String itemId;
    public final boolean glowing;
    public final int[] glowColors;
    public final String trimName;
    public final int trimNameColor;
    public final boolean wandOnly;

    public GlintApplyPacket(InteractionHand wandHand, boolean remove, CustomGlint.Layer[] layers, String itemId, boolean glowing, int[] glowColors) {
        this(wandHand, remove, layers, itemId, glowing, glowColors, "", 0xFFFFFFFF, false);
    }

    public GlintApplyPacket(InteractionHand wandHand, boolean remove, CustomGlint.Layer[] layers, String itemId, boolean glowing, int[] glowColors, String trimName, int trimNameColor) {
        this(wandHand, remove, layers, itemId, glowing, glowColors, trimName, trimNameColor, false);
    }

    public GlintApplyPacket(InteractionHand wandHand, boolean remove, CustomGlint.Layer[] layers, String itemId, boolean glowing, int[] glowColors, String trimName, int trimNameColor, boolean wandOnly) {
        this.wandHand = wandHand;
        this.remove = remove;
        this.layers = layers;
        this.itemId = itemId;
        this.glowing = glowing;
        this.glowColors = glowColors;
        this.trimName = trimName;
        this.trimNameColor = trimNameColor;
        this.wandOnly = wandOnly;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(FriendlyByteBuf buf, GlintApplyPacket pkt) {
        buf.writeEnum(pkt.wandHand);
        buf.writeBoolean(pkt.remove);
        if (!pkt.remove) {
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
            buf.writeUtf(pkt.itemId);
            buf.writeBoolean(pkt.glowing);
            buf.writeVarInt(pkt.glowColors.length);
            for (int c : pkt.glowColors) buf.writeInt(c);
            buf.writeUtf(pkt.trimName);
            buf.writeInt(pkt.trimNameColor);
        }
        buf.writeBoolean(pkt.wandOnly);
    }

    public static GlintApplyPacket decode(FriendlyByteBuf buf) {
        InteractionHand hand = buf.readEnum(InteractionHand.class);
        boolean remove = buf.readBoolean();
        if (remove) {
            boolean wandOnly = buf.readBoolean();
            return new GlintApplyPacket(hand, true, new CustomGlint.Layer[0], "", false, new int[0], "", 0xFFFFFFFF, wandOnly);
        }
        int layerCount = Math.min(buf.readVarInt(), 8);
        java.util.List<CustomGlint.Layer> parsed = new java.util.ArrayList<>(layerCount);
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
            ResourceLocation designRl = ResourceLocation.tryParse(design);
            if (designRl == null || colors.length == 0) continue;
            parsed.add(new CustomGlint.Layer(designRl, colors, speed, interp, scale, simultaneous));
        }
        CustomGlint.Layer[] layers = parsed.toArray(new CustomGlint.Layer[0]);
        String itemId = buf.readUtf();
        boolean glowing = buf.readBoolean();
        int gcLen = Math.min(buf.readVarInt(), 8);
        int[] glowColors = new int[gcLen];
        for (int i = 0; i < gcLen; i++) glowColors[i] = buf.readInt();
        String trimName = buf.readUtf();
        int trimNameColor = buf.readInt();
        boolean wandOnly = buf.readBoolean();
        return new GlintApplyPacket(hand, false, layers, itemId, glowing, glowColors, trimName, trimNameColor, wandOnly);
    }

    public static void handle(GlintApplyPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            InteractionHand otherHand = pkt.wandHand == InteractionHand.MAIN_HAND
                    ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            ItemStack wand = player.getItemInHand(pkt.wandHand);
            boolean wandIsWand = !wand.isEmpty() && wand.getItem() instanceof GlintWandItem;
            if (pkt.remove) {
                if (!pkt.wandOnly) {
                    ItemStack target = player.getItemInHand(otherHand);
                    if (!target.isEmpty()) CustomGlint.remove(target);
                }
                if (wandIsWand) CustomGlint.remove(wand);
            } else if (pkt.itemId.isEmpty()) {
                if (!pkt.wandOnly) {
                    ItemStack target = player.getItemInHand(otherHand);
                    if (!target.isEmpty()) {
                        CustomGlint.write(target, pkt.layers);
                        applyGlow(pkt, target);
                        applyName(pkt, target);
                    }
                }
                if (wandIsWand) {
                    CustomGlint.write(wand, pkt.layers);
                    applyGlow(pkt, wand);
                    applyName(pkt, wand);
                }
            } else {
                Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(pkt.itemId)).orElse(null);
                if (item == null) return;
                ItemStack given = new ItemStack(item);
                CustomGlint.write(given, pkt.layers);
                applyGlow(pkt, given);
                applyName(pkt, given);
                player.addItem(given);
                if (wandIsWand) {
                    CustomGlint.write(wand, pkt.layers);
                    applyGlow(pkt, wand);
                    applyName(pkt, wand);
                }
            }
        });
    }

    private static void applyGlow(GlintApplyPacket pkt, ItemStack stack) {
        CustomGlint.clearGlowColors(stack);
        CustomGlint.setGlowing(stack, pkt.glowing);
        if (pkt.glowColors.length > 0) CustomGlint.setGlowColors(stack, pkt.glowColors);
    }

    private static void applyName(GlintApplyPacket pkt, ItemStack stack) {
        if (!pkt.trimName.isEmpty()) {
            Component displayName = Component.literal(pkt.trimName)
                .withStyle(s -> s.withColor(TextColor.fromRgb((pkt.trimNameColor >>> 8) & 0xFFFFFF)));
            stack.set(DataComponents.CUSTOM_NAME, displayName);
        }
    }
}
