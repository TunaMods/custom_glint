package net.tunamods.customglint.module.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlintWandItem;
import net.tunamods.customglint.module.item.ModItems;

import java.util.function.Supplier;

/**
 * C→S: the wand editor's "give a trim" button. Mints a Glint Trim carrying the editor's current build and
 * puts it in the sender's inventory. Holding a wand is the gate.
 */
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
        GlintApplyPacket.writeLayers(buf, pkt.layers);
        buf.writeBoolean(pkt.glowing);
        GlintApplyPacket.writeColors(buf, pkt.glowColors);
        buf.writeUtf(pkt.trimName);
        buf.writeInt(pkt.trimNameColor);
    }

    public static GiveGlintTrimPacket decode(FriendlyByteBuf buf) {
        CustomGlint.Layer[] layers = GlintApplyPacket.readLayers(buf, CustomGlint.MAX_LAYERS);
        boolean glowing = buf.readBoolean();
        int[] glowColors = GlintApplyPacket.readCappedColors(buf, CustomGlint.MAX_COLORS_PER_LAYER);
        String trimName = buf.readUtf(32767);
        int trimNameColor = buf.readInt();
        return new GiveGlintTrimPacket(layers, glowing, glowColors, trimName, trimNameColor);
    }

    public static void handle(GiveGlintTrimPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetHandlers.withSender(ctx, player -> {
            // Require the sender to actually hold a wand.
            boolean holdsWand = player.getItemInHand(InteractionHand.MAIN_HAND).getItem() instanceof GlintWandItem
                    || player.getItemInHand(InteractionHand.OFF_HAND).getItem() instanceof GlintWandItem;
            if (!holdsWand) return;

            ItemStack trim = new ItemStack(ModItems.GLINT_TRIM.get());

            if (pkt.layers.length > 0) {
                // Roll a unique seed into any unseeded chromatic layer (the editor builds layers without one).
                CustomGlint.Layer[] seeded = CustomGlint.ensureChromaticSeeds(pkt.layers);
                CustomGlint.Layer layer0 = seeded[0];
                for (int color : layer0.colors()) GlintTrimItem.addColor(trim, color);
                trim.getOrCreateTag().putFloat(GlintTrimItem.SPEED_TAG, layer0.speed());
                trim.getOrCreateTag().putFloat(GlintTrimItem.SCALE_TAG, layer0.patternScale());
                trim.getOrCreateTag().putInt(GlintTrimItem.SCROLL_TAG, layer0.scrollDir());
                trim.getOrCreateTag().putFloat(GlintTrimItem.OFFSET_TAG, layer0.scrollOffset());
                GlintTrimItem.setPattern(trim, layer0.design());
                GlintTrimItem.setGlowing(trim, pkt.glowing);

                // The GlintTrimItem setters above only seed the trim's display config; each rewrites the glint
                // Data as a single SEQUENTIAL layer. Write the real Data from the packet layers last so every
                // layer's simultaneous flag (and any extra layers) is preserved, otherwise a single-layer
                // simultaneous trim always came out sequential.
                CustomGlint.write(trim, seeded);
                CustomGlint.setGlowing(trim, pkt.glowing);
                // Keep the trim NBT seed in sync with the Data we just wrote (setPattern rolled its own seed).
                GlintTrimItem.setSeed(trim, layer0.seed());
            }

            if (!pkt.trimName.isEmpty()) {
                int rgb = (pkt.trimNameColor >>> 8) & 0xFFFFFF; // wire packs the name colour as (rgb << 8) | alpha
                trim.setHoverName(GlintTrimItem.coloredName(pkt.trimName, rgb));
            }

            player.addItem(trim);
        });
    }
}
