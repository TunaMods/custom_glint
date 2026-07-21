package net.tunamods.customglint.common.client;

import java.lang.reflect.Method;

/**
 * Soft compat with Gnetum (a HUD-caching optimisation mod). Gnetum renders the in-game HUD (hotbar, …)
 * into an offscreen framebuffer and re-renders each element only every N frames, blitting the cached copy
 * on the frames in between. Our per-item content needs the element re-rendered every frame: the animated
 * glint foil freezes at whatever frame gnetum last cached, and the glow-outline ring flickers. Its
 * silhouette is captured at {@code ItemRenderer.render}, so on a cached frame nothing is captured and the
 * once-per-frame {@code GlowOutlineRenderer.drainGui} composites no ring, while the refresh frame does.
 *
 * <p>Gnetum exposes {@code Gnetum.disableCachingForCurrentElement(String reason)}: called while gnetum is
 * mid-render of a HUD element, it drops that element from the cache so it renders live every frame. The
 * change is one-way for the session and applies only when the user left that element's caching on AUTO (an
 * explicit user override is respected). An animated glint or glow ring can't be served from a stale cache,
 * so this matches how gnetum's own dynamic elements opt out. Idempotent: after the first disable the call is
 * a cheap no-op inside gnetum.
 *
 * <p>Reflective, no {@code compileOnly} dep; every method no-ops when gnetum is absent or its API moved.
 * Reached only from the client render path ({@code ItemRendererMixin}).
 */
public final class GnetumHudCompat {
    private GnetumHudCompat() {}

    private static volatile boolean lookupDone = false;
    private static volatile Method disableCaching = null;

    /** Tell gnetum not to cache the HUD element it is currently rendering (the hotbar, when a glinted /
     *  glowing item draws into it). No-op if gnetum isn't loaded, isn't mid-HUD-render, or already dropped
     *  that element. */
    public static void disableHudCachingForCurrentElement() {
        if (!lookupDone) {
            synchronized (GnetumHudCompat.class) {
                if (!lookupDone) {
                    try {
                        Class<?> gnetum = Class.forName("me.decce.gnetum.Gnetum");
                        disableCaching = gnetum.getMethod("disableCachingForCurrentElement", String.class);
                    } catch (Throwable ignored) {
                        // Gnetum absent or API renamed → stay a no-op for the session.
                        disableCaching = null;
                    }
                    lookupDone = true;
                }
            }
        }
        if (disableCaching == null) return;
        try {
            disableCaching.invoke(null, "customglint: animated glint / glow outline");
        } catch (Throwable ignored) {
            // A throwing gnetum internal must not break item rendering.
        }
    }
}
