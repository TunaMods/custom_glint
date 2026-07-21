package net.tunamods.customglint.common.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.tunamods.customglint.common.CustomGlintApiMod;

import java.util.Optional;

/**
 * API-owned network channel. Lives in {@code common/} so the channel and its packets ship in the api jar:
 * mods that depend only on {@code customglint_api} (via jarJar) get per-instance entity glint sync without
 * the full standalone jar in the mods folder.
 *
 * <p>Kept separate from the full jar's {@code customglint:main} channel so the two protocols version
 * independently.
 */
public final class ApiNetworking {
    private ApiNetworking() {}

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CustomGlintApiMod.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    public static void register() {
        // 0: GlintEntitySyncPacket  S→C  per-instance entity glint NBT for LivingEntities
        CHANNEL.registerMessage(0, GlintEntitySyncPacket.class,
                GlintEntitySyncPacket::encode, GlintEntitySyncPacket::decode, GlintEntitySyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
}
