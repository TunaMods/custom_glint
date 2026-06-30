package net.tunamods.customglint.module.compat.epicknights;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModList;

/**
 * Standalone-only Epic Knights compat (init side). Soft-dep gated on {@code magistuarmory}; routes
 * client wiring through {@link EpicKnightsClientCompat} so a dedicated server never resolves the
 * client renderer transitively. EK decoration glint (capes, tabards, crowns) is drawn by
 * {@link EpicKnightsGlintRT} via {@code ArmorDecorationLayerMixin}; the client wiring registers that
 * class's logout cache cleanup.
 */
public final class EpicKnightsCompat {
    private EpicKnightsCompat() {}

    static final String MOD_ID = "magistuarmory";

    public static void register() {
        if (!ModList.get().isLoaded(MOD_ID)) return;
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> EpicKnightsClientCompat::wire);
    }
}
