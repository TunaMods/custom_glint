package net.tunamods.customglint.module.compat.epicknights;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * Standalone-only Epic Knights (magistuarmory) compat (init side). EK armor decorations (capes,
 * tabards, trims) render through their own layer beyond the base armor mixin's coverage; the glint for
 * those is drawn by {@code ArmorDecorationLayerMixin} using the stencil-masked decoration render types
 * in {@code EpicKnightsGlintRT}.
 *
 * Client-only wiring is isolated in {@link EpicKnightsClientCompat} (it registers EpicKnightsGlintRT's
 * cache release on resource reload) so a dedicated server never resolves {@code CustomGlintRenderer}
 * transitively.
 */
public final class EpicKnightsCompat {
    private EpicKnightsCompat() {}

    static final String MOD_ID = "magistuarmory";

    public static void register() {
        if (!ModList.get().isLoaded(MOD_ID)) return;
        if (FMLEnvironment.dist == Dist.CLIENT) EpicKnightsClientCompat.wire();
    }
}
