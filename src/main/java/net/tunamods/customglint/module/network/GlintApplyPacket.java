package net.tunamods.customglint.module.network;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintWandItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.function.Supplier;

public class GlintApplyPacket {

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

    public static void encode(GlintApplyPacket pkt, FriendlyByteBuf buf) {
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
                buf.writeVarInt(layer.scrollDir());
                buf.writeFloat(layer.scrollOffset());
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
            int scrollDir = buf.readVarInt();
            float scrollOffset = buf.readFloat();
            layers[i] = new CustomGlint.Layer(new ResourceLocation(design), colors, speed, interp, scale, simultaneous, scrollDir, scrollOffset);
        }
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

    /** Hard cap on a layer-array length on the wire (anti-DoS), shared by the Glint Table print packet. */
    public static final int MAX_WIRE_COUNT = 256;

    /** Serializes a Layer[] (design, colors, speed, interpolate, scale, simultaneous, scrollDir, scrollOffset)
     *  — the shared wire format used by the editor packets and the Glint Table print packet. */
    public static void writeLayers(FriendlyByteBuf buf, CustomGlint.Layer[] layers) {
        buf.writeVarInt(layers.length);
        for (CustomGlint.Layer l : layers) {
            buf.writeUtf(l.design().toString());
            buf.writeVarInt(l.colors().length);
            for (int c : l.colors()) buf.writeInt(c);
            buf.writeFloat(l.speed());
            buf.writeBoolean(l.interpolate());
            buf.writeFloat(l.patternScale());
            buf.writeBoolean(l.simultaneous());
            buf.writeVarInt(l.scrollDir());
            buf.writeFloat(l.scrollOffset());
        }
    }

    /** Inverse of {@link #writeLayers}. Reads the full sent array (draining the buffer cleanly) but keeps at
     *  most {@code cap} layers; throws on a bogus count. */
    public static CustomGlint.Layer[] readLayers(FriendlyByteBuf buf, int cap) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_WIRE_COUNT) throw new io.netty.handler.codec.DecoderException("Bad layer count: " + n);
        int keep = Math.max(0, Math.min(n, cap));
        CustomGlint.Layer[] layers = new CustomGlint.Layer[keep];
        for (int i = 0; i < n; i++) {
            String design = buf.readUtf();
            int sentLen = buf.readVarInt();
            if (sentLen < 0 || sentLen > MAX_WIRE_COUNT) throw new io.netty.handler.codec.DecoderException("Bad color count: " + sentLen);
            int keepLen = Math.min(sentLen, 8);
            int[] colors = new int[keepLen];
            for (int j = 0; j < sentLen; j++) { int c = buf.readInt(); if (j < keepLen) colors[j] = c; }
            float speed = buf.readFloat();
            if (speed <= 0) speed = 1.0f;
            boolean interp = buf.readBoolean();
            float scale = buf.readFloat();
            boolean sim = buf.readBoolean();
            int scrollDir = buf.readVarInt();
            float scrollOffset = buf.readFloat();
            if (i < keep) layers[i] = new CustomGlint.Layer(new ResourceLocation(design), colors, speed, interp, scale, sim, scrollDir, scrollOffset);
        }
        return layers;
    }

    public static void handle(GlintApplyPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
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
                // Roll fresh per-trim seeds once at commit (the editor doesn't track them) so the wand and
                // the target item share the same chromatic pattern.
                CustomGlint.Layer[] layers = CustomGlint.ensureChromaticSeeds(pkt.layers);
                if (!pkt.wandOnly) {
                    ItemStack target = player.getItemInHand(otherHand);
                    if (!target.isEmpty()) {
                        CustomGlint.write(target, layers);
                        applyGlow(pkt, target);
                        applyName(pkt, target);
                    }
                }
                if (wandIsWand) {
                    CustomGlint.write(wand, layers);
                    applyGlow(pkt, wand);
                    applyName(pkt, wand);
                }
            } else {
                CustomGlint.Layer[] layers = CustomGlint.ensureChromaticSeeds(pkt.layers);
                Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(pkt.itemId));
                if (item == null) return;
                ItemStack given = new ItemStack(item);
                CustomGlint.write(given, layers);
                applyGlow(pkt, given);
                applyName(pkt, given);
                player.addItem(given);
                if (wandIsWand) {
                    CustomGlint.write(wand, layers);
                    applyGlow(pkt, wand);
                    applyName(pkt, wand);
                }
            }
        });
        ctx.get().setPacketHandled(true);
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
            stack.setHoverName(displayName);
        }
    }
}
