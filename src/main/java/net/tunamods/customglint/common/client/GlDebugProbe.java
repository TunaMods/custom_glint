package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLDebugMessageCallback;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opt-in diagnostic for the {@code GL_INVALID_OPERATION "No active program"} spam seen when glint/glow
 * renders alongside Iris. Minecraft's own GL debug callback logs the message but with no stack trace (the
 * callback is async), so the offending draw can't be identified from the log alone.
 *
 * <p>When enabled this installs OUR debug callback in {@code GL_DEBUG_OUTPUT_SYNCHRONOUS} mode, so the
 * callback fires on the render thread at the exact GL call. For each distinct high-severity error id it
 * logs the message plus a Java stack trace ONCE, that trace names the method (ours, vanilla's, or Iris's)
 * issuing the bad draw, which is what the fix needs.
 *
 * <p>Off by default. Enable with the JVM arg {@code -Dcustomglint.gldebug=true} (add it to the launcher's
 * JVM arguments). Pure diagnostic: it changes no render state and never runs in a normal session.
 */
public final class GlDebugProbe {
    private GlDebugProbe() {}

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("customglint.gldebug");

    private static final int GL_DEBUG_OUTPUT = 0x92E0;
    private static final int GL_DEBUG_OUTPUT_SYNCHRONOUS = 0x8242;
    private static final int GL_DEBUG_SEVERITY_HIGH = 0x9146;
    private static final int GL_DEBUG_TYPE_ERROR = 0x824C;

    private static volatile boolean installed = false;
    /** Held so the native callback isn't garbage-collected while GL still points at it. */
    @SuppressWarnings("unused")
    private static GLDebugMessageCallback callback;
    /** One stack trace per distinct error id, the spam repeats the same id, we only need it once. */
    private static final Set<Integer> loggedIds = ConcurrentHashMap.newKeySet();

    /** Idempotent; safe to call every frame. No-ops unless {@code -Dcustomglint.gldebug=true}. Must run on
     *  the render thread with the GL context current. */
    public static void install() {
        if (!ENABLED || installed) return;
        installed = true; // set first so a throw below doesn't retry-spam
        if (!RenderSystem.isOnRenderThread()) { installed = false; return; }
        try {
            GL43.glEnable(GL_DEBUG_OUTPUT);
            GL43.glEnable(GL_DEBUG_OUTPUT_SYNCHRONOUS); // callback fires in-call → the stack is the culprit
            callback = GLDebugMessageCallback.create((source, type, id, severity, length, message, userParam) -> {
                if (severity != GL_DEBUG_SEVERITY_HIGH && type != GL_DEBUG_TYPE_ERROR) return;
                if (!loggedIds.add(id)) return; // already captured this id's stack
                String msg = GLDebugMessageCallback.getMessage(length, message);
                LOGGER.error("[customglint gldebug] GL error id={} type={} severity={}: {}\n{}",
                        id, type, severity, msg, stack());
            });
            GL43.glDebugMessageCallback(callback, 0L);
            LOGGER.info("[customglint gldebug] synchronous GL debug probe installed, first occurrence of "
                    + "each high-severity GL error id will be logged with a stack trace.");
        } catch (Throwable t) {
            LOGGER.warn("[customglint gldebug] failed to install GL debug probe", t);
        }
    }

    private static String stack() {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        // Skip getStackTrace + this method + the callback lambda frames; print the rest.
        for (int i = 3; i < frames.length; i++) sb.append("\tat ").append(frames[i]).append('\n');
        return sb.toString();
    }
}
