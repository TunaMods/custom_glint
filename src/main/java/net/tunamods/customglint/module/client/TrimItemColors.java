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

@EventBusSubscriber(modid = CustomGlintMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TrimItemColors {
    private TrimItemColors() {}

    @SubscribeEvent
    public static void onRegisterItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> {
            if (tintIndex != 0) return 0xFFFFFFFF;
            // The authoritative glow colours (the glowColors component) win — the Glint Table live preview and
            // the print/recipe both write them via CustomGlint.setGlowColors, where the trim's own colours tag
            // isn't set (so reading only that tag left the preview edge untinted white). Fall back to the trim's
            // own storage so a bare Glow Trim item still tints. Animate on WALL-CLOCK so the tinted edge desyncs
            // from the game-time glow outline (different colours at once), honouring the trim's glow speed/interp.
            int[] colors = CustomGlint.getGlowColors(stack);
            if (colors.length == 0) colors = GlowTrimItem.getColors(stack);
            if (colors.length == 0) return 0xFFFFFFFF;
            return 0xFF000000 | (CustomGlintRenderer.computeAnimatedGlowColorGui(colors,
                    CustomGlint.getGlowSpeed(stack), CustomGlint.getGlowInterpolate(stack)) & 0xFFFFFF);
        }, ModItems.GLOW_TRIM.get());

        event.register((stack, tintIndex) -> {
            if (tintIndex != 0) return 0xFFFFFFFF;
            if (!GlintTrimItem.isGlowing(stack)) return 0xFFFFFFFF;
            // Resolve the glow tint like the outline (explicit glow colours, else glint layer 0), but on the
            // WALL-CLOCK variant so the tinted edge desyncs from the game-time glow outline — the edge and the
            // ring show different colours at once.
            return 0xFF000000 | (CustomGlintRenderer.resolveGlowColorGui(stack) & 0xFFFFFF);
        }, ModItems.GLINT_TRIM.get());
    }
}
