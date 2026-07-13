package net.tunamods.customglint.module.network;

import io.netty.handler.codec.DecoderException;
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
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintWandItem;

public class GlintApplyPacket implements CustomPacketPayload {

    public static final Type<GlintApplyPacket> TYPE =
            new Type<>(CustomGlint.res("glint_apply"));

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
            writeLayers(buf, pkt.layers);
            buf.writeUtf(pkt.itemId);
            writeGlowAndName(buf, pkt.glowing, pkt.glowColors, pkt.trimName, pkt.trimNameColor);
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
        // Use the hardened wire readers: they reject negative/oversized counts (a crafted client
        // could otherwise send a negative VarInt → NegativeArraySizeException) and fully drain the
        // sender's bytes so the trailing fields stay aligned. Empty colours stay valid.
        CustomGlint.Layer[] layers = readLayers(buf, 8);
        String itemId = buf.readUtf();
        GlowName gn = readGlowAndName(buf);
        boolean wandOnly = buf.readBoolean();
        return new GlintApplyPacket(hand, false, layers, itemId, gn.glowing(), gn.glowColors(), gn.name(), gn.nameColor(), wandOnly);
    }

    /** Defensive cap on wire-declared array sizes (colors / layers), shared by the layer helpers below. */
    static final int MAX_WIRE_COUNT = 256;

    /** Reads a color array, draining every int the sender wrote (so the buffer stays aligned) while keeping
     *  at most the first 8. */
    static int[] readCappedColors(FriendlyByteBuf buf) {
        int sent = buf.readVarInt();
        if (sent < 0 || sent > MAX_WIRE_COUNT) throw new DecoderException("Bad color count: " + sent);
        int len = Math.min(sent, 8);
        int[] colors = new int[len];
        for (int j = 0; j < sent; j++) {
            int c = buf.readInt();
            if (j < len) colors[j] = c;
        }
        return colors;
    }

    /** Trailing glow + custom-name wire block shared by GlintApplyPacket, GiveGlintTrimPacket, and
     *  GlintImportPacket: glowing flag, glow-color array, name string, packed name color. */
    record GlowName(boolean glowing, int[] glowColors, String name, int nameColor) {}

    static void writeGlowAndName(FriendlyByteBuf buf, boolean glowing, int[] glowColors, String name, int nameColor) {
        buf.writeBoolean(glowing);
        buf.writeVarInt(glowColors.length);
        for (int c : glowColors) buf.writeInt(c);
        buf.writeUtf(name);
        buf.writeInt(nameColor);
    }

    static GlowName readGlowAndName(FriendlyByteBuf buf) {
        boolean glowing = buf.readBoolean();
        int[] glowColors = readCappedColors(buf);
        String name = buf.readUtf();
        int nameColor = buf.readInt();
        return new GlowName(glowing, glowColors, name, nameColor);
    }

    /** Shared layer-array wire format (used by GiveGlintTrimPacket and GlintPrintPacket). Symmetric with
     *  {@link #readLayers}. */
    static void writeLayers(FriendlyByteBuf buf, CustomGlint.Layer[] layers) {
        buf.writeVarInt(layers.length);
        for (CustomGlint.Layer layer : layers) {
            buf.writeUtf(layer.design().toString());
            buf.writeVarInt(layer.colors().length);
            for (int c : layer.colors()) buf.writeInt(c);
            buf.writeFloat(layer.speed());
            buf.writeBoolean(layer.interpolate());
            buf.writeFloat(layer.patternScale());
            buf.writeBoolean(layer.simultaneous());
            buf.writeVarInt(layer.scrollDir());
            buf.writeFloat(layer.scrollOffset());
            buf.writeInt(layer.seed());
        }
    }

    /** Reads a layer array, draining every layer the sender wrote (so the buffer stays aligned) while keeping
     *  at most {@code cap}. Malformed design strings fall back to the vanilla glint; a non-positive speed is
     *  clamped, so a crafted packet can't throw on the network thread or desync the trailing fields. */
    static CustomGlint.Layer[] readLayers(FriendlyByteBuf buf, int cap) {
        int sent = buf.readVarInt();
        if (sent < 0 || sent > MAX_WIRE_COUNT) throw new DecoderException("Bad layer count: " + sent);
        int keep = Math.max(0, Math.min(sent, cap));
        CustomGlint.Layer[] layers = new CustomGlint.Layer[keep];
        for (int i = 0; i < sent; i++) {
            String design = buf.readUtf();
            int[] colors = readCappedColors(buf);
            float speed = buf.readFloat();
            if (speed <= 0) speed = 1.0f;
            boolean interp = buf.readBoolean();
            float scale = buf.readFloat();
            boolean simultaneous = buf.readBoolean();
            int scrollDir = buf.readVarInt();
            float scrollOffset = buf.readFloat();
            int seed = buf.readInt();
            if (i >= keep) continue; // drained for alignment; not stored
            ResourceLocation designRl = ResourceLocation.tryParse(design);
            if (designRl == null) designRl = CustomGlint.VANILLA;
            layers[i] = new CustomGlint.Layer(designRl, colors, speed, interp, scale, simultaneous, scrollDir, scrollOffset, seed);
        }
        return layers;
    }

    public static void handle(GlintApplyPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            InteractionHand otherHand = pkt.wandHand == InteractionHand.MAIN_HAND
                    ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            ItemStack wand = player.getItemInHand(pkt.wandHand);
            boolean wandIsWand = !wand.isEmpty() && wand.getItem() instanceof GlintWandItem;
            // Roll a stable oil-slick seed into any unseeded chromatic layer ONCE here, so the applied item
            // (and the wand copy) share the same pattern; the editor sends unseeded layers.
            CustomGlint.Layer[] seeded = CustomGlint.ensureChromaticSeeds(pkt.layers);
            if (pkt.remove) {
                if (!pkt.wandOnly) {
                    ItemStack target = player.getItemInHand(otherHand);
                    if (!target.isEmpty()) CustomGlint.remove(target);
                }
                if (wandIsWand) CustomGlint.remove(wand);
            } else if (pkt.itemId.isEmpty()) {
                if (!pkt.wandOnly) {
                    ItemStack target = player.getItemInHand(otherHand);
                    if (!target.isEmpty()) applyAll(pkt, target, seeded);
                }
                if (wandIsWand) applyAll(pkt, wand, seeded);
            } else {
                // Item-grant path: only honored when the player actually holds the wand that opens
                // the editor. Without this gate any client could request the server spawn arbitrary
                // items into their inventory.
                if (!wandIsWand && !player.hasPermissions(2)) return;
                ResourceLocation itemRl = ResourceLocation.tryParse(pkt.itemId);
                if (itemRl == null) return;
                Item item = BuiltInRegistries.ITEM.getOptional(itemRl).orElse(null);
                if (item == null) return;
                ItemStack given = new ItemStack(item);
                applyAll(pkt, given, seeded);
                player.addItem(given);
                if (wandIsWand) applyAll(pkt, wand, seeded);
            }
        });
    }

    /** Write layers + glow + custom name onto one stack (the wand copy and the target share this). */
    private static void applyAll(GlintApplyPacket pkt, ItemStack stack, CustomGlint.Layer[] seeded) {
        CustomGlint.write(stack, seeded);
        applyGlow(pkt, stack);
        applyName(pkt, stack);
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
