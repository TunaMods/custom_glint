package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SequencedMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static net.tunamods.customglint.CustomGlintMod.MOD_ID;

import net.tunamods.customglint.common.CustomGlint.Data;
import net.tunamods.customglint.common.CustomGlint.Layer;

/**
 * Client-only rendering backend. Split out of {@link CustomGlint} so that the data-API class
 * remains loadable on dedicated servers (where the render classes are absent). Mods bundling the
 * api jar should call render-pipeline methods through this class; NBT/data API stays on
 * {@link CustomGlint}.
 *
 * <p>26.1.2 port: the GPU state is built on {@link GlintPipelines} (immutable RenderPipeline /
 * RenderSetup / StencilTest) instead of the deleted {@code RenderStateShard}/{@code CompositeState}
 * model. Per-RenderType color rides the vertex color (callers inject it via a color-overriding
 * VertexConsumer); animation rides a {@link net.minecraft.client.renderer.rendertype.TextureTransform}
 * supplier.
 */
public final class CustomGlintRenderer {

    // ── Texture cache ─────────────────────────────────────────────────────────

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Identifier, Identifier> textureCache = new HashMap<>();

    public static Identifier getTexture(Identifier design) {
        if (textureCache.containsKey(design)) return textureCache.get(design);
        Identifier result = generateTexture(design);
        textureCache.put(design, result);
        return result;
    }

    public static void clearTextures() {
        Minecraft mc = Minecraft.getInstance();
        for (Identifier loc : textureCache.values())
            if (loc != null) mc.getTextureManager().release(loc);
        textureCache.clear();
        for (Runnable r : additionalReloadCleanup) {
            try { r.run(); } catch (Throwable t) {
                LOGGER.warn("[{}/CustomGlint] additional reload cleanup threw", MOD_ID, t);
            }
        }
        if (fixedBufferRegistry != null) {
            for (RenderType rt : BY_GLINT.values())             fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_ARMOR_GLINT.values())       fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_HORSE_ARMOR_GLINT.values()) fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_GLOW_MASK.values())          fixedBufferRegistry.remove(rt);
        }
        BY_GLINT.clear();
        BY_ARMOR_GLINT.clear();
        BY_HORSE_ARMOR_GLINT.clear();
        BY_GLOW_MASK.clear();
        // Free the in-phase glow-body native buffers. Their keys are RenderTypes that the cleared
        // BY_GLOW_MASK maps just dropped, so after a reload glowMaskRT mints fresh RenderType instances
        // and computeIfAbsent would orphan these builders — a slow per-reload native-memory leak. Close
        // and drop them so the next frame rebuilds against the new RenderTypes.
        for (ByteBufferBuilder b : GLOW_BODY_FIXED.values()) {
            try { b.close(); } catch (Throwable ignored) {}
        }
        GLOW_BODY_FIXED.clear();
        glowBodyBuffer = null;
        // TRIED (did not fix): after Iris/Oculus pack ON→OFF toggle, items in item frames develop
        // a "lens through walls" effect — looking through one outlined item shows other outlined
        // items behind walls. Hypothesis was that Iris's pipeline-destroy path leaves vanilla's
        // RenderTarget.useStencil = false even though the depth-stencil attachment is still there
        // (forced by iris's MixinRenderTarget_StencilBufferTest), making glStencilFunc undefined.
        // Tried: `Minecraft.getInstance().getMainRenderTarget().enableStencil()` here in
        // clearTextures (fires on resource reload = shader toggle) AND every frame in
        // CustomGlintClientInit's RenderTickEvent.START. Both no-ops in practice — symptom
        // persisted. Stencil-flag is NOT the cause. Real cause unknown; do not re-attempt this
        // angle without new evidence.
    }

    // ── Isolated glow-outline (silhouette target + composite) ────────────────────────────────────
    // The 26.1 replacement for the entity-body stencil ring the 1.20.1/1.21.1 builds used (confirmed
    // dead end — the dilated band's depth flickered at silhouette edges). Orchestrated by
    // EntityGlintRender.drainBodyOutlines at RenderLevelStageEvent.AfterOpaqueFeatures.

    private static final Map<Identifier, RenderType> BY_GLOW_MASK = new HashMap<>();

    /** Identifier the combined-mask RenderType binds for the scene-depth sampler. Resolved through
     *  TextureManager to {@link #sceneDepthTex}, a holder whose view is re-pointed at the live main-target
     *  depth each frame by {@link #bindSceneDepth}. The mask shader (core/glow_silhouette) samples this to
     *  decide per-fragment occlusion, which is why no separate depth-downsample pass is needed any more. */
    public static final Identifier SCENE_DEPTH_ID = CustomGlint.res("scene_depth");

    /** Per-texture combined-mask RenderType (GLOW_MASK_PIPE: ALWAYS_PASS depth, shape + per-fragment
     *  visibility encoded in alpha by core/glow_silhouette). One render per mob writes everything the
     *  composite needs — no separate shape pass. The draw is redirected to the half-res mask target by
     *  the caller via RenderSystem.outputColor/DepthTextureOverride. */
    public static RenderType glowMaskRT(Identifier texture) {
        RenderType rt = BY_GLOW_MASK.computeIfAbsent(texture, t ->
                GlintPipelines.glowMaskType(MOD_ID + ":glow_mask_" + texTag(t), t, SCENE_DEPTH_ID));
        registerFixed(rt);
        registerLiveFixedBuffer(rt);
        return rt;
    }

    /** Accumulates one entity model into the single combined-mask buffer with ONE model render and ONE
     *  vertex emit (the old path rendered the model into TWO fanned buffers — a full-shape pass and a
     *  visible pass — doubling the per-mob CPU emit and the GPU silhouette fill). Occlusion is now decided
     *  per-fragment in the shader against the bound scene depth, so a single pass carries both. Nothing
     *  draws yet: every mob sharing a texture piles into the same fixed buffer, and one {@link #flushGlowRT}
     *  per texture then draws them all (1×textures draws, not 2×mobs). Hot path for "dozens/hundreds of
     *  glowing mobs always on screen". */
    public static void accumulateGlowMask(PoseStack pose, Model<?> model,
            RenderType maskRT, int packedLight, int color, int glowKey) {
        if (model == null || maskRT == null) return;
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        MultiBufferSource.BufferSource bs = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer c = new AABBTrackingConsumer(
                new FullColorOverrideConsumer(bs.getBuffer(maskRT), r, g, b, glowKey), glowMaskBox);
        model.renderToBuffer(pose, c, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
    }

    // ── In-phase entity-body glow capture (option A — vanilla-parity) ──────────────────────────────
    // Vanilla's glowing-entity outline tees a SECOND renderToBuffer into a shared OutlineBufferSource
    // right after the body draw, while the model is still posed + setupAnim'd (ModelFeatureRenderer.
    // renderModel). No second setupAnim, no per-entity pose alloc, no deferred re-walk. We mirror that:
    // ModelFeatureRendererMixin calls teeBodyGlow at renderModel TAIL, capturing the posed silhouette
    // into our OWN buffer source (so the main BufferSource's endBatch at the end of renderSolidFeatures
    // never flushes it into the main target), then drainBodyOutlines flushes it into the mask + composites.
    //
    // Its fixed-buffer map is private to this source (NOT the shared fixedBufferRegistry), so getBuffer
    // never auto-flushes on a texture switch and the main endBatch can't touch it.
    private static final java.util.SequencedMap<RenderType, ByteBufferBuilder> GLOW_BODY_FIXED = new java.util.LinkedHashMap<>();
    private static MultiBufferSource.BufferSource glowBodyBuffer;
    private static boolean bodyGlowPresent;
    private static int bodyGlowCount; // this frame's fanned body count, for the outlineMaxEntities cap
    private static float bgMinX, bgMinY, bgMinZ, bgMaxX, bgMaxY, bgMaxZ;
    // Per-body camera-relative AABBs {minX,minY,minZ,maxX,maxY,maxZ} this frame, for the per-cluster
    // composite scissor (EntityGlintRender splits these into disjoint screen rects so the composite pays
    // for each cluster's screen area, not the whole union bbox spanning the gaps between them). The union
    // (bgMin/Max above) is still tracked for the mask clear + the single-rect fallback.
    private static final java.util.List<float[]> bgBoxes = new java.util.ArrayList<>();
    // Parallel to bgBoxes but the TIGHT per-body silhouette AABB (filled by an AABBTrackingConsumer in
    // fanBodyGlow as the body draws), so the drain can scissor a single glowing entity's composite to its
    // real silhouette instead of the loose pose-origin box. Cleared per frame in resetBodyGlow.
    private static final java.util.List<float[]> bgTightBoxes = new java.util.ArrayList<>();
    // Per-frame per-object mask key stamped into the vertex-colour alpha so the id-aware composite
    // (post/glow_outline_id) keeps each silhouette's outline separate AND picks a per-category thickness.
    // key = (category << 5) | id : top 2 bits = category, low 5 = a running 1..31 id. Reset in resetBodyGlow.
    private static int glowIdCounter = 0;
    /** Outline category — top 2 bits of the mask key, indexes THICKNESS[] in post/glow_outline_id.
     *  CAT_ITEM = 3rd-person held + dropped; CAT_HELD_FP = first-person held. */
    public static final int CAT_ENTITY = 0, CAT_ARMOR = 1, CAT_ITEM = 2, CAT_HELD_FP = 3;
    /** Running per-object id, 1..31 (wraps). 5 bits, leaving 2 for the category in the 7-bit mask key. */
    public static int nextGlowId() { glowIdCounter = (glowIdCounter % 31) + 1; return glowIdCounter; }
    /** Mask key = (category << 5) | id (1..127). The composite separates objects by key and reads the
     *  per-category outline thickness from the category bits. */
    public static int nextGlowKey(int category) { return (category << 5) | nextGlowId(); }

    /** One id per outline IDENTITY, shared by every silhouette that belongs to it — an entity's body and
     *  ALL its worn armor (identity = the entity render-state instance), or a special item's sub-models
     *  (identity = the submit token). Same id ⇒ the id-aware composite skips ringing between them, so two
     *  overlapping armor pieces (or body↔armor) compose as ONE ring with no doubled thickness along the
     *  seam, while DISTINCT identities still get distinct ids and stay separated. Identity-keyed (render
     *  states can collide on equals/hashCode); cleared each drain by {@link #resetBodyGlow}. */
    private static final java.util.Map<Object,Integer> GLOW_ID_BY_IDENTITY = new java.util.IdentityHashMap<>();
    /** Mask key for a silhouette belonging to {@code identity}: that identity's shared id (allocated on
     *  first use this frame) combined with {@code category} for per-category thickness. */
    public static int glowKeyFor(Object identity, int category) {
        int id = GLOW_ID_BY_IDENTITY.computeIfAbsent(identity, k -> nextGlowId());
        return (category << 5) | id;
    }

    /**
     * Wraps a glowing entity body's draw buffer so its SINGLE model walk fans into both the normal body
     * buffer AND our glow-mask buffer — the silhouette is captured for the cost of a few extra vertex
     * writes, with no second model traversal and no second setupAnim (strictly cheaper than vanilla's
     * outline, which re-renders the model). Called from {@code ModelFeatureRendererMixin}'s redirect of
     * the body {@code renderToBuffer}. Also records the camera-relative bbox for the composite scissor.
     */
    public static VertexConsumer fanBodyGlow(VertexConsumer bodyBuffer, PoseStack.Pose pose,
            int color, Identifier texture, float bbWidth, float bbHeight,
            Object entityState) {
        return fanEntityGlow(bodyBuffer, pose, color, texture, bbWidth, bbHeight, entityState, true);
    }

    /** Full-opaque silhouette for layer/special models: alpha never discards, so the whole model shape
     *  outlines (the 1.21.1 BEWLR "white.png full fill" approach). RenderLayer models (sheep wool, slime
     *  outer cube, saddle, mushrooms) are 3D geometry whose hull IS the visible shape, so this is correct
     *  — and it sidesteps recovering each layer's bound texture from the immutable RenderType. */
    private static final Identifier WHITE_SILHOUETTE = Identifier.fromNamespaceAndPath("neoforge", "textures/white.png");

    /**
     * Fans a glowing entity's secondary-model draw (a {@code RenderLayer} surface — wool, slime outer
     * cube, saddle, stray clothing, …) into the SAME glow mask the body uses, sharing the entity's outline
     * id ({@link #glowKeyFor}, CAT_ENTITY) so the whole mob composites as ONE ring with no doubled seam.
     * Like {@link #fanBodyGlow} this is in-phase (no second model walk, no second setupAnim) — the layer is
     * already posed when {@code ModelFeatureRenderer.renderModel} draws it. Uses white.png (full geometry)
     * so the silhouette follows the layer's real shape, and does NOT touch the entity cap / loose bbox
     * bookkeeping — the body already accounts the entity; only the tight silhouette box is extended so the
     * composite scissor covers a layer that sticks out past the body (mushrooms, wool).
     */
    public static VertexConsumer fanLayerGlow(VertexConsumer layerBuffer, PoseStack.Pose pose,
            int color, Object entityState) {
        return fanEntityGlow(layerBuffer, pose, color, WHITE_SILHOUETTE, 0.0f, 0.0f, entityState, false);
    }

    private static VertexConsumer fanEntityGlow(VertexConsumer baseBuffer, PoseStack.Pose pose,
            int color, Identifier texture, float bbWidth, float bbHeight,
            Object entityState, boolean isBody) {
        if (texture == null || pose == null || !GlintClientConfig.entityOutlines()) return baseBuffer;
        Matrix4f m = pose.pose();
        double distSq = (double) m.m30() * m.m30() + (double) m.m31() * m.m31() + (double) m.m32() * m.m32();
        if (distSq > GlintClientConfig.outlineMaxDistanceSq()) return baseBuffer; // too far: skip the outline
        if (isBody) {
            int maxEnt = GlintClientConfig.outlineMaxEntities();
            if (maxEnt > 0 && bodyGlowCount >= maxEnt) return baseBuffer; // entity cap reached this frame
            bodyGlowCount++;
        }
        RenderType rt = glowMaskRT(texture);
        GLOW_BODY_FIXED.computeIfAbsent(rt, k -> new ByteBufferBuilder(k.bufferSize()));
        if (glowBodyBuffer == null) {
            glowBodyBuffer = MultiBufferSource.immediateWithBuffers(GLOW_BODY_FIXED, new ByteBufferBuilder(256));
        }
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        // Tight per-object silhouette AABB, filled as the model draws through the returned consumer (added to
        // bgTightBoxes by reference now; populated by drain time). Lets the drain scissor the composite to
        // the real silhouette — body AND any layer that extends past it — not the loose pose-origin box.
        float[] tight = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        VertexConsumer mask = new AABBTrackingConsumer(new FullColorOverrideConsumer(
                glowBodyBuffer.getBuffer(rt), r, g, b, glowKeyFor(entityState, CAT_ENTITY)), tight);
        bgTightBoxes.add(tight);
        if (isBody) {
            // Generous camera-relative box (see EntityGlintRender.computeGroupScissor for the radius
            // rationale). Layers reuse the body's loose box (same entity) — only the tight box above grows.
            float rad = bbWidth + bbHeight + 2.0f;
            float x = m.m30(), y = m.m31(), z = m.m32();
            if (!bodyGlowPresent) {
                bgMinX = x - rad; bgMaxX = x + rad; bgMinY = y - rad; bgMaxY = y + rad; bgMinZ = z - rad; bgMaxZ = z + rad;
                bodyGlowPresent = true;
            } else {
                bgMinX = Math.min(bgMinX, x - rad); bgMaxX = Math.max(bgMaxX, x + rad);
                bgMinY = Math.min(bgMinY, y - rad); bgMaxY = Math.max(bgMaxY, y + rad);
                bgMinZ = Math.min(bgMinZ, z - rad); bgMaxZ = Math.max(bgMaxZ, z + rad);
            }
            bgBoxes.add(new float[]{x - rad, y - rad, z - rad, x + rad, y + rad, z + rad});
        } else {
            // A layer fanning before its body (rare) still needs the entity group to be processed.
            bodyGlowPresent = true;
        }
        return VertexMultiConsumer.create(baseBuffer, mask);
    }

    public static boolean hasBodyGlow() { return bodyGlowPresent; }

    /** Camera-relative AABB {minX,minY,minZ,maxX,maxY,maxZ} of this frame's body glows (union), or null. */
    public static float[] bodyGlowBox() {
        return bodyGlowPresent ? new float[]{bgMinX, bgMinY, bgMinZ, bgMaxX, bgMaxY, bgMaxZ} : null;
    }

    /** Per-body camera-relative AABBs captured this frame, for the per-cluster composite scissor. Live
     *  list, valid only during the drain; cleared by {@link #resetBodyGlow}. */
    public static java.util.List<float[]> bodyGlowBoxes() { return bgBoxes; }

    /** Union of this frame's TIGHT per-body silhouette AABBs (real geometry, not the loose pose box), or
     *  null if none were captured — used to scissor a glowing entity's composite to its actual size. */
    public static float[] bodyGlowTightUnion() {
        float[] u = null;
        for (float[] bx : bgTightBoxes) {
            if (bx[0] > bx[3]) continue; // never drawn → still empty sentinel
            if (u == null) u = new float[]{bx[0], bx[1], bx[2], bx[3], bx[4], bx[5]};
            else {
                u[0] = Math.min(u[0], bx[0]); u[1] = Math.min(u[1], bx[1]); u[2] = Math.min(u[2], bx[2]);
                u[3] = Math.max(u[3], bx[3]); u[4] = Math.max(u[4], bx[4]); u[5] = Math.max(u[5], bx[5]);
            }
        }
        return u;
    }

    /** Draws all captured body silhouettes into the current output override (the mask target). The
     *  endBatch consumes + resets the builders, so next frame starts clean. */
    public static void flushBodyGlow() {
        if (glowBodyBuffer != null) glowBodyBuffer.endBatch();
    }

    public static void resetBodyGlow() { bodyGlowPresent = false; bodyGlowCount = 0; bgBoxes.clear(); bgTightBoxes.clear(); glowIdCounter = 0; GLOW_ID_BY_IDENTITY.clear(); }

    // Tight per-group silhouette AABB (camera-relative world space — the ACTUAL posed geometry captured by
    // an AABBTrackingConsumer wrapped around accumulateGlowMask/Item/Part, NOT the loose pose-origin±radius
    // box). The drain projects this to scissor the COMPOSITE (the expensive 149-tap pass) to the real
    // silhouette instead of the near-fullscreen pose box a close/screen-filling object projects to (e.g. a
    // 3rd-person player in glowing armor). The loose box still drives the cheap mask clear.
    private static final float[] glowMaskBox = new float[6];
    /** Resets the tight-AABB accumulator; the drain calls this once per group before its accumulate* calls. */
    public static void resetGlowMaskBox() {
        glowMaskBox[0] = glowMaskBox[1] = glowMaskBox[2] = Float.POSITIVE_INFINITY;
        glowMaskBox[3] = glowMaskBox[4] = glowMaskBox[5] = Float.NEGATIVE_INFINITY;
    }
    /** The tight silhouette AABB {minX,minY,minZ,maxX,maxY,maxZ} accumulated since the last
     *  {@link #resetGlowMaskBox}, or null when nothing was emitted (degenerate → caller keeps the loose
     *  pose-origin scissor = prior behaviour). */
    public static float[] glowMaskBox() {
        return glowMaskBox[0] <= glowMaskBox[3] ? glowMaskBox.clone() : null;
    }

    /** {@link ModelPart} variant of {@link #accumulateGlowMask} for special-renderer 3D items that
     *  submit model parts (e.g. trident — shaft + prongs, several {@code submitModelPart} calls). Same
     *  forced-colour silhouette into the combined glow mask; the caller passes the white.png mask RT for a
     *  full-shape ring (the 1.21.1 BEWLR approach). All parts of one item submit share {@code glowKey} (one
     *  id per submit token) so they compose as ONE outline instead of ringing each other into boxes. */
    public static void accumulatePartGlowMask(PoseStack pose, ModelPart part,
            RenderType maskRT, int packedLight, int color, int glowKey) {
        if (part == null || maskRT == null) return;
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        MultiBufferSource.BufferSource bs = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer c = new AABBTrackingConsumer(
                new FullColorOverrideConsumer(bs.getBuffer(maskRT), r, g, b, glowKey), glowMaskBox);
        part.render(pose, c, packedLight, OverlayTexture.NO_OVERLAY);
    }

    /** Accumulates one held/dropped item's silhouette into the combined glow mask, the item analog of
     *  {@link #accumulateGlowMask}. Items are a list of {@link BakedQuad}s rather than an {@link EntityModel},
     *  so each quad is re-emitted into the {@link #glowMaskRT} for its sprite's atlas (so the mask shader's
     *  Sampler0 alpha-discard follows the real item shape — flat sprites trace the sprite, 3D models trace
     *  the model). The quad's vertex colour is forced to the resolved glow colour via
     *  {@link FullColorOverrideConsumer}; the pose is the camera-relative {@code ItemSubmit} pose, the same
     *  space the entity bodies drew in, so the silhouette lands at the right screen position. Each distinct
     *  atlas used is added to {@code texturesOut} so the drain flushes its buffer once. Runs inside the
     *  AfterOpaqueFeatures drain, sharing the mask + composite with entity outlines (one unioned ring). */
    public static void accumulateItemGlowMask(PoseStack.Pose pose, List<BakedQuad> quads, int packedLight,
            int color, Set<Identifier> texturesOut, int category) {
        if (quads == null || quads.isEmpty()) return;
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        MultiBufferSource.BufferSource bs = Minecraft.getInstance().renderBuffers().bufferSource();
        QuadInstance qi = new QuadInstance();
        qi.setLightCoords(packedLight);
        qi.setOverlayCoords(OverlayTexture.NO_OVERLAY);
        qi.setColor(0xFFFFFFFF); // white base; FullColorOverrideConsumer forces the glow colour per-vertex
        int gid = nextGlowKey(category); // one key (category + id) for the item so its quads share an outline
        for (BakedQuad quad : quads) {
            Identifier tex = quad.materialInfo().sprite().atlasLocation();
            RenderType rt = glowMaskRT(tex);
            VertexConsumer c = new AABBTrackingConsumer(
                    new FullColorOverrideConsumer(bs.getBuffer(rt), r, g, b, gid), glowMaskBox);
            c.putBakedQuad(pose, quad, qi);
            texturesOut.add(tex);
        }
    }

    /** Draws one accumulated glow RT (all mobs that shared its texture) to the current output override. */
    public static void flushGlowRT(RenderType rt) {
        if (rt == null) return;
        Minecraft.getInstance().renderBuffers().bufferSource().endBatch(rt);
    }

    /** Re-points the scene-depth holder at the live main-target depth view (registering it with
     *  TextureManager on first use). Must be called each frame before the mask RTs flush, so the combined
     *  mask shader samples the current frame's committed scene depth. The holder borrows the view; it never
     *  owns or closes it (see {@link SceneDepthTexture#close}). */
    public static void bindSceneDepth(GpuTextureView view) {
        if (sceneDepthTex == null) {
            sceneDepthTex = new SceneDepthTexture();
            Minecraft.getInstance().getTextureManager().register(SCENE_DEPTH_ID, sceneDepthTex);
        }
        sceneDepthTex.view = view;
    }

    private static SceneDepthTexture sceneDepthTex;

    /** Borrowed-view holder so a RenderType (which resolves textures by Identifier through TextureManager)
     *  can sample the main-target depth, which is a raw GpuTextureView with no Identifier of its own. The
     *  view is owned by the main render target — close() must NOT free it. */
    private static final class SceneDepthTexture extends AbstractTexture {
        GpuTextureView view;
        @Override public GpuTextureView getTextureView() { return view; }
        @Override public void close() { /* borrowed view — owned by the main render target, never freed here */ }
    }

    /**
     * Composite the per-object mask into the ring via the single-pass id-aware shader
     * (post/glow_outline_id): a pixel rings where a DIFFERENT object's visible silhouette is within reach,
     * so each glowing entity / armor / item keeps its own outline instead of merging into one union ring.
     * The mask packs object id + visibility into alpha (core/glow_silhouette). {@code scissor} ({x,y,w,h},
     * bottom-left origin, full-res pixels) confines the pass to a group's screen bbox; null draws full
     * screen. MaskSampler is NEAREST (the alpha is a packed classifier, not a continuous value).
     */
    public static void compositeGlowOutline(GpuTextureView maskColorView, GpuTextureView outColorView,
                                            int[] scissor) {
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "customglint glow outline composite", outColorView, OptionalInt.empty())) {
            pass.setPipeline(GlintPipelines.GLOW_COMPOSITE_ID_PIPE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("MaskSampler", maskColorView,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            // Scene depth for the per-source distance reconstruction (distance-proportional ring thinning).
            // bindSceneDepth(main depth) runs each frame before any composite (EntityGlintRender), so the
            // holder's view is live here. NEAREST + clamp: a depth read needs the exact texel, not a blend.
            pass.bindTexture("DepthSampler", sceneDepthTex.view,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            if (scissor != null) pass.enableScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
            pass.draw(0, 3);
        }
    }

    /** Bilinear-upscales the half-res ring ({@code ringColorView}) onto the main target, blended. */
    public static void upscaleGlowRing(GpuTextureView ringColorView, GpuTextureView mainColorView) {
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "customglint glow upscale", mainColorView, OptionalInt.empty())) {
            pass.setPipeline(GlintPipelines.GLOW_UPSCALE_PIPE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("InSampler", ringColorView,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(0, 3);
        }
    }


    private static Identifier generateTexture(Identifier design) {
        Minecraft mc = Minecraft.getInstance();
        NativeImage source;
        try {
            var resource = mc.getResourceManager().getResource(design);
            if (resource.isEmpty()) return null;
            try (InputStream stream = resource.get().open()) {
                source = NativeImage.read(stream);
            }
        } catch (IOException e) {
            return null;
        }

        NativeImage gray = new NativeImage(source.getWidth(), source.getHeight(), false);
        try {
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    // 26.1: getPixel/setPixel are ARGB (0xAARRGGBB). The gray result is channel-
                    // symmetric so the packing is unchanged; only the channel reads differ.
                    int pixel = source.getPixel(x, y);
                    int r = (pixel >> 16) & 0xFF;
                    int g = (pixel >>  8) & 0xFF;
                    int b =  pixel        & 0xFF;
                    int a = (pixel >> 24) & 0xFF;
                    int lum = (r + g + b) / 3;
                    gray.setPixel(x, y, (a << 24) | (lum << 16) | (lum << 8) | lum);
                }
            }
        } finally {
            source.close();
        }

        String safePath = design.getNamespace() + "/" + design.getPath().replace('/', '_').replace('.', '_');
        Identifier loc = CustomGlint.res("glint/" + safePath);
        // 26.1: DynamicTexture needs a label supplier; wrap/filter (REPEAT + NEAREST) is no longer set
        // on the texture — GlintPipelines.glintSampler() supplies it per binding.
        DynamicTexture dt = new DynamicTexture(() -> MOD_ID + ":glint/" + safePath, gray);
        mc.getTextureManager().register(loc, dt);
        return loc;
    }

    // ── Render types ──────────────────────────────────────────────────────────

    /** Assigned by RenderBuffersMixin on RenderBuffers construction; null until then. */
    public static SequencedMap<RenderType, ByteBufferBuilder> fixedBufferRegistry;
    public static final ThreadLocal<float[]> COLOR_BUF = ThreadLocal.withInitial(() -> new float[4]);

    private static final Map<String, RenderType> BY_GLINT              = new HashMap<>();
    private static final Map<String, RenderType> BY_ARMOR_GLINT        = new HashMap<>();
    private static final Map<String, RenderType> BY_HORSE_ARMOR_GLINT  = new HashMap<>();

    /**
     * Registers {@code rt}'s persistent buffer into the live BufferSource's {@code fixedBuffers}
     * when absent. Iris and Sodium swap the vanilla BufferSource for one whose {@code fixedBuffers}
     * is an IMMUTABLE fastutil map — its {@code put} throws {@link UnsupportedOperationException}
     * (the inline {@code live.put(...)} this replaced crashed armor/entity/item/outline glint the
     * instant Iris was installed: {@code Object2ObjectFunction.put} → UOE). Swallow that: under an
     * active shader pack our RTs render through the forward/shader path, which never pulls from this
     * BufferSource's fixed buffers, so the registration isn't needed there anyway. The vanilla
     * {@code fixedBufferRegistry} (captured in RenderBuffersMixin) still holds the RT for the
     * no-shader path.
     */
    /** Registers an RT's persistent buffer into the captured fixed-buffer registry (no-shader path). */
    private static void registerFixed(RenderType rt) {
        if (rt != null && fixedBufferRegistry != null && !fixedBufferRegistry.containsKey(rt))
            fixedBufferRegistry.put(rt, new ByteBufferBuilder(rt.bufferSize()));
    }

    public static void registerLiveFixedBuffer(RenderType rt) {
        if (rt == null) return;
        SequencedMap<RenderType, ByteBufferBuilder> live =
                Minecraft.getInstance().renderBuffers().bufferSource().fixedBuffers;
        if (live == null || live.containsKey(rt)) return;
        try {
            live.put(rt, new ByteBufferBuilder(rt.bufferSize()));
        } catch (UnsupportedOperationException ignored) {
            // immutable fixedBuffers (Iris/Sodium) — forward/shader path handles these RTs
        }
    }

    // Armor glint matches armor_cutout_no_cull's VIEW_OFFSET_Z layering (EQUAL depth, D-ε) so the
    // glint depth-test lands exactly on the armor depth. See the project_armor_glint_bleed_fix memory.
    public static RenderType forArmorGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        Identifier gray = getTexture(layer.design());
        if (gray == null) return null;
        final int cc = layer.colors().length;
        final double speed = layer.speed();
        final float ps = layer.patternScale();
        String key = "armor|" + layer.design() + "|" + speed + "|" + ps + "|" + colorIdx + "|" + cc;
        RenderType cached = BY_ARMOR_GLINT.computeIfAbsent(key, k -> {
            RenderType rt = GlintPipelines.glintType(MOD_ID + ":custom_armor_glint|" + k.hashCode(), gray,
                    LayeringTransform.VIEW_OFFSET_Z_LAYERING,
                    () -> GlintPipelines.animationMatrix(speed, ps, colorIdx, cc));
            registerFixed(rt);
            return rt;
        });
        registerLiveFixedBuffer(cached);
        return cached;
    }

    /**
     * Entity body glint (pigs, cows, zombies, … anything rendered through entityCutoutNoCull).
     * Aliases {@link #forHorseArmorGlint}: same render-state requirements (EQUAL depth + NO_LAYERING),
     * since LivingEntityRenderer body draws have no polygon offset. Separate method only for caller clarity.
     */
    public static RenderType forEntityGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        return forHorseArmorGlint(glint, layerIdx, frameColor, colorIdx);
    }

    // Horse armor uses entityCutoutNoCull (no polygon offset / no VIEW_OFFSET_Z_LAYERING).
    // forArmorGlint uses EQUAL + VIEW_OFFSET_Z_LAYERING — wrong offset → invisible on horses.
    // This variant keeps EQUAL + NO_LAYERING so depth matches, and scale 1.0 matches forArmorGlint visually.
    public static RenderType forHorseArmorGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        Identifier gray = getTexture(layer.design());
        if (gray == null) return null;
        final int cc = layer.colors().length;
        final double speed = layer.speed();
        final float ps = layer.patternScale();
        String key = "horse|" + layer.design() + "|" + speed + "|" + ps + "|" + colorIdx + "|" + layerIdx + "|" + cc;
        RenderType cached = BY_HORSE_ARMOR_GLINT.computeIfAbsent(key, k -> {
            RenderType rt = GlintPipelines.glintType(MOD_ID + ":custom_horse_armor_glint|" + k.hashCode(), gray,
                    LayeringTransform.NO_LAYERING,
                    () -> GlintPipelines.animationMatrix(speed, ps, colorIdx, cc));
            registerFixed(rt);
            return rt;
        });
        registerLiveFixedBuffer(cached);
        return cached;
    }

    public static RenderType forGlint(Data glint, int layerIdx, float[] frameColor, boolean isItem, int colorIdx) {
        // isItem=true → flat item model → atlas-calibrated 8× scale (matches vanilla glint()).
        // isItem=false → 3D entity model (trident, etc.) → 1.0 for visible pattern detail.
        // 26.1: ModelManager.getAtlas is gone (atlas lives on the private AtlasManager). The block
        // atlas is registered in TextureManager under LOCATION_BLOCKS; read its dims there.
        // 26.1 the block atlas is 2048×2048 (was 1024×512 in the 1.20.1 calibration era). The glint
        // pattern density per sprite only depends on scale/atlasSize, so anchor both axes to the
        // current atlas: scale = 8 when the atlas matches its dimension. This keeps the dev's known-
        // good 8/8 and stays square (undistorted) on any atlas size, instead of the old 1024/512
        // denominators that ballooned to 16/32 on the 2048² atlas (the V axis read 4× too dense).
        int atlasW = 2048, atlasH = 2048;
        if (Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS)
                instanceof TextureAtlas atlas) {
            atlasW = atlas.width;
            atlasH = atlas.height;
        }
        final float scaleU = isItem ? (8.0f * atlasW / 2048.0f) : 1.0f;
        final float scaleV = isItem ? (8.0f * atlasH / 2048.0f) : 1.0f;
        Layer layer = glint.layers()[layerIdx];
        Identifier gray = getTexture(layer.design());
        if (gray == null) return null;
        final int cc = layer.colors().length;
        final double speed = layer.speed();
        final float ps = layer.patternScale();
        String key = layer.design() + "|" + speed + "|" + isItem + "|" + ps + "|" + colorIdx + "|" + layerIdx + "|" + cc;
        RenderType cached = BY_GLINT.computeIfAbsent(key, k -> {
            RenderType rt = GlintPipelines.glintType(MOD_ID + ":custom_glint|" + k.hashCode(), gray,
                    LayeringTransform.NO_LAYERING,
                    () -> GlintPipelines.itemAnimationMatrix(speed, scaleU, scaleV, ps, colorIdx, cc));
            registerFixed(rt);
            return rt;
        });
        registerLiveFixedBuffer(cached);
        return cached;
    }

    public static int computeAnimatedColor(Data glint, int layerIdx) {
        Layer layer = glint.layers()[layerIdx];
        int[] colors = layer.colors();
        if (colors.length == 1) return colors[0];
        Minecraft mc = Minecraft.getInstance();
        long gameTime = mc.level != null ? mc.level.getGameTime() : 0;
        float totalTicks = (20.0f * colors.length) / layer.speed();
        float t = (gameTime % Math.max(1L, (long) totalTicks)) / totalTicks * colors.length;
        int idx = (int) t % colors.length;
        if (!layer.interpolate()) return colors[idx];
        float frac = t - (int) t;
        int c1 = colors[idx], c2 = colors[(idx + 1) % colors.length];
        int a = (int)(((c1 >> 24) & 0xFF) * (1 - frac) + ((c2 >> 24) & 0xFF) * frac);
        int r = (int)(((c1 >> 16) & 0xFF) * (1 - frac) + ((c2 >> 16) & 0xFF) * frac);
        int g = (int)(((c1 >>  8) & 0xFF) * (1 - frac) + ((c2 >>  8) & 0xFF) * frac);
        int b = (int)((c1         & 0xFF) * (1 - frac) + (c2         & 0xFF) * frac);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Animates through an int[] color array using game time. Default speed=1, interpolate=true. */
    public static int computeAnimatedGlowColor(int[] colors) {
        if (colors.length == 0) return 0xFFFFFFFF;
        if (colors.length == 1) return colors[0];
        Minecraft mc = Minecraft.getInstance();
        long gameTime = mc.level != null ? mc.level.getGameTime() : 0;
        float totalTicks = 20.0f * colors.length;
        float t = (gameTime % Math.max(1L, (long) totalTicks)) / totalTicks * colors.length;
        int idx = (int) t % colors.length;
        float frac = t - (int) t;
        int c1 = colors[idx], c2 = colors[(idx + 1) % colors.length];
        int a = (int)(((c1 >> 24) & 0xFF) * (1 - frac) + ((c2 >> 24) & 0xFF) * frac);
        int r = (int)(((c1 >> 16) & 0xFF) * (1 - frac) + ((c2 >> 16) & 0xFF) * frac);
        int g = (int)(((c1 >>  8) & 0xFF) * (1 - frac) + ((c2 >>  8) & 0xFF) * frac);
        int b = (int)((c1         & 0xFF) * (1 - frac) + (c2         & 0xFF) * frac);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Outline color for an item: prefers Glow Trim colors, falls back to glint layer 0, else white. */
    public static int outlineColor(ItemStack stack) {
        int[] glow = CustomGlint.getGlowColors(stack);
        if (glow.length > 0) return computeAnimatedGlowColor(glow);
        Data glint = CustomGlint.read(stack);
        if (glint != null) return computeAnimatedColor(glint, 0);
        return 0xFFFFFFFF;
    }

    /** Reload hooks appended by compat modules; invoked by {@link #clearTextures()} so each
     *  compat can release its own {@code DynamicTexture}s without {@code CustomGlintRenderer}
     *  needing to know about them. */
    public static final List<Runnable> additionalReloadCleanup = new CopyOnWriteArrayList<>();

    private static String texTag(Identifier t) {
        return t.getNamespace() + "_" + t.getPath().replace('/', '_');
    }

    /**
     * Shared base for the mod's wrapping {@link VertexConsumer}s. 26.1.2 added two abstract methods to
     * the interface that every wrapper would otherwise have to implement: the single-arg
     * {@code setColor(int packedARGB)} and {@code setLineWidth(float)}. This base implements both once
     * — {@code setColor(int)} unpacks to the subclass's own {@code setColor(r,g,b,a)} (so each
     * wrapper's color policy — no-op, force-override, pass-through — is honored), and
     * {@code setLineWidth} is a no-op (these wrappers never drive line geometry).
     */
    private abstract static class WrappingConsumer implements VertexConsumer {
        @Override public VertexConsumer setColor(int argb) {
            return setColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
        }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }

    /** Returns the current animated color (ARGB) from layer 0 of the glint, for use as outline color. */
    public static int glintOutlineColor(Data glint) {
        return computeAnimatedColor(glint, 0);
    }

    /**
     * Stack-aware outline color: prefers Glow Trim colors (glowColors NBT), falls back to glint
     * layer 0, else returns white. Use this for any outline rendered for an ItemStack so glow-only
     * items (with no glint Data) get the correct animated color.
     */
    public static int glintOutlineColor(ItemStack stack) {
        return outlineColor(stack);
    }

    /** Wraps a VertexConsumer and overrides vertex colors with a fixed RGBA value, forwarding
     *  uv/overlay/uv2/normal to the wrapped buffer. Forwarding those is required when the underlying
     *  buffer uses a full entity vertex format (POSITION_COLOR_TEX_OVERLAY_LIGHTMAP_NORMAL) — dropping
     *  them leaves the BufferBuilder with unfilled elements and crashes on endVertex. This is the color
     *  injector for the glint layers (ItemRendererMixin) and the glow-mask silhouettes. */
    public static final class FullColorOverrideConsumer extends WrappingConsumer {
        private final VertexConsumer wrapped;
        private final int r, g, b, a;
        public FullColorOverrideConsumer(VertexConsumer wrapped, int r, int g, int b, int a) {
            this.wrapped = wrapped; this.r = r; this.g = g; this.b = b; this.a = a;
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) { wrapped.addVertex(x, y, z); return this; }
        @Override public VertexConsumer addVertex(Matrix4fc m, float x, float y, float z) { wrapped.addVertex(m, x, y, z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return wrapped.setColor(this.r, this.g, this.b, this.a); }
        @Override public VertexConsumer setColor(float r, float g, float b, float a) { return wrapped.setColor(this.r, this.g, this.b, this.a); }
        @Override public VertexConsumer setUv(float u, float v) { return wrapped.setUv(u, v); }
        @Override public VertexConsumer setUv1(int u, int v) { return wrapped.setUv1(u, v); }
        @Override public VertexConsumer setUv2(int u, int v) { return wrapped.setUv2(u, v); }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return wrapped.setNormal(x, y, z); }
    }

    /** Wraps a VertexConsumer and records each vertex's eye-space position into a shared
     *  {minX,minY,minZ,maxX,maxY,maxZ} accumulator. Used during the outline stencil pass to
     *  derive an AABB-centered pivot for the dilation scale. */
    public static final class AABBTrackingConsumer extends WrappingConsumer {
        private final VertexConsumer wrapped;
        private final float[] minMax;
        public AABBTrackingConsumer(VertexConsumer wrapped, float[] minMax) {
            this.wrapped = wrapped; this.minMax = minMax;
        }
        private void track(float x, float y, float z) {
            if (x < minMax[0]) minMax[0] = x;
            if (y < minMax[1]) minMax[1] = y;
            if (z < minMax[2]) minMax[2] = z;
            if (x > minMax[3]) minMax[3] = x;
            if (y > minMax[4]) minMax[4] = y;
            if (z > minMax[5]) minMax[5] = z;
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            track((float) x, (float) y, (float) z);
            wrapped.addVertex(x, y, z);
            return this;
        }
        @Override public VertexConsumer addVertex(Matrix4fc m, float x, float y, float z) {
            float tx = m.m00() * x + m.m10() * y + m.m20() * z + m.m30();
            float ty = m.m01() * x + m.m11() * y + m.m21() * z + m.m31();
            float tz = m.m02() * x + m.m12() * y + m.m22() * z + m.m32();
            track(tx, ty, tz);
            wrapped.addVertex(m, x, y, z);
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return wrapped.setColor(r, g, b, a); }
        @Override public VertexConsumer setColor(float r, float g, float b, float a) { return wrapped.setColor(r, g, b, a); }
        @Override public VertexConsumer setUv(float u, float v) { return wrapped.setUv(u, v); }
        @Override public VertexConsumer setUv1(int u, int v) { return wrapped.setUv1(u, v); }
        @Override public VertexConsumer setUv2(int u, int v) { return wrapped.setUv2(u, v); }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return wrapped.setNormal(x, y, z); }
    }

    private CustomGlintRenderer() {}

    // ── Shader-mod detection ──────────────────────────────────────────────────
    // Reflective so we don't need a compileOnly dep on the shader mod. Common shader
    // mods expose the same public detection surface, resolved once and cached;
    // called every outline draw, so the Method is cached as a MethodHandle.
    //
    // Why this matters here: the shader mod's ShaderKey enum (the master list of every
    // RenderType→shader-program mapping under a loaded pack) has no `OUTLINE` entry.
    // That means draws using RENDERTYPE_OUTLINE_SHADER fall through to no destination
    // program under shaders — the stencil setup runs, but the visible color attachment
    // never receives our outline geometry. When a pack is loaded, we route the dilated
    // outline through vanilla's OutlineBufferSource instead — that pipeline (entity
    // outline target + EntityOutlineShader post-process composite) is one the shader
    // mod explicitly preserves to keep vanilla glowing working under shaders.
    private static volatile boolean SHADER_LOOKUP_DONE = false;
    private static volatile Method SHADER_GET_INSTANCE = null;
    private static volatile Method SHADER_IS_IN_USE = null;

    public static boolean isShaderPackActive() {
        if (!SHADER_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!SHADER_LOOKUP_DONE) {
                    try {
                        // Package path applies on Forge as well — do not change it.
                        Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                        SHADER_GET_INSTANCE = api.getMethod("getInstance");
                        SHADER_IS_IN_USE = api.getMethod("isShaderPackInUse");
                    } catch (Throwable ignored) {
                        SHADER_GET_INSTANCE = null;
                        SHADER_IS_IN_USE = null;
                    }
                    SHADER_LOOKUP_DONE = true;
                }
            }
        }
        if (SHADER_IS_IN_USE == null) return false;
        try {
            Object inst = SHADER_GET_INSTANCE.invoke(null);
            return (Boolean) SHADER_IS_IN_USE.invoke(inst);
        } catch (Throwable t) {
            return false;
        }
    }

}
