package net.tunamods.customglint.module.compat.artifacts;

import net.tunamods.customglint.module.compat.CompatGate;

/**
 * Standalone-only Artifacts compat (init side). The work is done by {@code ArtifactGlintMixin}
 * (@Pseudo, no-ops when Artifacts is absent) and {@code FoilBufferMixin} (a guarded no-op unless the
 * former arms it); this class only logs that the integration is live. Covers artifacts worn in Curios
 * slots (belts, necklaces, gloves, boots and the rest) which Artifacts draws itself instead of through
 * the vanilla armor layer.
 */
public final class ArtifactsCompat {
    private ArtifactsCompat() {}

    static final String MOD_ID = "artifacts";

    public static void register() {
        CompatGate.enable(MOD_ID, "Artifacts compat enabled");
    }
}
