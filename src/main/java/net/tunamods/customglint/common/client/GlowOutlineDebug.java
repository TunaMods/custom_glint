package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * TEMPORARY diagnostic for the EnhancedVisuals glow-outline black-screen. Logs the bound draw
 * framebuffer, the main / mask fbo ids, and any pending GL error at key points in the glow drain and
 * around EnhancedVisuals' {@code RenderGuiEvent.Post} render, so we can see exactly where the main
 * target stops being the bound draw target (which presents as a fully black screen).
 *
 * <p>Logging policy (so it survives long enough to catch the repro instead of being eaten by menu frames):
 * <ul>
 *   <li>Drain-family tags ({@code drain.*}, {@code drainGui.*}) — these only fire when a glow item is on
 *       screen, so always log them (capped at {@link #EVENT_CAP}).</li>
 *   <li>Any anomaly (bound draw fbo != main fbo, or a GL error) — always log (capped).</li>
 *   <li>Per-frame heartbeat / EV-bracket tags ({@code renderTick.END}, {@code guiPost.*}) — log only the
 *       first few of each as a sanity check they fire, then suppress unless they show an anomaly.</li>
 * </ul>
 * Toggle with {@code -Dcustomglint.glowdebug=false}; on by default while diagnosing. Remove once pinned.
 */
public final class GlowOutlineDebug {
    private GlowOutlineDebug() {}

    private static final Logger LOG = LogUtils.getLogger();
    public static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("customglint.glowdebug", "true"));

    private static final int EVENT_CAP = 1500;   // max drain/anomaly lines before self-silencing
    private static final int SANITY_PER_TAG = 3; // heartbeat tags: log this many, then only on anomaly
    private static final int PIXEL_CAP = 900;    // max centre-pixel readbacks

    private static int eventLines = 0;
    private static int pixelLines = 0;
    private static int frameCounter = 0;
    private static boolean glowActiveThisFrame = false;
    private static final Map<String, Integer> tagSeen = new HashMap<>();

    /** Set by each GUI-drain hook so the log shows which context drained. */
    public static volatile String drainSource = "?";

    /** DIAGNOSTIC: when true, drains accumulate into the mask but skip drawing the ring into main. Isolates
     *  whether EV reacts to our main-target composite write vs the mask framebuffer usage. */
    public static final boolean SKIP_COMPOSITE =
            Boolean.parseBoolean(System.getProperty("customglint.skipComposite", "false"));

    /** Reflect the depth of the RenderSystem modelview stack, to catch a push/pop imbalance our drain might
     *  leave (which would corrupt EV's own pushPose/popPose and blacken the frame). -1 if unreadable. */
    private static int modelViewStackDepth() {
        try {
            var ms = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
            for (var f : com.mojang.blaze3d.vertex.PoseStack.class.getDeclaredFields()) {
                if (java.util.Deque.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return ((java.util.Deque<?>) f.get(ms)).size();
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static float pitch() {
        var p = Minecraft.getInstance().player;
        return p != null ? p.getXRot() : Float.NaN;
    }

    private static final ByteBuffer PIXEL = BufferUtils.createByteBuffer(4);

    /** Called once per rendered frame (RenderTickEvent.START). */
    public static void frameTick() { frameCounter++; glowActiveThisFrame = false; }

    /** Marked by the drains so the (rate-limited) pixel readback only samples frames with a glow on screen. */
    public static void markGlowActive() { glowActiveThisFrame = true; }

    private static int glStateLines = 0;

    /** Dump the GL / RenderSystem state EV's fullscreen quad depends on, so we can see what our glow drain
     *  leaves dirty. Dumps on glow AND non-glow frames (capped) so a glow-frame line can be diffed against a
     *  known-good non-glow line at the same tag. */
    public static void dumpGlState(String tag) {
        if (!ENABLED || glStateLines >= 400) return;
        glStateLines++;
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        int sRGB = GL30.glGetInteger(GL30.GL_BLEND_SRC_RGB), dRGB = GL30.glGetInteger(GL30.GL_BLEND_DST_RGB);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        int stencilFunc = GL11.glGetInteger(GL11.GL_STENCIL_FUNC);
        int stencilRef = GL11.glGetInteger(GL11.GL_STENCIL_REF);
        int stencilWM = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean polyOff = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
        int prog = GL30.glGetInteger(GL30.GL_CURRENT_PROGRAM);
        int activeTex = GL30.glGetInteger(GL30.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int tex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int tex1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(activeTex);
        int[] wm = new int[4];
        GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, wm);
        float[] sc = com.mojang.blaze3d.systems.RenderSystem.getShaderColor();
        int mvDepth = modelViewStackDepth();
        LOG.info("[glowdebug] f{} glow={} {} GLSTATE: blend={} func=[{}/{}] depthTest={} depthMask={} stencil={}(func={},ref={},wm={}) scissor={} cull={} polyOff={} prog={} activeTex=0x{} tex0={} tex1={} mvStackDepth={} colorMask=[{},{},{},{}] shaderColor=[{},{},{},{}]",
                frameCounter, glowActiveThisFrame, tag, blend, sRGB, dRGB, depth, depthMask,
                stencil, stencilFunc, stencilRef, stencilWM, scissor, cull, polyOff, prog,
                Integer.toHexString(activeTex), tex0, tex1, mvDepth, wm[0], wm[1], wm[2], wm[3],
                sc[0], sc[1], sc[2], sc[3]);
    }

    /** Read the centre pixel of the MAIN render target and log its RGB. Answers "is main itself black?"
     *  independent of what is bound right now (binds main for read, restores the previous read binding). */
    public static void logMainCenterPixel(String tag) {
        if (!ENABLED || !glowActiveThisFrame || pixelLines >= PIXEL_CAP) return;
        pixelLines++;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        // Sample a 5-point grid on MAIN to MAP where black is (whole-screen vs localized around the held item).
        // Points: TL, TR, C, BL, BR at 1/4 & 3/4. Center avoided-ish (crosshair inverts a white ring there).
        int w = main.width, h = main.height;
        int[][] pts = { {w/4, 3*h/4}, {3*w/4, 3*h/4}, {w/2, h/2}, {w/4, h/4}, {3*w/4, h/4} };
        String[] names = { "TL", "TR", "C", "BL", "BR" };
        StringBuilder sb = new StringBuilder();
        int blackCount = 0;
        for (int i = 0; i < pts.length; i++) {
            PIXEL.clear();
            GL11.glReadPixels(pts[i][0], pts[i][1], 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, PIXEL);
            int r = PIXEL.get(0) & 0xFF, g = PIXEL.get(1) & 0xFF, b = PIXEL.get(2) & 0xFF;
            if (r < 8 && g < 8 && b < 8) blackCount++;
            sb.append(' ').append(names[i]).append("=(").append(r).append(',').append(g).append(',').append(b).append(')');
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        LOG.info("[glowdebug] f{} {} [src={} pitch={}]: MAIN grid{}{}",
                frameCounter, tag, drainSource, String.format("%.0f", pitch()), sb,
                blackCount >= 4 ? "  <<< MOSTLY-BLACK" : (blackCount > 0 ? "  ("+blackCount+" black)" : ""));
    }

    /** Log the current draw-framebuffer binding + main/mask fbo + any GL error, tagged with a call site. */
    public static void log(String tag) {
        if (!ENABLED) return;
        int draw = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int read = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int mainId = Minecraft.getInstance().getMainRenderTarget().frameBufferId;
        int maskId = GlowOutlineRenderer.debugMaskFboId();
        int err = GL11.glGetError();
        boolean anomaly = (draw != mainId) || (err != 0);

        boolean heartbeat = tag.startsWith("renderTick") || tag.startsWith("guiPost");
        if (heartbeat && !anomaly) {
            int seen = tagSeen.merge(tag, 1, Integer::sum);
            if (seen > SANITY_PER_TAG) return; // stop spamming the normal per-frame heartbeat
        }
        if (eventLines >= EVENT_CAP) return;
        eventLines++;

        String where = draw == mainId ? "MAIN" : (draw == maskId ? "MASK" : "OTHER(" + draw + ")");
        LOG.info("[glowdebug] f{} {}: draw={} read={} main={} mask={} -> {}{}{}",
                frameCounter, tag, draw, read, mainId, maskId, where,
                anomaly ? "  <<< ANOMALY" : "",
                err != 0 ? "  GLERR=0x" + Integer.toHexString(err) : "");
    }
}
