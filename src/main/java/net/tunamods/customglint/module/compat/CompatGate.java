package net.tunamods.customglint.module.compat;

import net.minecraftforge.fml.ModList;
import net.tunamods.customglint.CustomGlintMod;

/** Shared init-side gate for the soft-dep compat modules: check the target mod is present, log if so. */
public final class CompatGate {
    private CompatGate() {}

    /** @return true when {@code modId} is loaded (compat active); logs {@code message} in that case. */
    public static boolean enable(String modId, String message) {
        if (!ModList.get().isLoaded(modId)) return false;
        CustomGlintMod.LOGGER.info("[customglint] {}", message);
        return true;
    }
}
