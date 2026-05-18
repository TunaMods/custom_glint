package net.tunamods.customglint.module.compat.firstperson;

import net.minecraftforge.fml.ModList;
// CustomGlintRenderer import would go here when shouldSuppress is wired up. Kept out of imports
// for now so this server-safe class never resolves the renderer transitively.

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Standalone-only First Person Mod compat. When FPM (`firstperson` by tr7zw) renders the local
 * player body in its 3.5D view, parts of the player model are hidden or transformed in ways the
 * stencil-based outline pass can't track — the dilated outline geometry then renders unmasked
 * as full planes. We gate {@code CustomGlintRenderer.doModelOutline} on {@code FirstPersonAPI.isRenderingPlayer()}
 * via {@code CustomGlintRenderer.outlineSuppressor}. Item outlines are NOT suppressed — held items in
 * the 3.5D view need their Glow Trim outline to still draw.
 *
 * Reflective binding (no compileOnly dep on FPM) — silently no-ops when FPM is absent.
 */
public final class FirstPersonCompat {
    private FirstPersonCompat() {}

    private static final String MOD_ID = "firstperson";
    private static final String API_CLASS = "dev.tr7zw.firstperson.api.FirstPersonAPI";

    private static MethodHandle isRenderingPlayer;

    public static void register() {
        if (!ModList.get().isLoaded(MOD_ID)) return;
        try {
            Class<?> api = Class.forName(API_CLASS);
            isRenderingPlayer = MethodHandles.lookup().findStatic(
                    api, "isRenderingPlayer", MethodType.methodType(boolean.class));
        } catch (ReflectiveOperationException e) {
            return;
        }
        // Temporarily disabled to inspect current outline behavior under FPM 3.5D.
        // CustomGlintRenderer.outlineSuppressor = FirstPersonCompat::shouldSuppress;
    }

    private static boolean shouldSuppress() {
        try {
            return (boolean) isRenderingPlayer.invokeExact();
        } catch (Throwable t) {
            return false;
        }
    }
}
