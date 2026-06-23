package net.tunamods.customglint.module.client;

import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.tunamods.customglint.common.CustomGlint;

/**
 * Registers the animated trim-glow tint ({@link GlowTintSource}) for the Glint Trim / Glow Trim
 * inventory icons.
 *
 * <p>26.1 replaced {@code RegisterColorHandlersEvent.Item} (where 1.21.1 registered an {@code ItemColor}
 * lambda per item) with a data-driven pipeline: an {@link net.minecraft.client.color.item.ItemTintSource}
 * is registered by id via {@code RegisterColorHandlersEvent.ItemTintSources}, and the trim item-model JSON
 * references it from a {@code tints} entry. So instead of binding to specific items in code, we register
 * one source under {@code customglint:glow} and reference it from {@code items/glow_trim.json} and the
 * glowing variant in {@code items/glint_trim.json}.
 *
 * <p>Client-only, called from the {@code CustomGlintMod} constructor inside a {@code Dist.CLIENT} guard,
 * so the class (and its client-only imports) never loads on a dedicated server.
 */
public final class TrimItemColors {
    private TrimItemColors() {}

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener((RegisterColorHandlersEvent.ItemTintSources event) ->
                event.register(CustomGlint.res("glow"),
                        GlowTintSource.MAP_CODEC));
    }
}
