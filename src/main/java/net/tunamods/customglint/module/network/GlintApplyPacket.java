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
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

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
            NetworkCodecs.writeLayers(buf, pkt.layers);
            buf.writeUtf(pkt.itemId);
            buf.writeBoolean(pkt.glowing);
            NetworkCodecs.writeColors(buf, pkt.glowColors);
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
        CustomGlint.Layer[] layers = NetworkCodecs.readLayers(buf, NetworkCodecs.MAX_TRIM_LAYERS);
        String itemId = buf.readUtf();
        boolean glowing = buf.readBoolean();
        int[] glowColors = NetworkCodecs.readCappedColors(buf);
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
                // Roll a unique seed into any unseeded chromatic layer (the editor builds layers without one).
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
                // Server-authoritative gate. This branch spawns an arbitrary registered item into the
                // player's inventory, reached only through the wand UI. The wand has no recipe (it's
                // creative/command-only), so holding one is the authorization: no game-mode check, so an
                // admin who hands themselves a wand can use it in survival too. The packet is client-sent, so
                // re-verify the player still holds the wand before spawning the item.
                if (!wandIsWand) return;
                Identifier itemRl = Identifier.tryParse(pkt.itemId);
                if (itemRl == null) return;
                Item item = BuiltInRegistries.ITEM.getOptional(itemRl).orElse(null);
                if (item == null) return;
                CustomGlint.Layer[] layers = CustomGlint.ensureChromaticSeeds(pkt.layers);
                ItemStack given = new ItemStack(item);
                CustomGlint.write(given, layers);
                applyGlow(pkt, given);
                applyName(pkt, given);
                player.addItem(given);
                CustomGlint.write(wand, layers);
                applyGlow(pkt, wand);
                applyName(pkt, wand);
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
            // trimNameColor is packed RGBA; drop the low alpha byte to the 0xRRGGBB TextColor expects.
            Component displayName = Component.literal(pkt.trimName)
                .withStyle(s -> s.withColor(TextColor.fromRgb((pkt.trimNameColor >>> 8) & 0xFFFFFF)));
            stack.set(DataComponents.CUSTOM_NAME, displayName);
        }
    }
}
