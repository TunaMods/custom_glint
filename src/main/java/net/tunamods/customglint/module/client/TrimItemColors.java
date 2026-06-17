package net.tunamods.customglint.module.client;

/**
 * Animated per-color tint for the Glint Trim / Glow Trim inventory icons.
 *
 * <p><b>TODO(26.1) — neutralized to a green-compile stub.</b> 26.1.x replaced the
 * {@code RegisterColorHandlersEvent.Item} {@code ItemColor} lambda system with a data-driven tint
 * pipeline: {@code RegisterColorHandlersEvent.ItemTintSources} registers
 * {@code ItemTintSource} implementations via a {@code LateBoundIdMapper}, and the item-model JSON
 * (under {@code assets/customglint/items/*.json}) references the tint source. The old
 * {@code event.register((stack, tintIndex) -> animatedGlowColor, item)} does not port 1:1 — the
 * animated trim-color tint must move to a custom {@code ItemTintSource} (and the trim item models must
 * be migrated to the new {@code minecraft:custom_model_data} / tint-source format). Also note
 * {@code @EventBusSubscriber} lost its {@code bus}/{@code Bus} element in NeoForge 26.1 (single unified
 * bus). The 1.21.1 implementation is preserved in git history (working-1.21.1 branch).
 */
public final class TrimItemColors {
    private TrimItemColors() {}

    // TODO(26.1): register a custom ItemTintSource for GLOW_TRIM and the glowing GLINT_TRIM that
    // returns 0xFF000000 | (CustomGlintRenderer.computeAnimatedGlowColor(colors) & 0xFFFFFF) for
    // tintIndex 0, via RegisterColorHandlersEvent.ItemTintSources + the item-model JSON.
}
