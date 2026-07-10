package net.tunamods.customglint.module.compat.mekanism;

import net.minecraftforge.fml.ModList;
import net.tunamods.customglint.CustomGlintMod;

/**
 * Standalone-only Mekanism compat (init side). The work is done by {@code MekanismArmorGlintMixin}
 * (@Pseudo, no-ops when Mekanism is absent) and {@code FoilBufferMixin} (a guarded no-op unless the
 * former arms it); this class only logs that the integration is live. Covers the special armor Mekanism
 * renders itself — MekaSuit, Jetpacks, Free Runners and their armored variants, Scuba tank/mask. The
 * Hazmat suit is a plain vanilla armor item and already glints through the core armor path.
 */
public final class MekanismArmorCompat {
    private MekanismArmorCompat() {}

    static final String MOD_ID = "mekanism";

    public static void register() {
        if (!ModList.get().isLoaded(MOD_ID)) return;
        CustomGlintMod.LOGGER.info("[customglint] Mekanism special-armor compat enabled");
    }
}
