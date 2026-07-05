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
        // GlintMountArmorSyncPacket  S→C  syncs IaF hippogryph/hippocampus armor ItemStack to clients (compat — only sent by IaF compat code)
        registrar.playToClient(GlintMountArmorSyncPacket.TYPE, GlintMountArmorSyncPacket.STREAM_CODEC, GlintMountArmorSyncPacket::handle);
        // GiveGlintTrimPacket  C→S  gives a GlintTrimItem with current editor settings
        registrar.playToServer(GiveGlintTrimPacket.TYPE, GiveGlintTrimPacket.STREAM_CODEC, GiveGlintTrimPacket::handle);

        // ── Glint Table ──────────────────────────────────────────────────────
        // GlintPrintPacket        C→S  the table's Print button builds + outputs a finished trim
        registrar.playToServer(GlintPrintPacket.TYPE, GlintPrintPacket.STREAM_CODEC, GlintPrintPacket::handle);
        // GlintGiveDesignPacket   C→S  shift-click a palette design → free blank trim
        registrar.playToServer(GlintGiveDesignPacket.TYPE, GlintGiveDesignPacket.STREAM_CODEC, GlintGiveDesignPacket::handle);
        // GlintDepositPacket      C→S  drop a cursor-held trim onto a table grid → library
        registrar.playToServer(GlintDepositPacket.TYPE, GlintDepositPacket.STREAM_CODEC, GlintDepositPacket::handle);
        // GlintWithdrawPacket     C→S  shift-click a printed trim → pull into inventory
        registrar.playToServer(GlintWithdrawPacket.TYPE, GlintWithdrawPacket.STREAM_CODEC, GlintWithdrawPacket::handle);
        // GlintStoredSyncPacket   S→C  per-player stored design set (drives grid ghosting)
        registrar.playToClient(GlintStoredSyncPacket.TYPE, GlintStoredSyncPacket.STREAM_CODEC, GlintStoredSyncPacket::handle);
        // GlintPrintedSyncPacket  S→C  per-player printed-trim library (drives the right grid)
        registrar.playToClient(GlintPrintedSyncPacket.TYPE, GlintPrintedSyncPacket.STREAM_CODEC, GlintPrintedSyncPacket::handle);
        // GlintDeletePrintedPacket C→S shift-click a still-locked imported trim → remove it from the library
        registrar.playToServer(GlintDeletePrintedPacket.TYPE, GlintDeletePrintedPacket.STREAM_CODEC, GlintDeletePrintedPacket::handle);
        // GlintImportPacket       C→S  Import list: add a config trim to the library as a locked build target
        registrar.playToServer(GlintImportPacket.TYPE, GlintImportPacket.STREAM_CODEC, GlintImportPacket::handle);

        // ── Shared blueprints (server-curated + wand Save Design) ─────────────
        // GlintServerBlueprintsSyncPacket S→C  the dedicated server's shared blueprint trims, pushed on open
        registrar.playToClient(GlintServerBlueprintsSyncPacket.TYPE, GlintServerBlueprintsSyncPacket.STREAM_CODEC, GlintServerBlueprintsSyncPacket::handle);
        // GlintDeleteServerBlueprintPacket C→S  op-only: delete one shared blueprint
        registrar.playToServer(GlintDeleteServerBlueprintPacket.TYPE, GlintDeleteServerBlueprintPacket.STREAM_CODEC, GlintDeleteServerBlueprintPacket::handle);
        // GlintWandSaveBlueprintPacket    C→S  wand "Save Design": save the build to the shared blueprint pool
        registrar.playToServer(GlintWandSaveBlueprintPacket.TYPE, GlintWandSaveBlueprintPacket.STREAM_CODEC, GlintWandSaveBlueprintPacket::handle);
        // GlintWandDeleteBlueprintPacket  C→S  wand Import trash icon: delete a shared blueprint
        registrar.playToServer(GlintWandDeleteBlueprintPacket.TYPE, GlintWandDeleteBlueprintPacket.STREAM_CODEC, GlintWandDeleteBlueprintPacket::handle);
        // GlintWandRequestBlueprintsPacket C→S  wand Import open: request the current shared blueprint pool
        registrar.playToServer(GlintWandRequestBlueprintsPacket.TYPE, GlintWandRequestBlueprintsPacket.STREAM_CODEC, GlintWandRequestBlueprintsPacket::handle);
    }
}
