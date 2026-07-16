package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Post-composite replay of the ITEM path's glint layers under an active shaderpack (Oculus/Iris).
 *
 * <p>NAME IS HISTORICAL. This was built for chromatic, which used to bind a private core shader Iris colour-masks
 * to nothing during the level pass, so its draw had to be captured and re-run after the pack composited. Chromatic
 * does not come through here any more: its slick is baked to an ordinary texture ({@link ChromaticTextureBaker}),
 * so it rides the pack's own GLINT program in-gbuffer like every other design - which is what let the pack's TAA
 * resolve it and stopped it flickering. What remains is the texture-glint item deferral (see
 * {@link CustomGlintRenderer#postCompositeVariant} / {@code GLINT_POST}), which exists for an Embeddium reason and
 * is itself pending a revert.
 *
 * <p>The mechanism mirrors {@link GlowOutlineRenderer}'s shader-pack world drain: while a pack is active, the
 * layer's geometry (camera-space position + UV0) is captured instead of drawn, then re-drawn through a
 * "post-composite" sibling RenderType at the RETURN of {@code LevelRenderer.renderLevel} - after Iris finalizes -
 * so the pack's composite can't erase it. The capture records the live {@code RenderSystem} model-view +
 * projection and the replay restores them, reproducing the exact clip-space position the in-pass draw would have
 * used. Each {@link Recorder} snapshots its OWN pair, so a pass with a different projection (the first-person
 * hand's FOV) replays correctly alongside the world's.
 *
 * <p>Off-pack this path is never taken - the glint draws in-pass as before.
 */
public final class ChromaticShaderReplay {
    private ChromaticShaderReplay() {}

    private static final List<Recorder> worldJobs = new ArrayList<>();

    // Reused immediate source for the replay flush. One layer's captured stream is emitted, then endBatch runs the
    // post-composite RT's setup (shader / design texture / scroll matrix / additive blend / EQUAL depth) and draws it.
    private static final BufferBuilder REPLAY_BB = new BufferBuilder(2048);
    private static final MultiBufferSource.BufferSource REPLAY_SRC = MultiBufferSource.immediate(REPLAY_BB);

    /** Per-frame reset; called from the RenderTickEvent.START listener alongside the glow renderer's. */
    public static void beginFrame() { worldJobs.clear(); }

    /** Capture a deferrable glint layer directly from the item's BAKED QUADS instead of from the foil vertex
     *  stream. Used by the item path under an active shaderpack: drawing the glint in-phase would put the item
     *  base in a {@code VertexMultiConsumer} alongside our foreign delegate, and Embeddium's bulk vertex encoder
     *  (which a chromatic entity draw flips on for the frame) then DROPS the base's writes - the item loses its
     *  texture. So the base is drawn alone and every glint layer is captured here from the quads and replayed
     *  post-Iris. {@code pose} is the item's reconstructed display pose (same transform the foil stream applied),
     *  so {@code putBulkData} lands the identical camera-pose-space {@code [x,y,z,u,v]} the foil-stream capture
     *  would have. {@code inPassRt} must have a registered post-composite sibling (see
     *  {@link CustomGlintRenderer#postCompositeVariant}) - a chromatic RT does not, and no longer reaches here. */
    public static void captureQuads(RenderType inPassRt, List<BakedQuad> quads, PoseStack.Pose pose, int light) {
        if (quads.isEmpty()) return;
        Recorder r = new Recorder(inPassRt,
                new Matrix4f(RenderSystem.getModelViewMatrix()),
                new Matrix4f(RenderSystem.getProjectionMatrix()));
        for (BakedQuad q : quads) r.putBulkData(pose, q, 1.0f, 1.0f, 1.0f, light, OverlayTexture.NO_OVERLAY);
        worldJobs.add(r);
    }

    /** Re-draw every captured layer over the finished frame. Called at {@code renderLevel} RETURN
     *  (LevelRendererMixin), after Iris's finalize, only while a pack is active. */
    public static void drainWorldShaderPack() {
        if (worldJobs.isEmpty()) return;

        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        main.bindWrite(true);

        // Snapshot the GL state this drain mutates and hand it back in the finally. Without this the leaked
        // blend / mask / bound-texture state bleeds into the following GUI pass and paints glinted icons black -
        // the same class of leak GlowOutlineRenderer.bindMainAndResetState guards against. The RenderType's own
        // clearRenderState resets most per-shard state, but a mid-drain throw (or an unrestored enable flag) must
        // not escape into the HUD render.
        boolean savedBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean savedDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        // LOAD-BEARING: setProjectionMatrix also sets RenderSystem's global VertexSorting, so the replay's
        // DISTANCE_TO_ORIGIN must be handed back with the projection. It is NOT GL state - it is read later by
        // MultiBufferSource.endBatch (`renderType.end(builder, RenderSystem.getVertexSorting())`) and applied by
        // every sortOnUpload RenderType. Leaking it re-sorts the GUI/world item base (itemEntityTranslucentCull
        // is sortOnUpload) against world-space distances, wrecking its index buffer: the item loses its texture
        // and reads as a solid glint-coloured silhouette. Our glint RTs are sortOnUpload=false, so they are
        // untouched - which is exactly why the glint survives and only the base dies, with every GL probe clean.
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        PoseStack mv = RenderSystem.getModelViewStack();
        mv.pushPose();
        try {
            for (Recorder r : worldJobs) {
                if (r.count < 15) continue; // need at least one primitive (3 verts * 5 floats)
                RenderType pc = CustomGlintRenderer.postCompositeVariant(r.rt);
                if (pc == null) continue;

                mv.setIdentity();
                mv.mulPoseMatrix(r.modelView);
                RenderSystem.applyModelViewMatrix();
                RenderSystem.setProjectionMatrix(r.proj, VertexSorting.DISTANCE_TO_ORIGIN);

                VertexConsumer vc = REPLAY_SRC.getBuffer(pc);
                float[] d = r.data;
                for (int i = 0; i + 4 < r.count; i += 5) {
                    vc.vertex(d[i], d[i + 1], d[i + 2]).uv(d[i + 3], d[i + 4]).endVertex();
                }
                REPLAY_SRC.endBatch();
            }
        } finally {
            mv.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProj, savedSorting);
            // Release Iris's depth-color lock FIRST. Applying our plain chromatic ShaderInstance under an active
            // pack trips Iris's DepthColorStorage lock; while it's held, colorMask/depthMask calls are DEFERRED
            // (stored, not applied) and Iris's own glint ExtendedShader never releases it - so a leftover lock
            // swallows the mask restore below and every GUI glint inherits the forced-off mask (renders black /
            // fully glint-coloured). Unlocking before the restore lets the colorMask/depthMask below actually take.
            // No-op when nothing is locked or when Iris is absent.
            releaseIrisDepthColorLock();
            // Iris locks BLEND separately (BlendModeStorage): while blendLocked, its GlStateManager mixin
            // CANCELS every _enableBlend / _blendFuncSeparate call, so the blend restore below (and the toggles)
            // were being swallowed and the replay's additive func bled into the GUI pass (glinted icons black).
            // A real chromatic draw trips Iris's own lock/unlock, which is why only a held chromatic item masked
            // it. restoreBlend() clears the lock and hands GL back Iris's pre-lock blend state. No-op when unlocked.
            releaseIrisBlendLock();
            // Rebind the main target and hand back exactly the state this drain touched - mirrors
            // GlowOutlineRenderer.bindMainAndResetState for the world phase. defaultBlendFunc() is load-bearing:
            // the replay's additive GLINT blend func must NOT leak into the following HUD / Enhanced Visuals pass
            // (a leaked additive func renders glinted icons / overlays black). The held-item correlation was this
            // leak - holding a glint item ran another glint draw that reset the func and masked it.
            main.bindWrite(true);
            RenderSystem.setShaderTexture(0, 0);
            RenderSystem.setShaderTexture(1, 0);
            // Iris's blend / depth / mask overrides can leave GlStateManager's cache out of sync with real GL,
            // so a plain RenderSystem.enableX() dedups against the stale cache and never writes GL - the restore
            // silently no-ops and the leaked state bleeds into the GUI glint pass (icons render black). Toggle to
            // the opposite first so the final call always differs from the cache and forces a real GL write,
            // landing both cache and GL on the intended state.
            RenderSystem.colorMask(false, false, false, false);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(false);
            RenderSystem.depthMask(true);
            if (savedDepthTest) { RenderSystem.disableDepthTest(); RenderSystem.enableDepthTest(); }
            else { RenderSystem.enableDepthTest(); RenderSystem.disableDepthTest(); }
            if (savedBlend) { RenderSystem.disableBlend(); RenderSystem.enableBlend(); }
            else { RenderSystem.enableBlend(); RenderSystem.disableBlend(); }
            // Same desync trap for the blend FUNC: the replay's additive GLINT func (dstAlpha = ZERO) can leak
            // into the GUI pass and zero the icons' alpha (they blit black). defaultBlendFunc() alone dedups
            // against the stale cache, so set an off-default func first to force the real write.
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO,
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            RenderSystem.disableScissor();
            worldJobs.clear();
        }
    }

    // Reflective release of Iris's internal depth-color lock (net.irisshaders.iris.gl.blending.DepthColorStorage),
    // resolved once. No compileOnly dep - matches the reflective Iris detection in CustomGlintRenderer. Absent
    // (non-Iris / method moved) → silently no-op.
    private static volatile boolean irisUnlockResolved = false;
    private static volatile Method irisUnlock = null;

    private static void releaseIrisDepthColorLock() {
        if (!irisUnlockResolved) {
            synchronized (ChromaticShaderReplay.class) {
                if (!irisUnlockResolved) {
                    try {
                        Class<?> c = Class.forName("net.irisshaders.iris.gl.blending.DepthColorStorage");
                        irisUnlock = c.getMethod("unlockDepthColor");
                    } catch (Throwable ignored) {
                        irisUnlock = null;
                    }
                    irisUnlockResolved = true;
                }
            }
        }
        if (irisUnlock == null) return;
        try { irisUnlock.invoke(null); } catch (Throwable ignored) {}
    }

    // Reflective release of Iris's internal blend lock (net.irisshaders.iris.gl.blending.BlendModeStorage),
    // resolved once. Absent (non-Iris / method moved) → silently no-op.
    private static volatile boolean irisBlendResolved = false;
    private static volatile Method irisRestoreBlend = null;

    private static void releaseIrisBlendLock() {
        if (!irisBlendResolved) {
            synchronized (ChromaticShaderReplay.class) {
                if (!irisBlendResolved) {
                    try {
                        Class<?> c = Class.forName("net.irisshaders.iris.gl.blending.BlendModeStorage");
                        irisRestoreBlend = c.getMethod("restoreBlend");
                    } catch (Throwable ignored) {
                        irisRestoreBlend = null;
                    }
                    irisBlendResolved = true;
                }
            }
        }
        if (irisRestoreBlend == null) return;
        try { irisRestoreBlend.invoke(null); } catch (Throwable ignored) {}
    }

    /** Records camera-space {@code [x,y,z,u,v]} per vertex (chromatic reads only Position + UV0); every other
     *  vertex attribute the model feeds is ignored. */
    private static final class Recorder implements VertexConsumer {
        private final RenderType rt;
        private final Matrix4f modelView, proj;
        private float[] data = new float[512];
        private int count;
        private float px, py, pz; // stash position until uv() flushes the 5-tuple

        Recorder(RenderType rt, Matrix4f modelView, Matrix4f proj) {
            this.rt = rt; this.modelView = modelView; this.proj = proj;
        }

        private void push(float u, float v) {
            if (count + 5 > data.length) data = Arrays.copyOf(data, data.length * 2);
            data[count++] = px; data[count++] = py; data[count++] = pz; data[count++] = u; data[count++] = v;
        }

        @Override public VertexConsumer vertex(double x, double y, double z) { px = (float) x; py = (float) y; pz = (float) z; return this; }
        @Override public VertexConsumer uv(float u, float v) { push(u, v); return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float nx, float ny, float nz) { return this; }
        @Override public void endVertex() {}
        @Override public void defaultColor(int r, int g, int b, int a) {}
        @Override public void unsetDefaultColor() {}
    }
}
