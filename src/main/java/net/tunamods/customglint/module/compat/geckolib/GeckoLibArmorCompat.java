package net.tunamods.customglint.module.compat.geckolib;

import net.minecraftforge.fml.ModList;
import net.tunamods.customglint.CustomGlintMod;

/**
 * Standalone-only GeckoLib armor compat (init side). The actual work is done by the {@code @Pseudo}
 * {@code GeoArmorRendererMixin}, which silently no-ops when GeckoLib is absent; this class only logs
 * that the integration is live. Covers any mod whose worn armor renders through GeckoLib's
 * {@code GeoArmorRenderer} — Iron's Spells 'n Spellbooks, Ars Nouveau, and the like.
 */
public final class GeckoLibArmorCompat {
    private GeckoLibArmorCompat() {}

    static final String MOD_ID = "geckolib";

    public static void register() {
        if (!ModList.get().isLoaded(MOD_ID)) return;
        CustomGlintMod.LOGGER.info("[customglint] GeckoLib armor compat enabled");
    }
}
