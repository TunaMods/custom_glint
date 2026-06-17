package net.tunamods.customglint.common.client;

import net.tunamods.customglint.common.CustomGlint;

/**
 * Thread-locals that bridge the two phases of the 26.1 item render:
 *
 * <ul>
 *   <li>{@link #SUBMIT_GLINT} is set during {@code ItemStackRenderState.submit(...)} (extraction) so
 *       every {@code ItemSubmit} node created inside that synchronous call captures the item's glint.</li>
 *   <li>{@link #DRAW_GLINT} is set during {@code ItemFeatureRenderer.renderItem(...)} (the deferred
 *       draw) from the node's carried glint, then read by the foil-buffer injection.</li>
 * </ul>
 *
 * <p>Lives outside the {@code common.mixin} package because Mixin reserves that package and forbids
 * direct references to non-mixin classes within it.
 */
public final class GlintCarrier {
    public static final ThreadLocal<CustomGlint.Data> SUBMIT_GLINT = new ThreadLocal<>();
    public static final ThreadLocal<CustomGlint.Data> DRAW_GLINT = new ThreadLocal<>();

    /**
     * Item glow state published during {@code ItemStackRenderState.submit(...)} so each
     * {@code ItemSubmit} node captures it for the deferred glow-outline draw. Glow is independent of
     * the glint, so it rides the carrier separately (see {@link CgGlintHolder}). No DRAW-side thread
     * local is needed: the item draw ({@code ItemFeatureRenderer.renderItem}) reads the glow back
     * straight off the {@code ItemSubmit} node, which is in scope there.
     */
    public static final ThreadLocal<Boolean> SUBMIT_GLOWING = new ThreadLocal<>();
    public static final ThreadLocal<int[]> SUBMIT_GLOW_COLORS = new ThreadLocal<>();

    /**
     * A fresh identity token per {@code ItemStackRenderState.submit} call, used as the outline GROUP key
     * for special-renderer items (shield, trident): one such item submits several model/part nodes
     * (base + patterns + foil), and keying them all by this token merges them into a single ring instead
     * of compositing the same silhouette several times. Distinct items get distinct tokens → distinct
     * rings. Read by {@code SubmitNodeStorageMixin} during the special renderer's submit.
     */
    public static final ThreadLocal<Object> SUBMIT_TOKEN = new ThreadLocal<>();

    private GlintCarrier() {}
}
