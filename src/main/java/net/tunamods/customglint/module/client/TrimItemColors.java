package net.tunamods.customglint.module.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;
import net.tunamods.customglint.module.item.ModItems;

/**
 * Item colour handlers for the two trim items. Both tint only tintIndex 0 (the glowing edge layer of the
 * model) and leave every other tint index white, so the trim's base art is untouched. Client-only,
 * standalone module code.
 */
@EventBusSubscriber(modid = CustomGlintMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TrimItemColors {
    private TrimItemColors() {}

    @SubscribeEvent
    public static void onRegisterItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> {
            if (tintIndex != 0) return 0xFFFFFFFF;
            // The authoritative glow colours (the glowColors component) win: the Glint Table live preview and
            // the print/recipe both write them via CustomGlint.setGlowColors, where the trim's own colours tag
            // isn't set (so reading only that tag left the preview edge untinted white). Fall back to the trim's
            // own storage so a bare Glow Trim item still tints. Animate on the same GAME clock as the outline
            // ring but at phase 0 (the ring runs at GLOW_RING_PHASE_OFFSET), so the tinted edge sits a stable
            // half-step behind the ring (edge red while the ring is blue and back) instead of drifting.
            int[] colors = CustomGlint.getGlowColors(stack);
            if (colors.length == 0) colors = GlowTrimItem.getColors(stack);
            if (colors.length == 0) return 0xFFFFFFFF;
            return 0xFF000000 | (CustomGlintRenderer.computeAnimatedGlowColor(colors,
                    CustomGlint.getGlowSpeed(stack), CustomGlint.getGlowInterpolate(stack)) & 0xFFFFFF);
        }, ModItems.GLOW_TRIM.get());

        event.register((stack, tintIndex) -> {
            if (tintIndex != 0) return 0xFFFFFFFF;
            if (!GlintTrimItem.isGlowing(stack)) return 0xFFFFFFFF;
            // Resolve the glow tint like the outline (explicit glow colours, else glint layer 0) but at phase 0,
            // so the tinted edge sits a stable half-step (GLOW_RING_PHASE_OFFSET) behind the ring: same clock per
            // branch as the ring, so the two show different colours at once without drifting.
            return 0xFF000000 | (CustomGlintRenderer.resolveGlowColorTint(stack) & 0xFFFFFF);
        }, ModItems.GLINT_TRIM.get());
    }
}
