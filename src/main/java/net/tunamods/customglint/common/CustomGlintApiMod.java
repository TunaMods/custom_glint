package net.tunamods.customglint.common;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.ItemFishedEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.tunamods.customglint.common.client.CustomGlintClientInit;

@Mod(CustomGlintApiMod.MOD_ID)
public class CustomGlintApiMod {
    public static final String MOD_ID = "customglint_api";

    public CustomGlintApiMod() {
        // Renderer-touching init (BEWLR outline textures, resource reload listener for the texture
        // cache) happens only on the client. Method-handle target lives in a separate class
        // (CustomGlintClientInit) so the JVM never resolves CustomGlintRenderer on a dedicated
        // server — DistExecutor.safeRunWhenOn only invokes the supplier on the matching dist.
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> CustomGlintClientInit::run);

        // Server-safe event listeners — only touch NBT/data registries on CustomGlint.
        MinecraftForge.EVENT_BUS.addListener(this::onCraft);
        MinecraftForge.EVENT_BUS.addListener(this::onFish);
        MinecraftForge.EVENT_BUS.addListener(this::onMobDrop);
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
