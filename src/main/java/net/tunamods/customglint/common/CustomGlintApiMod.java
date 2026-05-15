// MIT License — Copyright (c) 2026 Likely Tuna | TunaMods — see LICENSE.txt
package net.tunamods.customglint.common;

import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.ItemFishedEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(CustomGlintApiMod.MOD_ID)
public class CustomGlintApiMod {
    public static final String MOD_ID = "customglint_api";

    public CustomGlintApiMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::onRegisterClientReloadListeners);

        MinecraftForge.EVENT_BUS.addListener(this::onCraft);
        MinecraftForge.EVENT_BUS.addListener(this::onFish);
        MinecraftForge.EVENT_BUS.addListener(this::onMobDrop);
    }

    private void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> CustomGlint.clearTextures());
    }

    private void onCraft(PlayerEvent.ItemCraftedEvent event) {
        CustomGlint.applyCraftGlint(event.getCrafting());
    }

    private void onFish(ItemFishedEvent event) {
        event.getDrops().forEach(CustomGlint::applyFishingGlint);
    }

    private void onMobDrop(LivingDropsEvent event) {
        event.getDrops().forEach(entity -> CustomGlint.applyMobDropGlint(entity.getItem()));
    }
}
