package net.tunamods.customglint.module.client;

import net.tunamods.customglint.module.item.ModItems;

import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CustomGlintMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TrimItemColors {
    private TrimItemColors() {}

    @SubscribeEvent
    public static void onRegisterItemColors(RegisterColorHandlersEvent.Item event) {
        // Only the white outline (layer1 / tintindex 1 - texture glow_glint_trim_edge) is recoloured; the grey
        // body (layer0 / tintindex 0) stays untinted (returns white = the identity multiply). A single multiply
        // over the whole sprite would darken the grey body toward the glow colour, which is wrong.
        // Colour source = the authoritative glow colour (the `glowColors` tag), which every path writes: the
        // Glow Trim recipe/print, and the Glint Table preview (CustomGlint.setGlowColors). Falls back to the
        // trim's own colour storage so a bare trim still tints.
        event.register((stack, tintIndex) -> {
            if (tintIndex != 1) return 0xFFFFFFFF;
            int[] glow = CustomGlint.getGlowColors(stack);
            if (glow.length == 0) glow = GlowTrimItem.getColors(stack);
            if (glow.length == 0) return 0xFFFFFFFF;
            return 0xFF000000 | (CustomGlintRenderer.computeAnimatedGlowColor(glow,
                    CustomGlint.getGlowSpeed(stack), CustomGlint.getGlowInterpolate(stack)) & 0xFFFFFF);
        }, ModItems.GLOW_TRIM.get());

        event.register((stack, tintIndex) -> {
            // The glowing trim uses the single-layer glint_trim_glow model (layer0 / tintindex 0 - the combined
            // glow_glint_trim sprite), so tint that layer, NOT tintindex 1. A two-layer body/edge model would
            // double-render the edge under the Glint Table preview (which composites its own glow overlay).
            if (tintIndex != 0) return 0xFFFFFFFF;
            if (!GlintTrimItem.isGlowing(stack)) return 0xFFFFFFFF; // non-glowing trims stay untinted
            // Explicit glow colours win; otherwise the glow is "auto" and follows layer 1's glint colours
            // (never the focused-layer editing colours, which the flat getColors would return).
            int[] glow = CustomGlint.getGlowColors(stack);
            if (glow.length == 0) glow = GlintTrimItem.getBaseLayerColors(stack);
            if (glow.length == 0) return 0xFFFFFFFF;
            return 0xFF000000 | (CustomGlintRenderer.computeAnimatedGlowColor(glow,
                    CustomGlint.getGlowSpeed(stack), CustomGlint.getGlowInterpolate(stack)) & 0xFFFFFF);
        }, ModItems.GLINT_TRIM.get());
    }
}
