package net.tunamods.customglint.module.network;

import net.tunamods.customglint.common.CustomGlint;
import net.minecraft.network.FriendlyByteBuf;
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

    public GlintApplyPacket(InteractionHand wandHand, boolean remove, CustomGlint.Layer[] layers, String itemId) {
        this.wandHand = wandHand;
        this.remove = remove;
        this.layers = layers;
        this.itemId = itemId;
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
            }
            buf.writeUtf(pkt.itemId);
        }
    }

    public static GlintApplyPacket decode(FriendlyByteBuf buf) {
        InteractionHand hand = buf.readEnum(InteractionHand.class);
        boolean remove = buf.readBoolean();
        if (remove) return new GlintApplyPacket(hand, true, new CustomGlint.Layer[0], "");
        int layerCount = Math.min(buf.readVarInt(), 8);
        CustomGlint.Layer[] layers = new CustomGlint.Layer[layerCount];
        for (int i = 0; i < layerCount; i++) {
            String design = buf.readUtf();
            int colorLen = Math.min(buf.readVarInt(), 8);
            int[] colors = new int[colorLen];
            for (int j = 0; j < colorLen; j++) colors[j] = buf.readInt();
            float speed = buf.readFloat();
            boolean interp = buf.readBoolean();
            float scale = buf.readFloat();
            boolean simultaneous = buf.readBoolean();
            layers[i] = new CustomGlint.Layer(new ResourceLocation(design), colors, speed, interp, scale, simultaneous);
        }
        String itemId = buf.readUtf();
        return new GlintApplyPacket(hand, false, layers, itemId);
    }

    public static void handle(GlintApplyPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            InteractionHand otherHand = pkt.wandHand == InteractionHand.MAIN_HAND
                    ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            if (pkt.remove) {
                ItemStack target = player.getItemInHand(otherHand);
                if (!target.isEmpty()) CustomGlint.remove(target);
            } else if (pkt.itemId.isEmpty()) {
                ItemStack target = player.getItemInHand(otherHand);
                if (!target.isEmpty()) CustomGlint.write(target, pkt.layers);
            } else {
                Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(pkt.itemId));
                if (item == null) return;
                ItemStack given = new ItemStack(item);
                CustomGlint.write(given, pkt.layers);
                player.addItem(given);
                ItemStack wand = player.getItemInHand(pkt.wandHand);
                if (!wand.isEmpty()) CustomGlint.write(wand, pkt.layers);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
