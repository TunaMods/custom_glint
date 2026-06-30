package net.tunamods.customglint.module.network;

import net.tunamods.customglint.common.CustomGlint;

import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public class ModNetworking {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            CustomGlint.res("main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    public static void register() {
        // 0 — GlintApplyPacket       C→S  player applied or removed a custom glint from the glint wand editor
        CHANNEL.registerMessage(0, GlintApplyPacket.class, GlintApplyPacket::encode, GlintApplyPacket::decode, GlintApplyPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        // 1 — GlintDesignSyncPacket  S→C  syncs data-pack design names to clients on join and reload
        CHANNEL.registerMessage(1, GlintDesignSyncPacket.class, GlintDesignSyncPacket::encode, GlintDesignSyncPacket::decode, GlintDesignSyncPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        // 2 — GlintMountArmorSyncPacket  S→C  syncs IaF hippogryph/hippocampus armor ItemStack to clients (compat — only sent by IaF compat code)
        CHANNEL.registerMessage(2, GlintMountArmorSyncPacket.class, GlintMountArmorSyncPacket::encode, GlintMountArmorSyncPacket::decode, GlintMountArmorSyncPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        // 3 — GiveGlintTrimPacket  C→S  gives a GlintTrimItem with current editor settings
        CHANNEL.registerMessage(3, GiveGlintTrimPacket.class, GiveGlintTrimPacket::encode, GiveGlintTrimPacket::decode, GiveGlintTrimPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        // 4 — (reserved) was GlintEntitySyncPacket, moved to ApiNetworking under customglint_api:main
        // 5 — GlintPrintPacket        C→S  Glint Table "Print" button: build + validate + output a trim
        CHANNEL.registerMessage(5, GlintPrintPacket.class, GlintPrintPacket::encode, GlintPrintPacket::decode, GlintPrintPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        // 6 — GlintDepositPacket      C→S  drop a cursor-held trim into the table's library
        CHANNEL.registerMessage(6, GlintDepositPacket.class, GlintDepositPacket::encode, GlintDepositPacket::decode, GlintDepositPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        // 7 — GlintWithdrawPacket     C→S  pull a printed trim out of the library
        CHANNEL.registerMessage(7, GlintWithdrawPacket.class, GlintWithdrawPacket::encode, GlintWithdrawPacket::decode, GlintWithdrawPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        // 8 — GlintGiveDesignPacket   C→S  hand the player a free blank trim of a palette design
        CHANNEL.registerMessage(8, GlintGiveDesignPacket.class, GlintGiveDesignPacket::encode, GlintGiveDesignPacket::decode, GlintGiveDesignPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        // 9 — GlintStoredSyncPacket   S→C  syncs the player's stored-design set to the client
        CHANNEL.registerMessage(9, GlintStoredSyncPacket.class, GlintStoredSyncPacket::encode, GlintStoredSyncPacket::decode, GlintStoredSyncPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        // 10 — GlintPrintedSyncPacket S→C  syncs the player's printed-trim library to the client
        CHANNEL.registerMessage(10, GlintPrintedSyncPacket.class, GlintPrintedSyncPacket::encode, GlintPrintedSyncPacket::decode, GlintPrintedSyncPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
}
