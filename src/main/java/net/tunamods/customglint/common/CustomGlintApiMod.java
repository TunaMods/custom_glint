package net.tunamods.customglint.common;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.tunamods.customglint.common.client.CustomGlintClientInit;

@Mod(CustomGlintApiMod.MOD_ID)
public class CustomGlintApiMod {
    public static final String MOD_ID = "customglint_api";

    public CustomGlintApiMod(IEventBus modEventBus, ModContainer modContainer) {
        // Item glint = a typed data component; entity glint = a synced AttachmentType. Both live in
        // CustomGlintComponents (api jar) and are registered here, so mods that bundle only the api jar
        // get the component + auto-synced entity glint with no extra wiring.
        CustomGlintComponents.register(modEventBus);

        // Renderer-touching init (resource reload listener for the texture cache, the CLIENT rendering
        // config + screen, render-state modifiers) happens only on the client. The client classes are
        // referenced solely inside the dist guard, so the JVM never resolves CustomGlintRenderer or the
        // config screen on a dedicated server — the guarded branch only runs on the matching dist.
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            CustomGlintClientInit.run(modEventBus, modContainer);
        }

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
