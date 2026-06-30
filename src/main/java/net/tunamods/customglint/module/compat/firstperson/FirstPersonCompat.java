package net.tunamods.customglint.module.compat.firstperson;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModList;

/**
 * Standalone-only First Person Mod compat. When FPM ({@code firstperson} by tr7zw) renders the
 * local player body in its 3.5D view, items sit at near-1P camera distance even though the display
 * context is THIRD_PERSON_*, which the shader-pack item outline path's eye-space Z push is not
 * calibrated for. {@link FirstPersonClientCompat} is where the client-side compensation is wired.
 *
 * Server-safe: gates on {@code firstperson} being loaded and routes the client wiring through
 * {@link DistExecutor} so {@link FirstPersonClientCompat} (and its client-only imports) never loads
 * on a dedicated server.
 */
public final class FirstPersonCompat {
    private FirstPersonCompat() {}

    private static final String MOD_ID = "firstperson";

    public static void register() {
        if (!ModList.get().isLoaded(MOD_ID)) return;
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> FirstPersonClientCompat::wireRenderer);
    }
}
