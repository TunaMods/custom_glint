package net.tunamods.customglint.common;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.tunamods.customglint.common.client.CustomGlintClientInit;
import net.tunamods.customglint.common.client.EntityGlintClientInit;
import net.tunamods.customglint.common.entity.EntityGlintEvents;
import net.tunamods.customglint.common.network.ApiNetworking;

@Mod(CustomGlintApiMod.MOD_ID)
public class CustomGlintApiMod {
    public static final String MOD_ID = "customglint_api";

    public CustomGlintApiMod(IEventBus modEventBus) {
        // Renderer-touching init (BEWLR outline textures, resource reload listener for the texture
        // cache) happens only on the client. The client classes are referenced solely inside the
        // dist guard, so the JVM never resolves CustomGlintRenderer on a dedicated server — the
        // guarded branch (and therefore the class load) only runs on the matching dist.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            CustomGlintClientInit.run(modEventBus);
            EntityGlintClientInit.run();
        }

        // API-owned network channel + per-instance entity glint sync. Lives here (not in the
        // full jar's ModNetworking) so mods that bundle only the api jar still get
        // server↔client sync for entity glints with no extra wiring on their side.
        ApiNetworking.register(modEventBus);
        NeoForge.EVENT_BUS.register(EntityGlintEvents.class);

        // Server-safe event listeners — only touch NBT/data registries on CustomGlint.
        NeoForge.EVENT_BUS.addListener(this::onCraft);
        NeoForge.EVENT_BUS.addListener(this::onFish);
        NeoForge.EVENT_BUS.addListener(this::onMobDrop);
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
