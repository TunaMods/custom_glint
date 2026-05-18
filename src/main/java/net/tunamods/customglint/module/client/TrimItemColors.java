package net.tunamods.customglint.module.client;

import net.tunamods.customglint.CustomGlintMod;
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
        event.register((stack, tintIndex) -> {
            if (tintIndex != 0) return 0xFFFFFFFF;
            int[] colors = GlowTrimItem.getColors(stack);
            if (colors.length == 0) return 0xFFFFFFFF;
            return 0xFF000000 | (CustomGlintRenderer.computeAnimatedGlowColor(colors) & 0xFFFFFF);
        }, CustomGlintMod.GLOW_TRIM.get());

        event.register((stack, tintIndex) -> {
            if (tintIndex != 0) return 0xFFFFFFFF;
            if (!GlintTrimItem.isGlowing(stack)) return 0xFFFFFFFF;
            int[] colors = GlintTrimItem.getColors(stack);
            if (colors.length == 0) return 0xFFFFFFFF;
            return 0xFF000000 | (CustomGlintRenderer.computeAnimatedGlowColor(colors) & 0xFFFFFF);
        }, CustomGlintMod.GLINT_TRIM.get());
    }
}
