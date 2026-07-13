package net.tunamods.customglint.module.network;

import io.netty.handler.codec.DecoderException;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
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

import java.util.ArrayList;
import java.util.List;
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
            writeLayers(buf, pkt.layers);
            buf.writeUtf(pkt.itemId);
            buf.writeBoolean(pkt.glowing);
            writeColors(buf, pkt.glowColors);
            buf.writeUtf(pkt.trimName);
            buf.writeInt(pkt.trimNameColor);
        }
        buf.writeBoolean(pkt.wandOnly);
    }

    public static GlintApplyPacket decode(FriendlyByteBuf buf) {
        // bounds-check the hand ordinal ourselves; readEnum() has none.
        int handOrd = buf.readVarInt();
        InteractionHand[] hands = InteractionHand.values();
        InteractionHand hand = (handOrd >= 0 && handOrd < hands.length) ? hands[handOrd] : InteractionHand.MAIN_HAND;
        boolean remove = buf.readBoolean();
        if (remove) {
            boolean wandOnly = buf.readBoolean();
            return new GlintApplyPacket(hand, true, new CustomGlint.Layer[0], "", false, new int[0], "", 0xFFFFFFFF, wandOnly);
        }
        CustomGlint.Layer[] layers = readLayers(buf, CustomGlint.MAX_LAYERS);
        String itemId = buf.readUtf();
        boolean glowing = buf.readBoolean();
        int[] glowColors = readCappedColors(buf, CustomGlint.MAX_COLORS_PER_LAYER);
        String trimName = buf.readUtf();
        int trimNameColor = buf.readInt();
        boolean wandOnly = buf.readBoolean();
        return new GlintApplyPacket(hand, false, layers, itemId, glowing, glowColors, trimName, trimNameColor, wandOnly);
    }

    /** Hard cap on a layer-array length on the wire (anti-DoS), shared by the Glint Table print packet. */
    public static final int MAX_WIRE_COUNT = 256;

    /** Serializes a Layer[] (design, colors, speed, interpolate, scale, simultaneous, scrollDir, scrollOffset)
     *  - the shared wire format used by the editor packets and the Glint Table print packet. */
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

    /** Writes a {@code writeVarInt(len) + len*writeInt} color array; the counterpart to {@link #readCappedColors}. */
    public static void writeColors(FriendlyByteBuf buf, int[] colors) {
        buf.writeVarInt(colors.length);
        for (int c : colors) buf.writeInt(c);
    }

    /** Inverse of {@link #writeLayers}. Reads the full sent array (draining the buffer cleanly) but keeps at
     *  most {@code cap} layers; throws on a bogus count. */
    public static CustomGlint.Layer[] readLayers(FriendlyByteBuf buf, int cap) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_WIRE_COUNT) throw new DecoderException("Bad layer count: " + n);
        List<CustomGlint.Layer> layers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String design = buf.readUtf();
            int sentLen = buf.readVarInt();
            if (sentLen < 0 || sentLen > MAX_WIRE_COUNT) throw new DecoderException("Bad color count: " + sentLen);
            int keepLen = Math.min(sentLen, CustomGlint.MAX_COLORS_PER_LAYER);
            int[] colors = new int[keepLen];
            for (int j = 0; j < sentLen; j++) { int c = buf.readInt(); if (j < keepLen) colors[j] = c; }
            float speed = buf.readFloat();
            if (speed <= 0 || !Float.isFinite(speed)) speed = 1.0f;
            boolean interp = buf.readBoolean();
            float scale = buf.readFloat();
            if (!Float.isFinite(scale)) scale = 1.0f;
            boolean sim = buf.readBoolean();
            int scrollDir = buf.readVarInt();
            float scrollOffset = buf.readFloat();
            if (!Float.isFinite(scrollOffset)) scrollOffset = 0.0f;
            ResourceLocation rl = ResourceLocation.tryParse(design);
            if (rl != null && layers.size() < cap)
                layers.add(new CustomGlint.Layer(rl, colors, speed, interp, scale, sim, scrollDir, scrollOffset));
        }
        return layers.toArray(new CustomGlint.Layer[0]);
    }

    /** Reads a {@code writeVarInt(len) + len*writeInt} color array, draining the full sent length but
     *  keeping at most {@code cap} entries; throws on a bogus count. */
    public static int[] readCappedColors(FriendlyByteBuf buf, int cap) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_WIRE_COUNT) throw new DecoderException("Bad color count: " + n);
        int keep = Math.min(n, cap);
        int[] out = new int[keep];
        for (int i = 0; i < n; i++) { int c = buf.readInt(); if (i < keep) out[i] = c; }
        return out;
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
                // Give-item path: require the sender to actually hold a wand.
                if (!wandIsWand) return;
                CustomGlint.Layer[] layers = CustomGlint.ensureChromaticSeeds(pkt.layers);
                ResourceLocation itemRl = ResourceLocation.tryParse(pkt.itemId);
                if (itemRl == null) return;
                Item item = ForgeRegistries.ITEMS.getValue(itemRl);
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
            int rgb = (pkt.trimNameColor >>> 8) & 0xFFFFFF; // wire packs the name colour as (rgb << 8) | alpha
            stack.setHoverName(GlintTrimItem.coloredName(pkt.trimName, rgb));
        }
    }
}
