package net.tunamods.customglint.module.network;

import io.netty.handler.codec.DecoderException;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S→C: the data-pack design names the server knows about, sent on join and on every datapack reload. The
 * client merges them into {@link GlintTrimItem#PATTERNS} and remembers what it added so the next sync can
 * drop the previous server's names instead of stacking them.
 */
public class GlintDesignSyncPacket {

    /** Sanity cap on the wire count; the real list (builtins + data-pack designs) is far smaller. */
    private static final int MAX_DESIGNS = 65536;

    /** Names this client added to PATTERNS from a sync, so a later sync can remove them again. */
    private static final List<String> clientSyncedDesigns = new ArrayList<>();

    private final List<String> designs;

    public GlintDesignSyncPacket(List<String> designs) {
        this.designs = designs;
    }

    public static void encode(GlintDesignSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.designs.size());
        for (String design : pkt.designs) {
            buf.writeUtf(design);
        }
    }

    public static GlintDesignSyncPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_DESIGNS)
            throw new DecoderException("Bad design count: " + count);
        List<String> designs = new ArrayList<>(Math.min(count, 256));
        for (int i = 0; i < count; i++) {
            designs.add(buf.readUtf());
        }
        return new GlintDesignSyncPacket(designs);
    }

    public static void handle(GlintDesignSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetHandlers.work(ctx, () -> {
            GlintTrimItem.PATTERNS.removeAll(clientSyncedDesigns);
            clientSyncedDesigns.clear();
            for (String design : pkt.designs) {
                if (!GlintTrimItem.PATTERNS.contains(design)) {
                    GlintTrimItem.PATTERNS.add(design);
                    clientSyncedDesigns.add(design);
                }
            }
        });
    }
}
