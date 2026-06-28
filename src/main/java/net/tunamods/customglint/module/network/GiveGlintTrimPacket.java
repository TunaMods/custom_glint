package net.tunamods.customglint.module.network;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlintWandItem;
import net.tunamods.customglint.module.item.ModItems;

public class GiveGlintTrimPacket implements CustomPacketPayload {

    public static final Type<GiveGlintTrimPacket> TYPE =
            new Type<>(CustomGlint.res("give_glint_trim"));

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
        GlintApplyPacket.writeLayers(buf, pkt.layers);
        buf.writeBoolean(pkt.glowing);
        buf.writeVarInt(pkt.glowColors.length);
        for (int c : pkt.glowColors) buf.writeInt(c);
        buf.writeUtf(pkt.trimName);
        buf.writeInt(pkt.trimNameColor);
    }

    public static GiveGlintTrimPacket decode(FriendlyByteBuf buf) {
        // Shared, bounds-checked decoders: a crafted packet can't throw NegativeArraySizeException or
        // desync the trailing fields on the network thread.
        CustomGlint.Layer[] layers = GlintApplyPacket.readLayers(buf, 8);
        boolean glowing = buf.readBoolean();
        int[] glowColors = GlintApplyPacket.readCappedColors(buf);
        String trimName = buf.readUtf(32767);
        int trimNameColor = buf.readInt();
        return new GiveGlintTrimPacket(layers, glowing, glowColors, trimName, trimNameColor);
    }

    public static void handle(GiveGlintTrimPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            // Only honored when the player actually holds the wand that opens the editor (or is an op).
            // Without this gate any client could request the server spawn free Glint Trims into their inventory.
            boolean wandIsWand = player.getMainHandItem().getItem() instanceof GlintWandItem
                    || player.getOffhandItem().getItem() instanceof GlintWandItem;
            if (!wandIsWand && !player.hasPermissions(2)) return;

            ItemStack trim = new ItemStack(ModItems.GLINT_TRIM.get());

            // Roll a stable oil-slick seed into any unseeded chromatic layer once, so the granted trim keeps
            // one pattern (the editor sends unseeded layers).
            CustomGlint.Layer[] seeded = CustomGlint.ensureChromaticSeeds(pkt.layers);
            if (seeded.length > 0) {
                CustomGlint.Layer layer0 = seeded[0];
                for (int color : layer0.colors()) GlintTrimItem.addColor(trim, color);
                GlintTrimItem.setSpeed(trim, layer0.speed());
                GlintTrimItem.setScale(trim, layer0.patternScale());
                GlintTrimItem.setScrollDir(trim, layer0.scrollDir());
                GlintTrimItem.setScrollOffset(trim, layer0.scrollOffset());
                GlintTrimItem.setPattern(trim, layer0.design());
                GlintTrimItem.setGlowing(trim, pkt.glowing);
                CustomGlint.setGlowing(trim, pkt.glowing);

                CustomGlint.remove(trim);
                CustomGlint.write(trim, seeded);
                CustomGlint.setGlowing(trim, pkt.glowing);
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
