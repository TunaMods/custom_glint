package net.tunamods.customglint.common.network;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * API-owned network channel. Lives in {@code common/} so the channel and its packets ship in the
 * api jar — mods that depend only on {@code customglint_api} (via jarJar) still get
 * per-instance entity glint sync without needing the full standalone jar in the mods folder.
 *
 * Distinct from the full jar's {@code customglint:main} channel so the two protocols version
 * independently.
 */
public final class ApiNetworking {
    private ApiNetworking() {}

    private static final String PROTOCOL = "1";

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ApiNetworking::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL);
        // GlintEntitySyncPacket  S→C  per-instance entity glint NBT for LivingEntities
        registrar.playToClient(GlintEntitySyncPacket.TYPE, GlintEntitySyncPacket.STREAM_CODEC, GlintEntitySyncPacket::handle);
    }
}
