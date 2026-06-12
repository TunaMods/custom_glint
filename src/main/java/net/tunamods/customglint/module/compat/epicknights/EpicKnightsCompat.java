package net.tunamods.customglint.module.compat.epicknights;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.ModList;

/**
 * Standalone-only Epic Knights compat (init side). EK chest armor models include standard
 * vanilla-layout arm cuboids (UV 40,16 size 4×12×4), and EK chest textures have fully-opaque
 * pixels across that UV region even when no visible arm armor is intended. Our outline RTs
 * use {@code RENDERTYPE_OUTLINE_SHADER} which alpha-discards only at exact 0.0, so the WRITE
 * pass stamps the full arm cuboid and the dilated TEST pass forms a ring around the arm.
 *
 * Fix: install a predicate on {@code CustomGlintRenderer.chestArmorHidesArmsInOutline} that
 * matches any {@code com.magistuarmory.*} item. {@code HumanoidArmorLayerMixin} hides arm
 * parts for the outline render call only; normal armor render and glint pass are unaffected.
 *
 * Client-only wiring is isolated in {@link EpicKnightsClientCompat} so a dedicated server
 * never resolves {@code CustomGlintRenderer} transitively.
 */
public final class EpicKnightsCompat {
    private EpicKnightsCompat() {}

    static final String MOD_ID = "magistuarmory";

    public static void register() {
        if (!ModList.get().isLoaded(MOD_ID)) return;
        if (FMLEnvironment.dist == Dist.CLIENT) EpicKnightsClientCompat.wire();
    }
}
