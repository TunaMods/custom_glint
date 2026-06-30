package net.tunamods.customglint.module.network;

import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class GlintDesignSyncPacket {

    private static final List<String> clientSyncedDesigns = new ArrayList<>();

    private final List<String> designs;

    public GlintDesignSyncPacket(List<String> designs) {
        this.designs = designs;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(designs.size());
        for (String design : designs) {
            buf.writeUtf(design);
        }
    }

    /** Generous upper bound on design names on the wire — guards a malformed/hostile server from driving a
     *  negative-capacity allocation or an unbounded read loop. The real list is the builtin set plus data-pack
     *  additions, far under this. */
    private static final int MAX_DESIGNS = 65536;

    public static GlintDesignSyncPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_DESIGNS)
            throw new io.netty.handler.codec.DecoderException("Bad design count: " + count);
        List<String> designs = new ArrayList<>(Math.min(count, 256));
        for (int i = 0; i < count; i++) {
            designs.add(buf.readUtf());
        }
        return new GlintDesignSyncPacket(designs);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            GlintTrimItem.PATTERNS.removeAll(clientSyncedDesigns);
            clientSyncedDesigns.clear();
            for (String design : designs) {
                if (!GlintTrimItem.PATTERNS.contains(design)) {
                    GlintTrimItem.PATTERNS.add(design);
                    clientSyncedDesigns.add(design);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
