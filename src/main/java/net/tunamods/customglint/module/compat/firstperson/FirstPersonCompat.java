package net.tunamods.customglint.module.compat.firstperson;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Standalone-only First Person Mod compat. This existed to correct the previous stencil/shader
 * outline under FPM ({@code firstperson} by tr7zw): when FPM renders the local player body in its
 * 3.5D view, items sit at near-1P camera distance (~0.7 eye-space Z) while the display context is
 * still THIRD_PERSON_*, which distorted that outline's shader-pack sprite push. The outline was
 * since rebuilt as the post-process {@link net.tunamods.customglint.common.client.GlowOutlineRenderer},
 * which has no shader-pack sprite branch and skips first-person capture today, so there is nothing
 * to correct and {@link FirstPersonClientCompat#wireRenderer()} is a no-op. The reflective
 * {@code isRenderingPlayer} handle is kept for when the deferred first-person hand milestone needs it.
 *
 * Reflective binding (no compileOnly dep on FPM); silently no-ops when FPM is absent.
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
        // Client-side wiring is a no-op for the post-process outline (FirstPersonClientCompat is
        // kept out of this class's imports so dedicated servers never resolve it transitively).
        if (FMLEnvironment.dist == Dist.CLIENT) FirstPersonClientCompat.wireRenderer();
    }

    static boolean shouldSuppress() {
        try {
            return (boolean) isRenderingPlayer.invokeExact();
        } catch (Throwable t) {
            return false;
        }
    }
}
