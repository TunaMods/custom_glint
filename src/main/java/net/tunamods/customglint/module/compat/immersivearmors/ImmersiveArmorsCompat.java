package net.tunamods.customglint.module.compat.immersivearmors;

import net.minecraftforge.fml.ModList;
import net.tunamods.customglint.CustomGlintMod;

/**
 * Standalone-only Immersive Armors compat (init side). The work is done by the {@code @Pseudo}
 * {@code ArmorPieceMixin}, which silently no-ops when Immersive Armors is absent; this class only logs
 * that the integration is live.
 *
 * Immersive Armors cancels the vanilla {@code HumanoidArmorLayer.renderArmorPiece} for its own
 * {@code ExtendedArmorItem}s and instead draws each armor slot as a list of {@code Piece}s (layered body
 * shells, deco models). That bypasses our core {@code HumanoidArmorLayerMixin}, so the worn armor never
 * picks up a custom glint or glow. {@code ArmorPieceMixin} re-hooks the piece renderer instead.
 */
public final class ImmersiveArmorsCompat {
    private ImmersiveArmorsCompat() {}

    static final String MOD_ID = "immersive_armors";

    public static void register() {
        if (!ModList.get().isLoaded(MOD_ID)) return;
        CustomGlintMod.LOGGER.info("[customglint] Immersive Armors compat enabled");
    }
}
