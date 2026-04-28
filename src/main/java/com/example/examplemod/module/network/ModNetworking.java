package com.example.examplemod.module.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public class ModNetworking {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("examplemod", "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    public static void register() {
        // 0 — GlintApplyPacket  C→S  player applied or removed a custom glint from the glint wand editor
        CHANNEL.registerMessage(0, GlintApplyPacket.class, GlintApplyPacket::encode, GlintApplyPacket::decode, GlintApplyPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }
}
