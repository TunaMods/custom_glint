package net.tunamods.customglint.module.network;

import net.tunamods.customglint.module.item.ModItems;

import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlintWandItem;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class GiveGlintTrimPacket {

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

    public static void encode(GiveGlintTrimPacket pkt, FriendlyByteBuf buf) {
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
        buf.writeBoolean(pkt.glowing);
        buf.writeVarInt(pkt.glowColors.length);
        for (int c : pkt.glowColors) buf.writeInt(c);
        buf.writeUtf(pkt.trimName);
        buf.writeInt(pkt.trimNameColor);
    }

    public static GiveGlintTrimPacket decode(FriendlyByteBuf buf) {
        CustomGlint.Layer[] layers = GlintApplyPacket.readLayers(buf, 8);
        boolean glowing = buf.readBoolean();
        int[] glowColors = GlintApplyPacket.readCappedColors(buf, 8);
        String trimName = buf.readUtf(32767);
        int trimNameColor = buf.readInt();
        return new GiveGlintTrimPacket(layers, glowing, glowColors, trimName, trimNameColor);
    }

    public static void handle(GiveGlintTrimPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            // Only reachable from the wand editor (opened by right-clicking the wand). Require the sender to
            // hold one so a forged packet can't mint free trims.
            boolean holdsWand = player.getItemInHand(InteractionHand.MAIN_HAND).getItem() instanceof GlintWandItem
                    || player.getItemInHand(InteractionHand.OFF_HAND).getItem() instanceof GlintWandItem;
            if (!holdsWand) return;

            ItemStack trim = new ItemStack(ModItems.GLINT_TRIM.get());

            if (pkt.layers.length > 0) {
                CustomGlint.Layer layer0 = pkt.layers[0];
                for (int color : layer0.colors()) GlintTrimItem.addColor(trim, color);
                trim.getOrCreateTag().putFloat(GlintTrimItem.SPEED_TAG, layer0.speed());
                trim.getOrCreateTag().putFloat(GlintTrimItem.SCALE_TAG, layer0.patternScale());
                trim.getOrCreateTag().putInt(GlintTrimItem.SCROLL_TAG, layer0.scrollDir());
                trim.getOrCreateTag().putFloat(GlintTrimItem.OFFSET_TAG, layer0.scrollOffset());
                GlintTrimItem.setPattern(trim, layer0.design());
                GlintTrimItem.setGlowing(trim, pkt.glowing);
                CustomGlint.setGlowing(trim, pkt.glowing);

                if (pkt.layers.length > 1) {
                    if (trim.hasTag() && trim.getTag().contains(CustomGlintMod.MOD_ID)) {
                        trim.getTag().remove(CustomGlintMod.MOD_ID);
                    }
                    CustomGlint.write(trim, CustomGlint.ensureChromaticSeeds(pkt.layers));
                    CustomGlint.setGlowing(trim, pkt.glowing);
                }
            }

            // Apply custom name and color if provided
            if (!pkt.trimName.isEmpty()) {
                Component displayName = Component.literal(pkt.trimName)
                    .withStyle(s -> s.withColor(TextColor.fromRgb((pkt.trimNameColor >>> 8) & 0xFFFFFF)));
                trim.setHoverName(displayName);
            }

            player.addItem(trim);
        });
        ctx.get().setPacketHandled(true);
    }
}
