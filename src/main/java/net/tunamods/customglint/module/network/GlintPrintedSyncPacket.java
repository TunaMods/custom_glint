package net.tunamods.customglint.module.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.tunamods.customglint.common.CustomGlint;

/**
 * S→C: the player's "printed" trim library — the finished, painted (colored / glow) trims shown in the Glint
 * Table's right panel. Sent when the table opens and whenever a trim is deposited. The client mirror
 * {@link #CLIENT_PRINTED} drives the right grid.
 */
public record GlintPrintedSyncPacket(List<ItemStack> trims) implements CustomPacketPayload {

    public static final Type<GlintPrintedSyncPacket> TYPE = new Type<>(CustomGlint.res("glint_printed_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GlintPrintedSyncPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeVarInt(pkt.trims.size());
                        for (ItemStack s : pkt.trims) ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, s);
                    },
                    buf -> {
                        int count = buf.readVarInt();
                        // Clamp the pre-sized capacity so a crafted server can't trigger a huge eager
                        // allocation; the loop drains the real count and underflows cleanly if it's fake.
                        List<ItemStack> trims = new ArrayList<>(Math.max(0, Math.min(count, 1024)));
                        for (int i = 0; i < count; i++) {
                            ItemStack s = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                            if (!s.isEmpty()) trims.add(s);
                        }
                        return new GlintPrintedSyncPacket(trims);
                    }
            );

    /** Client-side mirror of the local player's printed-trim library. Read by the Glint Table screen. */
    public static final List<ItemStack> CLIENT_PRINTED = new ArrayList<>();

    public static void clearClient() { CLIENT_PRINTED.clear(); }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GlintPrintedSyncPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            CLIENT_PRINTED.clear();
            CLIENT_PRINTED.addAll(pkt.trims);
        });
    }
}
