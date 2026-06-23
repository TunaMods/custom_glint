package net.tunamods.customglint.module.network;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class ModNetworking {

    private static final String PROTOCOL = "1";

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ModNetworking::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL);

        // GlintApplyPacket       C→S  player applied or removed a custom glint from the glint wand editor
        registrar.playToServer(GlintApplyPacket.TYPE, GlintApplyPacket.STREAM_CODEC, GlintApplyPacket::handle);
        // GlintDesignSyncPacket  S→C  syncs data-pack design names to clients on join and reload
        registrar.playToClient(GlintDesignSyncPacket.TYPE, GlintDesignSyncPacket.STREAM_CODEC, GlintDesignSyncPacket::handle);
        // GiveGlintTrimPacket  C→S  gives a GlintTrimItem with current editor settings
        registrar.playToServer(GiveGlintTrimPacket.TYPE, GiveGlintTrimPacket.STREAM_CODEC, GiveGlintTrimPacket::handle);
        // GlintStoredSyncPacket  S→C  per-player set of Glint Table "stored" design names
        registrar.playToClient(GlintStoredSyncPacket.TYPE, GlintStoredSyncPacket.STREAM_CODEC, GlintStoredSyncPacket::handle);
        // GlintPrintedSyncPacket S→C  per-player library of finished (painted) trims
        registrar.playToClient(GlintPrintedSyncPacket.TYPE, GlintPrintedSyncPacket.STREAM_CODEC, GlintPrintedSyncPacket::handle);
        // GlintPrintPacket       C→S  Glint Table "Print", consume materials/dyes, output the trim
        registrar.playToServer(GlintPrintPacket.TYPE, GlintPrintPacket.STREAM_CODEC, GlintPrintPacket::handle);
        // GlintDepositPacket     C→S  drag-in: deposit the cursor-held trim into the table's library
        registrar.playToServer(GlintDepositPacket.TYPE, GlintDepositPacket.STREAM_CODEC, GlintDepositPacket::handle);
        // GlintWithdrawPacket    C→S  shift-click: pull a printed trim out of the library into the inventory
        registrar.playToServer(GlintWithdrawPacket.TYPE, GlintWithdrawPacket.STREAM_CODEC, GlintWithdrawPacket::handle);
        // GlintGiveDesignPacket  C→S  shift-click: pull a free blank trim of a palette design into the inventory
        registrar.playToServer(GlintGiveDesignPacket.TYPE, GlintGiveDesignPacket.STREAM_CODEC, GlintGiveDesignPacket::handle);
    }
}
