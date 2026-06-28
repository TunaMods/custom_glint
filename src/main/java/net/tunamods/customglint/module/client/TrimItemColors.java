package net.tunamods.customglint.module.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.tunamods.customglint.CustomGlintMod;
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
            int[] colors = GlowTrimItem.getColors(stack);
            if (colors.length == 0) return 0xFFFFFFFF;
            return 0xFF000000 | (CustomGlintRenderer.computeAnimatedGlowColor(colors) & 0xFFFFFF);
        }, ModItems.GLOW_TRIM.get());

        event.register((stack, tintIndex) -> {
            if (tintIndex != 0) return 0xFFFFFFFF;
            if (!GlintTrimItem.isGlowing(stack)) return 0xFFFFFFFF;
            // Resolve the glow tint the same way the in-world outline does (explicit glow colours,
            // else glint layer 0 — the main layer). The flat trim `colors` tag would otherwise track
            // whichever layer shard is active in the Glint Table, so a multi-layer preview's glow
            // would shift with the selected shard instead of staying on the main layer.
            return 0xFF000000 | (CustomGlintRenderer.resolveGlowColor(stack) & 0xFFFFFF);
        }, ModItems.GLINT_TRIM.get());
    }
}
