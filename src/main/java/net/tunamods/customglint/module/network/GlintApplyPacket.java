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
    public final String design;
    public final int[] colors;
    public final float speed;
    public final boolean interpolate;
    public final float patternScale;
    public final boolean simultaneous;
    public final String itemId;

    public GlintApplyPacket(InteractionHand wandHand, boolean remove,
                             String design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous, String itemId) {
        this.wandHand = wandHand;
        this.remove = remove;
        this.design = design;
        this.colors = colors;
        this.speed = speed;
        this.interpolate = interpolate;
        this.patternScale = patternScale;
        this.simultaneous = simultaneous;
        this.itemId = itemId;
    }

    public static void encode(GlintApplyPacket pkt, FriendlyByteBuf buf) {
        buf.writeEnum(pkt.wandHand);
        buf.writeBoolean(pkt.remove);
        if (!pkt.remove) {
            buf.writeUtf(pkt.design);
            buf.writeVarInt(pkt.colors.length);
            for (int c : pkt.colors) buf.writeInt(c);
            buf.writeFloat(pkt.speed);
            buf.writeBoolean(pkt.interpolate);
            buf.writeFloat(pkt.patternScale);
            buf.writeBoolean(pkt.simultaneous);
            buf.writeUtf(pkt.itemId);
        }
    }

    public static GlintApplyPacket decode(FriendlyByteBuf buf) {
        InteractionHand hand = buf.readEnum(InteractionHand.class);
        boolean remove = buf.readBoolean();
        if (remove) return new GlintApplyPacket(hand, true, "", new int[0], 1.0f, true, 1.0f, true, "");
        String design = buf.readUtf();
        int len = Math.min(buf.readVarInt(), 8);
        int[] colors = new int[len];
        for (int i = 0; i < len; i++) colors[i] = buf.readInt();
        float speed = buf.readFloat();
        boolean interp = buf.readBoolean();
        float patternScale = buf.readFloat();
        boolean simultaneous = buf.readBoolean();
        String itemId = buf.readUtf();
        return new GlintApplyPacket(hand, false, design, colors, speed, interp, patternScale, simultaneous, itemId);
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
                if (!target.isEmpty()) {
                    CustomGlint.write(target, new ResourceLocation(pkt.design), pkt.colors, pkt.speed, pkt.interpolate, pkt.patternScale, pkt.simultaneous);
                }
            } else {
                Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(pkt.itemId));
                if (item == null) return;
                ResourceLocation designRL = new ResourceLocation(pkt.design);
                ItemStack given = new ItemStack(item);
                CustomGlint.write(given, designRL, pkt.colors, pkt.speed, pkt.interpolate, pkt.patternScale, pkt.simultaneous);
                player.addItem(given);
                ItemStack wand = player.getItemInHand(pkt.wandHand);
                if (!wand.isEmpty()) CustomGlint.write(wand, designRL, pkt.colors, pkt.speed, pkt.interpolate, pkt.patternScale, pkt.simultaneous);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
