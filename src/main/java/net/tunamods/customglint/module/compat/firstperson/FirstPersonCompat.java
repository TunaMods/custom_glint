package net.tunamods.customglint.module.compat.firstperson;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.ModList;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Standalone-only First Person Mod compat. When FPM ({@code firstperson} by tr7zw) renders the
 * local player body in its 3.5D view, items are at near-1P camera distance (~0.7 eye-space Z)
 * even though the display context is THIRD_PERSON_*. The shader-pack item outline path's eye-space
 * Z push ({@code dz = 0.03}) is calibrated for vanilla 3P distance (~3.0 Z); at FPM's close range
 * the same value causes ~4× more perspective shrinkage in XY — the 4 translated sprite copies
 * drift toward screen-center and shift as the camera rotates. {@link FirstPersonClientCompat}
 * installs a {@code fpmRenderingPlayerGate} on {@code CustomGlintRenderer} that causes the
 * shader-pack path to use {@code dz = 0.0} when FPM is rendering the player.
 *
 * Reflective binding (no compileOnly dep on FPM) — silently no-ops when FPM is absent.
 */
public final class FirstPersonCompat {
    private FirstPersonCompat() {}

    private static final String MOD_ID = "firstperson";
    private static final String API_CLASS = "dev.tr7zw.firstperson.api.FirstPersonAPI";

    static MethodHandle isRenderingPlayer;

    public static void register() {
        if (!ModList.get().isLoaded(MOD_ID)) return;
        try {
            Class<?> api = Class.forName(API_CLASS);
            isRenderingPlayer = MethodHandles.lookup().findStatic(
                    api, "isRenderingPlayer", MethodType.methodType(boolean.class));
        } catch (ReflectiveOperationException e) {
            return;
        }
        // Wire fpmRenderingPlayerGate on client side (CustomGlintRenderer is client-only —
        // kept out of this class's imports so dedicated servers never resolve it transitively).
        if (FMLEnvironment.getDist() == Dist.CLIENT) FirstPersonClientCompat.wireRenderer();
        // Temporarily disabled to inspect current outline behavior under FPM 3.5D.
        // CustomGlintRenderer.outlineSuppressor = FirstPersonCompat::shouldSuppress;
    }

    static boolean shouldSuppress() {
        try {
            return (boolean) isRenderingPlayer.invokeExact();
        } catch (Throwable t) {
            return false;
        }
    }
}
