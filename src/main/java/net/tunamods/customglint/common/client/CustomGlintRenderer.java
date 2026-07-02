package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderPass;
import javax.annotation.Nullable;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.util.Util;
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
import net.tunamods.customglint.common.CustomGlint;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SequencedMap;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static net.tunamods.customglint.CustomGlintMod.MOD_ID;

import net.tunamods.customglint.common.CustomGlint.Data;
import net.tunamods.customglint.common.CustomGlint.Layer;

/**
 * Client-only rendering backend. Split out of {@link CustomGlint} so that the data-API class
 * remains loadable on dedicated servers (where the render classes are absent). Mods bundling the
 * api jar should call render-pipeline methods through this class; NBT/data API stays on
 * {@link CustomGlint}.
 *
 * <p>On 26.1 the GPU state is built on {@link GlintPipelines} (immutable RenderPipeline /
 * RenderSetup) instead of the 1.20-era {@code RenderStateShard}/{@code CompositeState}
 * model. Per-RenderType color rides the vertex color (callers inject it via a color-overriding
 * VertexConsumer); animation rides a {@link net.minecraft.client.renderer.rendertype.TextureTransform}
 * supplier.
 */
public final class CustomGlintRenderer {

    // ── Texture cache ─────────────────────────────────────────────────────────

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Identifier, Identifier> textureCache = new HashMap<>();
    /** Procedural-chromatic palette strips (1px-tall RGBA), keyed by the color-array CONTENTS (see
     *  {@link #colorsKey}). Keying on {@code Arrays.hashCode} alone collides, distinct equal-length color
     *  arrays can hash equal (the 31*r+e formula), which would silently hand a trim the wrong cached strip.
     *  Access-order LRU capped like the RenderType caches: one tiny DynamicTexture is registered per distinct
     *  color-array ever seen, and without a bound they accumulate for the whole session (freed only on resource
     *  reload). The eldest's texture is released on eviction; the cap sits far above any frame's distinct count. */
    private static final int PALETTE_CACHE_CAP = 256;
    private static final Map<String, Identifier> paletteCache =
        new LinkedHashMap<>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Identifier> eldest) {
                if (size() <= PALETTE_CACHE_CAP) return false;
                Identifier loc = eldest.getValue();
                if (loc != null) {
                    try { Minecraft.getInstance().getTextureManager().release(loc); } catch (Throwable ignored) {}
                }
                return true;
            }
        };
    /** 1×1 opaque-white dummy bound to the chromatic pipeline's unused Sampler0 (lazily built). */
    private static Identifier whiteTexture;
    /** Cached block-atlas dimensions feeding the item-glint scale. The atlas size only changes on resource
     *  reload, so resolving it once (vs every {@link #forGlint} call) avoids a per-item-draw TextureManager
     *  lookup + instanceof. 0 = unresolved; reset in {@link #clearTextures}. */
    private static int cachedAtlasW = 0, cachedAtlasH = 0;
    /** GUI scale (clamped 1..127) cached once per frame by {@link #refreshFrameGuiScale()}. The GUI glint/glow
     *  overlay packs this into a colour's alpha byte for every glinted icon; it can't change mid-frame, so the
     *  per-icon Window lookup it replaces was redundant on the full-creative-tab path. */
    private static int frameGuiScale = 1;

    /** Refreshes {@link #frameGuiScale}; called once per rendered frame from the RenderFrameEvent.Pre hook. */
    public static void refreshFrameGuiScale() {
        frameGuiScale = Math.max(1, Math.min(127, (int) Minecraft.getInstance().getWindow().getGuiScale()));
    }

    /** The GUI scale (1..127) cached at the start of the current frame. */
    public static int frameGuiScale() { return frameGuiScale; }

    /** Returns the cached grayscale design texture, building it on first request. A missing/unreadable design
     *  caches {@code null} on purpose: {@link #generateTexture} does file I/O + an image pass, so retrying it
     *  every frame for an absent design would stall the GUI. The null entry is cleared on the next resource
     *  reload ({@link #clearTextures}), so a design that appears later recovers. Callers MUST null-check both
     *  this result AND the subsequent {@code TextureManager.getTexture} before dereferencing. */
    public static Identifier getTexture(Identifier design) {
        if (textureCache.containsKey(design)) return textureCache.get(design);
        Identifier result = generateTexture(design);
        textureCache.put(design, result);
        return result;
    }

    /** Removes each RenderType from {@code fixedBufferRegistry} and closes its native {@link ByteBufferBuilder}. */
    private static void cg_dropFixed(Iterable<RenderType> rts) {
        for (RenderType rt : rts) {
            ByteBufferBuilder b = fixedBufferRegistry.remove(rt);
            if (b != null) { try { b.close(); } catch (Throwable ignored) {} }
        }
    }

    /** Hard cap on each per-config glint RenderType cache. The keys embed continuous params (speed, pattern
     *  scale, scroll offset) and the per-trim chromatic seed, so without a bound these grow once per distinct
     *  trim configuration ever seen this session and leak a native {@link ByteBufferBuilder} apiece (only
     *  freed on resource reload). An access-order LRU keeps the live working set and frees the rest. The cap
     *  sits far above any single frame's distinct-config count, so in-use entries are never evicted mid-frame. */
    private static final int RT_CACHE_CAP = 256;

    /** Builds an access-order LRU RenderType cache that closes the evicted RenderType's native buffer(s). */
    private static <K> Map<K, RenderType> newRtCache() {
        return new LinkedHashMap<>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, RenderType> eldest) {
                if (size() <= RT_CACHE_CAP) return false;
                cg_evictRenderType(eldest.getValue());
                return true;
            }
        };
    }

    /** Removes {@code rt} from both the captured registry and the live BufferSource's fixedBuffers, closing
     *  each native {@link ByteBufferBuilder}. Mirrors {@link #cg_dropFixed} but also covers the live map, which
     *  Iris/Sodium may swap for an immutable one (its {@code remove} throws, swallowed). The RT re-mints
     *  lazily on its next access. */
    private static void cg_evictRenderType(RenderType rt) {
        if (rt == null) return;
        if (fixedBufferRegistry != null) {
            ByteBufferBuilder b = fixedBufferRegistry.remove(rt);
            if (b != null) { try { b.close(); } catch (Throwable ignored) {} }
        }
        try {
            SequencedMap<RenderType, ByteBufferBuilder> live =
                    Minecraft.getInstance().renderBuffers().bufferSource().fixedBuffers;
            if (live != null && live != fixedBufferRegistry) {
                ByteBufferBuilder lb = live.remove(rt);
                if (lb != null) { try { lb.close(); } catch (Throwable ignored) {} }
            }
        } catch (Throwable ignored) {}
    }

    public static void clearTextures() {
        Minecraft mc = Minecraft.getInstance();
        cachedAtlasW = cachedAtlasH = 0; // atlas may resize on reload, re-resolve lazily in forGlint
        for (Identifier loc : textureCache.values())
            if (loc != null) mc.getTextureManager().release(loc);
        textureCache.clear();
        // The stitched GUI design atlas is a DynamicTexture too; release it and force a lazy rebuild so a
        // resource reload (or a data pack that adds designs) re-stitches the current design set.
        invalidateGuiDesignAtlas();
        // Chromatic palette strips + the white dummy are DynamicTextures too, release and rebuild on reload.
        for (Identifier loc : paletteCache.values())
            if (loc != null) mc.getTextureManager().release(loc);
        paletteCache.clear();
        if (whiteTexture != null) { mc.getTextureManager().release(whiteTexture); whiteTexture = null; }
        // Drop the borrowed scene-depth holder's TextureManager registration so it doesn't dangle across a
        // reload; bindSceneDepth re-registers a fresh holder on the next frame. (The view itself is owned by
        // the main render target, releasing here only removes our registration, never frees the view.)
        if (sceneDepthTex != null) { mc.getTextureManager().release(SCENE_DEPTH_ID); sceneDepthTex = null; }
        // The entity-surface RenderType identity cache keys on interned singletons; clear it so a pack/dimension
        // swap that re-mints those RenderTypes doesn't keep stale identities alive.
        EntityGlintRender.clearSurfaceCache();
        // Free the glow/chromatic composite RenderTargets' GPU textures, they're otherwise never closed and
        // dangle across reloads; rebuilt lazily on the next drain.
        EntityGlintRender.releaseTargets();
        for (Runnable r : additionalReloadCleanup) {
            try { r.run(); } catch (Throwable t) {
                LOGGER.warn("[{}/CustomGlint] additional reload cleanup threw", MOD_ID, t);
            }
        }
        if (fixedBufferRegistry != null) {
            // Close each removed builder, its ByteBufferBuilders hold raw native allocations with no Cleaner,
            // so remove() alone leaks them every reload (mirrors the GLOW_BODY_FIXED close loop below). They're
            // re-minted lazily on the next draw. (fixedBufferRegistry is the live bufferSource.fixedBuffers in
            // the vanilla path; under an Iris/Sodium-swapped source it can be a different instance, which
            // cg_evictRenderType / registerLiveFixedBuffer handle separately.)
            cg_dropFixed(BY_GLINT.values());
            cg_dropFixed(BY_ARMOR_GLINT.values());
            cg_dropFixed(BY_ENTITY_BODY_GLINT.values());
            cg_dropFixed(BY_BLOCK_GLINT.values());
            cg_dropFixed(BY_CHROMATIC.values());
            cg_dropFixed(BY_CHROMATIC_OVERLAY.values());
            cg_dropFixed(BY_GLOW_MASK.values());
        }
        BY_GLINT.clear();
        BY_ARMOR_GLINT.clear();
        BY_ENTITY_BODY_GLINT.clear();
        BY_BLOCK_GLINT.clear();
        BY_CHROMATIC.clear();
        BY_CHROMATIC_OVERLAY.clear();
        BY_GLOW_MASK.clear();
        // Free the in-phase glow-body native buffers. Their keys are RenderTypes that the cleared
        // BY_GLOW_MASK maps just dropped, so after a reload glowMaskRT mints fresh RenderType instances
        // and computeIfAbsent would orphan these builders, a slow per-reload native-memory leak. Close
        // and drop them so the next frame rebuilds against the new RenderTypes.
        for (ByteBufferBuilder b : GLOW_BODY_FIXED.values()) {
            try { b.close(); } catch (Throwable ignored) {}
        }
        GLOW_BODY_FIXED.clear();
        // The shared fallback builder lives inside glowBodyBuffer, not in GLOW_BODY_FIXED, so the loop
        // above never closed it, close it here so it doesn't leak one native allocation per reload.
        if (glowBodySpare != null) {
            try { glowBodySpare.close(); } catch (Throwable ignored) {}
            glowBodySpare = null;
        }
        glowBodyBuffer = null;
        // TRIED (did not fix): after Iris/Oculus pack ON→OFF toggle, items in item frames develop
        // a "lens through walls" effect, looking through one outlined item shows other outlined
        // items behind walls. Hypothesis was that Iris's pipeline-destroy path leaves vanilla's
        // RenderTarget.useStencil = false even though the depth-stencil attachment is still there
        // (forced by iris's MixinRenderTarget_StencilBufferTest), making glStencilFunc undefined.
        // Tried: `Minecraft.getInstance().getMainRenderTarget().enableStencil()` here in
        // clearTextures (fires on resource reload = shader toggle) AND every frame in
        // CustomGlintClientInit's RenderTickEvent.START. Both no-ops in practice, symptom
        // persisted. Stencil-flag is NOT the cause. Real cause unknown; do not re-attempt this
        // angle without new evidence.
    }

    // ── Isolated glow-outline (silhouette target + composite) ────────────────────────────────────
    // The 26.1 replacement for the entity-body stencil ring the 1.20.1/1.21.1 builds used (confirmed
    // dead end, the dilated band's depth flickered at silhouette edges). Orchestrated by
    // EntityGlintRender.drainBodyOutlines at RenderLevelStageEvent.AfterWeather (off-shader) /
    // LevelRendererMixin renderLevel TAIL (under an active Iris pack).

    private static final Map<Identifier, RenderType> BY_GLOW_MASK = new HashMap<>();

    /** Identifier the combined-mask RenderType binds for the scene-depth sampler. Resolved through
     *  TextureManager to {@link #sceneDepthTex}, a holder whose view is re-pointed at the live main-target
     *  depth each frame by {@link #bindSceneDepth}. The mask shader (core/glow_silhouette) samples this to
     *  decide per-fragment occlusion, which is why no separate depth-downsample pass is needed any more. */
    public static final Identifier SCENE_DEPTH_ID = CustomGlint.res("scene_depth");

    /** Per-texture combined-mask RenderType (GLOW_MASK_PIPE: ALWAYS_PASS depth, shape + per-fragment
     *  visibility encoded in alpha by core/glow_silhouette). One render per mob writes everything the
     *  composite needs, no separate shape pass. The draw is redirected to the half-res mask target by
     *  the caller via RenderSystem.outputColor/DepthTextureOverride. */
    public static RenderType glowMaskRT(Identifier texture) {
        RenderType rt = BY_GLOW_MASK.computeIfAbsent(texture, t ->
                GlintPipelines.glowMaskType(MOD_ID + ":glow_mask_" + texTag(t), t, SCENE_DEPTH_ID));
        registerFixed(rt);
        registerLiveFixedBuffer(rt);
        return rt;
    }

    /** Accumulates one {@link Model} into the combined-mask buffer with ONE model render and ONE vertex emit
     *  (the old path rendered the model into TWO fanned buffers, a full-shape pass and a visible pass,
     *  doubling the per-mob CPU emit and the GPU silhouette fill). Occlusion is now decided per-fragment in the
     *  shader against the bound scene depth, so a single pass carries both. Nothing draws yet: every model
     *  sharing a texture piles into the same fixed buffer, and one {@link #flushGlowRT} per texture then draws
     *  them all (1×textures draws, not 2×models). Entity BODIES are captured in-phase by {@link #fanBodyGlow};
     *  this method's caller is the worn-armor loop ({@code EntityGlintRender.drainBodyOutlines}), which has no
     *  in-phase hook. */
    public static void accumulateGlowMask(PoseStack pose, Model<?> model,
            RenderType maskRT, int packedLight, int color, int glowKey) {
        if (model == null || maskRT == null) return;
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        MultiBufferSource.BufferSource bs = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer c = new AABBTrackingConsumer(
                new FullColorOverrideConsumer(bs.getBuffer(maskRT), r, g, b, glowKey), glowMaskBox);
        model.renderToBuffer(pose, c, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
    }

    // ── In-phase entity-body glow capture (option A, vanilla-parity) ──────────────────────────────
    // Vanilla's glowing-entity outline tees a SECOND renderToBuffer into a shared OutlineBufferSource
    // right after the body draw, while the model is still posed + setupAnim'd (ModelFeatureRenderer.
    // renderModel). No second setupAnim, no per-entity pose alloc, no deferred re-walk. We mirror that:
    // ModelFeatureRendererMixin calls fanBodyGlow at renderModel TAIL, capturing the posed silhouette
    // into our OWN buffer source (so the main BufferSource's endBatch at the end of renderSolidFeatures
    // never flushes it into the main target), then drainBodyOutlines flushes it into the mask + composites.
    //
    // Its fixed-buffer map is private to this source (NOT the shared fixedBufferRegistry), so getBuffer
    // never auto-flushes on a texture switch and the main endBatch can't touch it.
    private static final java.util.SequencedMap<RenderType, ByteBufferBuilder> GLOW_BODY_FIXED = new java.util.LinkedHashMap<>();
    private static MultiBufferSource.BufferSource glowBodyBuffer;
    // The shared fallback builder handed to immediateWithBuffers below. It lives inside glowBodyBuffer (not
    // in GLOW_BODY_FIXED), so the GLOW_BODY_FIXED close loop in clearTextures() never reaches it, keep a
    // direct handle so it can be closed on reload instead of leaking one native allocation per reload.
    private static ByteBufferBuilder glowBodySpare;
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
    /** Outline category, top 2 bits of the mask key, indexes THICKNESS[] in post/glow_outline_id.
     *  CAT_ITEM = 3rd-person held + dropped; CAT_HELD_FP = first-person held. */
    public static final int CAT_ENTITY = 0, CAT_ARMOR = 1, CAT_ITEM = 2, CAT_HELD_FP = 3;
    /** Running per-object id, 1..31 (wraps). 5 bits, leaving 2 for the category in the 7-bit mask key. */
    public static int nextGlowId() { glowIdCounter = (glowIdCounter % 31) + 1; return glowIdCounter; }
    /** Mask key = (category << 5) | id (1..127). The composite separates objects by key and reads the
     *  per-category outline thickness from the category bits. */
    public static int nextGlowKey(int category) { return (category << 5) | nextGlowId(); }

    /** One id per outline IDENTITY, shared by every silhouette that belongs to it, an entity's body and
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
     * buffer AND our glow-mask buffer, the silhouette is captured for the cost of a few extra vertex
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
     *, and it sidesteps recovering each layer's bound texture from the immutable RenderType. */
    private static final Identifier WHITE_SILHOUETTE = Identifier.fromNamespaceAndPath("neoforge", "textures/white.png");

    /**
     * Fans a glowing entity's secondary-model draw (a {@code RenderLayer} surface, wool, slime outer
     * cube, saddle, stray clothing, …) into the SAME glow mask the body uses, sharing the entity's outline
     * id ({@link #glowKeyFor}, CAT_ENTITY) so the whole mob composites as ONE ring with no doubled seam.
     * Like {@link #fanBodyGlow} this is in-phase (no second model walk, no second setupAnim), the layer is
     * already posed when {@code ModelFeatureRenderer.renderModel} draws it. Uses white.png (full geometry)
     * so the silhouette follows the layer's real shape, and does NOT touch the entity cap / loose bbox
     * bookkeeping, the body already accounts the entity; only the tight silhouette box is extended so the
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
            glowBodySpare = new ByteBufferBuilder(256);
            glowBodyBuffer = MultiBufferSource.immediateWithBuffers(GLOW_BODY_FIXED, glowBodySpare);
        }
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        // Tight per-object silhouette AABB, filled as the model draws through the returned consumer (added to
        // bgTightBoxes by reference now; populated by drain time). Lets the drain scissor the composite to
        // the real silhouette, body AND any layer that extends past it, not the loose pose-origin box.
        float[] tight = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        VertexConsumer mask = new AABBTrackingConsumer(new FullColorOverrideConsumer(
                glowBodyBuffer.getBuffer(rt), r, g, b, glowKeyFor(entityState, CAT_ENTITY)), tight);
        bgTightBoxes.add(tight);
        if (isBody) {
            // Generous camera-relative box (see EntityGlintRender.computeGroupScissor for the radius
            // rationale). Layers reuse the body's loose box (same entity), only the tight box above grows.
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
     *  null if none were captured, used to scissor a glowing entity's composite to its actual size. */
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

    // Tight per-group silhouette AABB (camera-relative world space, the ACTUAL posed geometry captured by
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
     *  submit model parts (e.g. trident, shaft + prongs, several {@code submitModelPart} calls). Same
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
     *  Sampler0 alpha-discard follows the real item shape, flat sprites trace the sprite, 3D models trace
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
        // The wrappers are quad-invariant (same r/g/b/gid + shared glowMaskBox); only the underlying buffer
        // changes per atlas. Build one wrapper per distinct atlas instead of one per quad, a modeled item
        // (crossbow, banner) has dozens of quads but usually a single atlas, so this drops the per-frame
        // allocation from O(quads) to O(distinct atlases).
        Map<Identifier, VertexConsumer> byAtlas = new HashMap<>();
        for (BakedQuad quad : quads) {
            Identifier tex = quad.materialInfo().sprite().atlasLocation();
            VertexConsumer c = byAtlas.get(tex);
            if (c == null) {
                c = new AABBTrackingConsumer(
                        new FullColorOverrideConsumer(bs.getBuffer(glowMaskRT(tex)), r, g, b, gid), glowMaskBox);
                byAtlas.put(tex, c);
                texturesOut.add(tex);
            }
            c.putBakedQuad(pose, quad, qi);
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
     *  view is owned by the main render target, close() must NOT free it. */
    private static final class SceneDepthTexture extends AbstractTexture {
        GpuTextureView view;
        @Override public GpuTextureView getTextureView() { return view; }
        @Override public void close() { /* borrowed view, owned by the main render target, never freed here */ }
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
        NativeImage gray = loadGrayscale(design);
        if (gray == null) return null;
        String safePath = design.getNamespace() + "/" + design.getPath().replace('/', '_').replace('.', '_');
        Identifier loc = CustomGlint.res("glint/" + safePath);
        // 26.1: DynamicTexture needs a label supplier; wrap/filter (REPEAT + NEAREST) is no longer set
        // on the texture, GlintPipelines.glintSampler() supplies it per binding.
        DynamicTexture dt = new DynamicTexture(() -> MOD_ID + ":glint/" + safePath, gray);
        mc.getTextureManager().register(loc, dt);
        return loc;
    }

    /** Reads a design PNG and converts it to a fresh grayscale {@link NativeImage} (the caller owns and must
     *  close it). Returns null when the design is absent/unreadable. Shared by {@link #generateTexture} (one
     *  texture per design, the world path) and {@link #ensureGuiDesignAtlas} (all designs stitched into one
     *  GUI atlas so the inventory glint overlays batch). */
    private static NativeImage loadGrayscale(Identifier design) {
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
        // Allocate `gray` and run the copy INSIDE the try whose finally closes `source`: the
        // `new NativeImage(...)` can itself throw (native allocation failure), and if it sat outside
        // the try the already-read `source` would leak its native buffer on every retry. If the copy
        // throws after gray is allocated, close gray too before propagating.
        NativeImage gray = null;
        try {
            gray = new NativeImage(source.getWidth(), source.getHeight(), false);
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
        } catch (Throwable t) {
            if (gray != null) gray.close();
            throw t;
        } finally {
            source.close();
        }
        return gray;
    }

    // ── GUI design atlas (inventory glint-overlay batching) ──────────────────────────────────────
    // Each glinted inventory icon draws its scrolling glint as a live overlay GLYPH (GuiRendererMixin),
    // one per layer/colour. Those glyphs are NOT sorted by the GUI renderer (only blits are), and the GUI
    // mesher flushes a new draw on every texture change — so with a per-design Sampler1 each distinct design
    // forced its own draw call (an inventory full of trims = dozens of draws/frame). Stitching every design
    // into ONE shared atlas makes every glint glyph carry the SAME TextureSetup, so they batch into a single
    // draw regardless of how many distinct designs are on screen. The per-glyph design is selected in-shader
    // from a cell index (packed into the spare high bits of UV2.y); each cell is built with a wrapped gutter
    // so the in-shader fract() tiling stays seamless under LINEAR filtering.
    private static final int GUI_ATLAS_CONTENT = 64;  // design content size per cell (designs are <=64px)
    private static final int GUI_ATLAS_GUTTER  = 4;   // wrapped border per side, so LINEAR can't seam-bleed
    private static final int GUI_ATLAS_STRIDE  = GUI_ATLAS_CONTENT + 2 * GUI_ATLAS_GUTTER; // 72
    // The grid is sized to the design count at build time (ceil(sqrt(n)) cells per side) so data-pack designs
    // batch too, not just the built-ins. Only the grid dimension varies; the shader recovers it from
    // textureSize(Sampler1)/STRIDE, so no per-draw uniform is needed. The cell index rides 8 bits of the GUI
    // glint vertex payload (UV2.y bits 8-15), so at most 256 designs share the atlas; any beyond that fall
    // back to the per-design draw path (still correct, just one draw each).
    private static final int GUI_ATLAS_MAX_CELLS = 256;
    public static final Identifier GUI_DESIGN_ATLAS_ID = CustomGlint.res("gui_design_atlas");
    private static boolean guiDesignAtlasBuilt = false;
    /** design Identifier → its 0-based cell index in the atlas. Absent designs (CHROMATIC, load failures, or
     *  beyond {@link #GUI_ATLAS_MAX_CELLS}) fall back to the per-design draw path. */
    private static final Map<Identifier, Integer> guiDesignCell = new HashMap<>();
    /** Full design list to atlas (built-ins + data-pack designs), installed by the full mod at client init
     *  since the data-pack design list lives in the module, not the api jar. Null → built-ins only (an
     *  api-only embedder without the full mod's data-pack list). */
    private static volatile Supplier<List<Identifier>> guiAtlasDesignSource = null;

    /** Installs the design source for the shared GUI atlas — the full mod passes its data-pack-inclusive
     *  design list here so those designs batch like the built-ins. Forces a rebuild on the next draw. */
    public static void setGuiAtlasDesignSource(Supplier<List<Identifier>> source) {
        guiAtlasDesignSource = source;
        invalidateGuiDesignAtlas();
    }

    /** Drops the built GUI atlas so the next inventory glint draw re-stitches it — call after the design list
     *  changes (data-pack reload / server sync) or a resource reload. Idempotent; no-op if not yet built. */
    public static void invalidateGuiDesignAtlas() {
        if (!guiDesignAtlasBuilt) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.getTextureManager().release(GUI_DESIGN_ATLAS_ID);
        guiDesignCell.clear();
        guiDesignAtlasBuilt = false;
    }

    /** Builds the shared GUI design atlas once (lazily, on first inventory glint draw). Idempotent; the
     *  {@code built} flag is set first so a failed build degrades to the per-design fallback for the whole
     *  session instead of retrying (and stalling) every frame. Rebuilt after a resource reload via
     *  {@link #clearTextures}. */
    private static void ensureGuiDesignAtlas() {
        if (guiDesignAtlasBuilt) return;
        guiDesignAtlasBuilt = true;
        // Source list: the full mod's data-pack-inclusive designs when installed, else the built-ins. Guarded
        // because the source reads a list mutated on other threads (the data-pack reload) — a torn read falls
        // back to the built-ins rather than aborting the build.
        List<Identifier> designs;
        try {
            Supplier<List<Identifier>> src = guiAtlasDesignSource;
            designs = src != null ? src.get() : Arrays.asList(CustomGlint.PATTERNS);
        } catch (Throwable t) {
            designs = Arrays.asList(CustomGlint.PATTERNS);
        }
        if (designs == null || designs.isEmpty()) designs = Arrays.asList(CustomGlint.PATTERNS);
        int count = Math.min(designs.size(), GUI_ATLAS_MAX_CELLS);
        int grid = (int) Math.ceil(Math.sqrt(count));       // cells per side, so grid*grid >= count
        int dim = grid * GUI_ATLAS_STRIDE;
        NativeImage atlas = new NativeImage(dim, dim, false);
        try {
            for (int i = 0; i < count; i++) {
                Identifier design = designs.get(i);
                if (design == null || design.equals(CustomGlint.CHROMATIC)) continue; // procedural: no texture
                NativeImage gray = loadGrayscale(design);
                if (gray == null) continue;                          // missing → per-design fallback
                try {
                    int col = i % grid, row = i / grid;
                    int ox = col * GUI_ATLAS_STRIDE, oy = row * GUI_ATLAS_STRIDE;
                    int sw = gray.getWidth(), sh = gray.getHeight();
                    for (int gy = 0; gy < GUI_ATLAS_STRIDE; gy++) {
                        for (int gx = 0; gx < GUI_ATLAS_STRIDE; gx++) {
                            // Local cell coord (content origin at GUTTER,GUTTER), wrapped into the design so
                            // the gutter holds the opposite-edge texels: seamless LINEAR tiling in the shader.
                            int lx = gx - GUI_ATLAS_GUTTER, ly = gy - GUI_ATLAS_GUTTER;
                            int cx = ((lx % GUI_ATLAS_CONTENT) + GUI_ATLAS_CONTENT) % GUI_ATLAS_CONTENT;
                            int cy = ((ly % GUI_ATLAS_CONTENT) + GUI_ATLAS_CONTENT) % GUI_ATLAS_CONTENT;
                            atlas.setPixel(ox + gx, oy + gy,
                                    gray.getPixel(cx * sw / GUI_ATLAS_CONTENT, cy * sh / GUI_ATLAS_CONTENT));
                        }
                    }
                    guiDesignCell.put(design, i);
                } finally {
                    gray.close();
                }
            }
            // DynamicTexture takes ownership of `atlas` on register (don't close it on the success path).
            Minecraft.getInstance().getTextureManager().register(GUI_DESIGN_ATLAS_ID,
                    new DynamicTexture(() -> MOD_ID + ":gui_design_atlas", atlas));
        } catch (Throwable t) {
            atlas.close();
            guiDesignCell.clear();
            LOGGER.warn("[{}/CustomGlint] GUI design atlas build failed; falling back to per-design glint draws", MOD_ID, t);
        }
    }

    /** The shared GUI design-atlas texture view (one for every glinted icon → the glint glyphs batch), or
     *  null if the atlas isn't built/available (caller falls back to the per-design path). */
    public static GpuTextureView guiDesignAtlasView() {
        ensureGuiDesignAtlas();
        AbstractTexture t = Minecraft.getInstance().getTextureManager().getTexture(GUI_DESIGN_ATLAS_ID);
        return t != null ? t.getTextureView() : null;
    }

    /** The atlas cell index for {@code design}, or null when it isn't atlased (CHROMATIC, a load failure, or
     *  past {@link #GUI_ATLAS_MAX_CELLS}) and the per-design draw path must be used. */
    public static Integer guiDesignCellIndex(Identifier design) {
        ensureGuiDesignAtlas();
        return guiDesignCell.get(design);
    }

    /** Sampler for the GUI design atlas: CLAMP (tiling is done in-shader via fract + the wrapped gutters,
     *  so the bound sampler must NOT wrap across cell boundaries) + LINEAR (matches the per-design design
     *  sampler's filtering). Cached instance, so every atlas glyph shares one TextureSetup and batches. */
    public static com.mojang.blaze3d.textures.GpuSampler guiDesignAtlasSampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }

    // ── Procedural chromatic ────────────────────────────────────────────────────

    /** Lazily builds the 1×1 opaque-white texture bound to the chromatic pipeline's unused Sampler0
     *  (the chromatic shader never samples it, but the inherited binding must point somewhere). */
    private static Identifier getWhiteTexture() {
        if (whiteTexture != null) return whiteTexture;
        NativeImage img = new NativeImage(1, 1, false);
        img.setPixel(0, 0, 0xFFFFFFFF);
        Identifier loc = CustomGlint.res("chromatic_white");
        Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(() -> MOD_ID + ":chromatic_white", img));
        whiteTexture = loc;
        return loc;
    }

    /** Builds (and caches) the palette strip for {@code colors}, one opaque RGBA texel per color. An empty
     *  list yields a 1×1 dummy; the shader reads the real count from the animation matrix and takes the
     *  rainbow-fallback path, so the dummy texel is never read. */
    private static Identifier getPaletteTexture(int[] colors) {
        String key = colorsKey(colors);
        Identifier cached = paletteCache.get(key);
        if (cached != null) return cached;
        int w = Math.max(1, colors.length);
        NativeImage img = new NativeImage(w, 1, false);
        for (int i = 0; i < w; i++) {
            int c = i < colors.length ? colors[i] : 0xFFFFFFFF;
            img.setPixel(i, 0, 0xFF000000 | (c & 0xFFFFFF)); // force opaque so texelFetch returns the pure color
        }
        Identifier loc = CustomGlint.res("chromatic_palette/" + key);
        Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(() -> MOD_ID + ":chromatic_palette", img));
        paletteCache.put(key, loc);
        return loc;
    }

    /** Content key for a color array, distinct colors map to distinct keys (unlike {@code Arrays.hashCode},
     *  which collides). Hex digits + {@code '_'} only, so it doubles as a valid resource-path segment. */
    private static String colorsKey(int[] colors) {
        StringBuilder sb = new StringBuilder(colors.length * 9);
        for (int c : colors) sb.append(Integer.toHexString(c)).append('_');
        return sb.toString();
    }

    /** The palette an UNCOLORED chromatic layer renders with, white / grey / dark grey, so an "empty"
     *  chromatic trim reads as a neutral greyscale oil-slick instead of the full-spectrum rainbow fallback. */
    public static final int[] CHROMATIC_EMPTY_PALETTE = { 0xFFFFFFFF, 0xFF8A8A8A, 0xFF3A3A3A };

    /** A chromatic layer's effective palette: its own colors, or {@link #CHROMATIC_EMPTY_PALETTE} when it has
     *  none. Both the world RenderType and the GUI overlay resolve colors through this so an empty chromatic
     *  trim looks the same everywhere. */
    public static int[] chromaticColors(int[] colors) {
        return colors.length == 0 ? CHROMATIC_EMPTY_PALETTE : colors;
    }

    /** Public accessor for the GUI overlay path: the cached palette-strip texture for {@code colors}
     *  (same texture the world chromatic RenderType binds to Sampler1). */
    public static Identifier paletteTexture(int[] colors) {
        return getPaletteTexture(colors);
    }

    /** Reduces a 32-bit seed to a small, well-distributed float so {@code seed*offset} in the shader stays
     *  within float precision (the raw seed would blow up the noise coordinates). */
    private static float packSeed(int seed) {
        return (seed & 0xFFFF) / 256.0f; // 0..256
    }

    /** Noise UV scale for chromatic on MODEL surfaces (armor, entity bodies, horse armor). These sample their
     *  own 0..1 texture UV where each body part is a small sub-rect, so the field is scaled up ~8× to give a
     *  per-part density near the flat-item look. Tunable: higher = smaller/more colour cells per body part. */
    private static final float CHROMATIC_MODEL_UV_SCALE = 8.0f;

    /** Single-draw chromatic RenderType for {@code layer}, the palette carries every color, so unlike the
     *  normal glint factories this is never looped per-color. {@code tag} keeps the item / armor / entity
     *  layering variants in separate cache buckets. {@code uvScale} multiplies the noise UV so the on-item
     *  density matches the GUI overlay: flat items sample the BLOCK ATLAS, where a 16px sprite spans only
     *  {@code spritePx/atlasPx} of UV space, so without this the whole oil-slick collapses to one zoomed-in
     *  blob. {@code atlasW/16} makes a 16px sprite span ~1 UV unit (= the GUI's item-local 0..1), so both show
     *  the same ~DENSITY cells. Armor/entity sample their own 0..1 model UV, so they pass {@code 1.0}. */
    private static RenderType chromaticRT(Layer layer, LayeringTransform layering, String tag, float uvScale) {
        return chromaticRT(layer, layering, tag, uvScale, false);
    }

    /** {@code overlay=true} builds on {@link GlintPipelines#CHROMATIC_OVERLAY} (LEQUAL depth) for chromatic
     *  drawn on top of a separately-rendered surface, block-model entity layers. See {@link #forBlockGlint}. */
    private static RenderType chromaticRT(Layer layer, LayeringTransform layering, String tag, float uvScale, boolean overlay) {
        int[] colors = chromaticColors(layer.colors());
        final double speed = layer.speed();
        final float effScale = layer.patternScale() * uvScale;
        final int cc = colors.length;
        final float seedPacked = packSeed(layer.seed());
        Identifier white = getWhiteTexture();
        Identifier palette = getPaletteTexture(colors);
        String key = tag + "|" + colorsKey(colors) + "|" + speed + "|" + effScale + "|" + layer.seed();
        RenderType cached = BY_CHROMATIC.computeIfAbsent(key, k -> {
            String name = MOD_ID + ":custom_chromatic|" + k.hashCode();
            Supplier<Matrix4f> anim = () -> GlintPipelines.chromaticMatrix(speed, effScale, cc, seedPacked);
            RenderType rt = overlay
                    ? GlintPipelines.chromaticBlockType(name, white, palette, TextureAtlas.LOCATION_BLOCKS, layering, anim)
                    : GlintPipelines.chromaticType(name, white, palette, layering, anim);
            registerFixed(rt);
            return rt;
        });
        registerLiveFixedBuffer(cached);
        return cached;
    }

    /**
     * Post-Iris chromatic OVERLAY RenderType for {@code layer}, the {@link #chromaticRT} counterpart on
     * {@link GlintPipelines#CHROMATIC_OVERLAY} (ALWAYS depth + an in-shader scene-depth occlusion test).
     * Same palette / seed / speed / count / pattern-scale payload, so the slick matches the in-phase look;
     * it just survives an active shader pack by being re-rendered after the framegraph (see
     * {@code EntityGlintRender.drainChromaticOverlays}). Only meaningful for chromatic layers, callers gate
     * on {@code CustomGlint.isChromatic} and {@link #isShaderPackActive()}.
     */
    private static RenderType chromaticOverlayRT(Layer layer, String tag, float uvScale, @Nullable Identifier texture) {
        int[] colors = chromaticColors(layer.colors());
        final double speed = layer.speed();
        final float effScale = layer.patternScale() * uvScale;
        final int cc = colors.length;
        final float seedPacked = packSeed(layer.seed());
        // Sampler0 is the MODEL texture (its alpha cuts the slick to the real silhouette, a rectangular
        // elytra/armor mesh would otherwise fill its whole bounding quad). Fall back to the opaque white
        // dummy (alpha 1 → no discard, full mesh) when the caller has no texture (e.g. an opaque body).
        final Identifier sampler0 = texture != null ? texture : getWhiteTexture();
        Identifier palette = getPaletteTexture(colors);
        String key = tag + "|" + sampler0 + "|" + colorsKey(colors) + "|" + speed + "|" + effScale + "|" + layer.seed();
        RenderType cached = BY_CHROMATIC_OVERLAY.computeIfAbsent(key, k -> {
            String name = MOD_ID + ":custom_chromatic_overlay|" + k.hashCode();
            Supplier<Matrix4f> anim = () -> GlintPipelines.chromaticMatrix(speed, effScale, cc, seedPacked);
            RenderType rt = GlintPipelines.chromaticOverlayType(name, sampler0, palette, SCENE_DEPTH_ID, anim);
            registerFixed(rt);
            return rt;
        });
        registerLiveFixedBuffer(cached);
        return cached;
    }

    /** Post-Iris chromatic overlay RT for worn equipment (humanoid armor, elytra/cape, barding), the
     *  {@link #forArmorGlint} chromatic branch re-routed onto {@link GlintPipelines#CHROMATIC_OVERLAY}.
     *  {@code texture} is the equipment-layer texture whose alpha cuts the slick to the real armor shape. */
    public static RenderType forArmorGlintOverlay(Data glint, int layerIdx, @Nullable Identifier texture) {
        return chromaticOverlayRT(glint.layers()[layerIdx], "armor_ov", CHROMATIC_MODEL_UV_SCALE, texture);
    }

    /** Post-Iris chromatic overlay RT for entity bodies / horse armor, the {@link #forEntityGlint} chromatic
     *  branch re-routed onto {@link GlintPipelines#CHROMATIC_OVERLAY}. {@code texture} is the body texture
     *  (its alpha cuts the slick to the entity shape); null for an opaque body draws the whole mesh. */
    public static RenderType forEntityGlintOverlay(Data glint, int layerIdx, @Nullable Identifier texture) {
        return chromaticOverlayRT(glint.layers()[layerIdx], "entity_ov", CHROMATIC_MODEL_UV_SCALE, texture);
    }

    /** Post-Iris chromatic overlay RT for SPECIAL 3D items (shield/trident), scale 1 + the white dummy
     *  cutout (full model shape, like the glow part path). Flat/quad items use {@link #forItemGlintOverlay}
     *  instead (per-quad atlas cutout). */
    public static RenderType forSpecialItemGlintOverlay(Data glint, int layerIdx) {
        return chromaticOverlayRT(glint.layers()[layerIdx], "special_ov", 1.0f, getWhiteTexture());
    }

    /** Post-Iris chromatic overlay RT for a flat/quad item, cut out against the quad's OWN sprite atlas.
     *  {@code atlas} must be the quad's {@code materialInfo().sprite().atlasLocation()} (item sprites can live
     *  on different atlases, binding a fixed atlas samples the wrong texels, so the slick either fills the
     *  whole quad or vanishes). Sampler0 = that atlas drives the cutout; the noise scale stays
     *  {@code atlasW/16}, matching {@link #forGlint}'s flat-item calibration. */
    public static RenderType forItemGlintOverlay(Data glint, int layerIdx, Identifier atlas) {
        ensureAtlasDims();
        return chromaticOverlayRT(glint.layers()[layerIdx], "item_ov|" + atlas, cachedAtlasW / 16.0f, atlas);
    }

    /** Composites the isolated chromatic-overlay target ({@code inView}) onto the main target
     *  ({@code outView}) with the GLINT blend, the chromatic analog of {@link #upscaleGlowRing}. Called by
     *  {@code EntityGlintRender.drainChromaticOverlays} after the slick is rendered into its own target. */
    public static void compositeChromatic(GpuTextureView inView, GpuTextureView outView) {
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "customglint chromatic composite", outView, OptionalInt.empty())) {
            pass.setPipeline(GlintPipelines.CHROMATIC_COMPOSITE_PIPE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("InSampler", inView,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(0, 3);
        }
    }

    // ── Render types ──────────────────────────────────────────────────────────

    /** Assigned by RenderBuffersMixin on RenderBuffers construction; null until then. */
    public static SequencedMap<RenderType, ByteBufferBuilder> fixedBufferRegistry;

    private static final Map<String, RenderType> BY_GLINT              = newRtCache();
    private static final Map<String, RenderType> BY_ARMOR_GLINT        = newRtCache();
    private static final Map<String, RenderType> BY_ENTITY_BODY_GLINT  = newRtCache();
    private static final Map<String, RenderType> BY_BLOCK_GLINT        = newRtCache();
    private static final Map<String, RenderType> BY_CHROMATIC          = newRtCache();
    private static final Map<String, RenderType> BY_CHROMATIC_OVERLAY  = newRtCache();

    /** Lazily resolves the block-atlas dimensions feeding the item/block glint scale. Atlas size only
     *  changes on resource reload, so this runs once until {@link #clearTextures} resets the cache. */
    private static void ensureAtlasDims() {
        if (cachedAtlasW != 0) return;
        cachedAtlasW = 2048; cachedAtlasH = 2048;
        if (Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS)
                instanceof TextureAtlas atlas) {
            cachedAtlasW = atlas.width;
            cachedAtlasH = atlas.height;
        }
    }

    /**
     * Registers {@code rt}'s persistent buffer into the live BufferSource's {@code fixedBuffers}
     * when absent. Iris and Sodium swap the vanilla BufferSource for one whose {@code fixedBuffers}
     * is an IMMUTABLE fastutil map, its {@code put} throws {@link UnsupportedOperationException}
     * (the inline {@code live.put(...)} this replaced crashed armor/entity/item/outline glint the
     * instant Iris was installed: {@code Object2ObjectFunction.put} → UOE). Swallow that: under an
     * active shader pack our RTs render through the forward/shader path, which never pulls from this
     * BufferSource's fixed buffers, so the registration isn't needed there anyway. The vanilla
     * {@code fixedBufferRegistry} (captured in RenderBuffersMixin) still holds the RT for the
     * no-shader path.
     */
    private static void registerFixed(RenderType rt) {
        if (rt == null) return;
        OWNED_RENDER_TYPES.add(rt);
        if (fixedBufferRegistry != null && !fixedBufferRegistry.containsKey(rt))
            fixedBufferRegistry.put(rt, new ByteBufferBuilder(rt.bufferSize()));
    }

    /** Every glint/glow {@link RenderType} this class mints, identity-tracked so the ImmediatelyFast compat
     *  mixin can route ONLY ours through IF's non-batched path. IF's {@code enhanced_batching} batches every
     *  consolidatable (QUADS) render type and toggles Iris's vertex-format state mid-flush, which desyncs the
     *  26.1 GpuDevice program cache to program 0 for custom render types under Iris ("No active program"
     *  spam). Routing ours direct (as without IF) avoids it while leaving the rest of the pack batched. Weak
     *  keys so LRU-evicted RTs drop out, no leak. */
    public static final Set<RenderType> OWNED_RENDER_TYPES =
            Collections.newSetFromMap(new WeakHashMap<>());

    /** True if {@code rt} is one of our glint/glow render types (see {@link #OWNED_RENDER_TYPES}). */
    public static boolean isOwnedRenderType(RenderType rt) {
        return rt != null && OWNED_RENDER_TYPES.contains(rt);
    }

    public static void registerLiveFixedBuffer(RenderType rt) {
        if (rt == null) return;
        OWNED_RENDER_TYPES.add(rt);
        SequencedMap<RenderType, ByteBufferBuilder> live =
                Minecraft.getInstance().renderBuffers().bufferSource().fixedBuffers;
        if (live == null || live.containsKey(rt)) return;
        try {
            live.put(rt, new ByteBufferBuilder(rt.bufferSize()));
        } catch (UnsupportedOperationException ignored) {
            // immutable fixedBuffers (Iris/Sodium), forward/shader path handles these RTs
        }
    }

    /**
     * Glint for a block-model entity layer (mooshroom mushrooms, snow-golem pumpkin). Mirrors
     * {@link #forGlint}'s flat-item path, block parts carry block-atlas UVs, so the same atlas-calibrated
     * scale applies, but builds on {@link GlintPipelines#GLINT_BLOCK} (LEQUAL depth) + VIEW_OFFSET_Z. The
     * block is drawn by a DIFFERENT pipeline, so EQUAL would flicker against its depth; LEQUAL + a toward-camera
     * bias sits the glint just in front instead. Emitted via {@code submitCustomGeometry} by
     * {@link EntityGlintRender#submitBlockLayerGlintGlow}.
     */
    public static RenderType forBlockGlint(Data glint, int layerIdx, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        ensureAtlasDims();
        final int atlasW = cachedAtlasW, atlasH = cachedAtlasH;
        if (CustomGlint.isChromatic(layer))
            return chromaticRT(layer, LayeringTransform.VIEW_OFFSET_Z_LAYERING, "block", atlasW / 16.0f, true);
        Identifier gray = getTexture(layer.design());
        if (gray == null) return null;
        final float scaleU = 8.0f * atlasW / 1024.0f;
        final float scaleV = 8.0f * atlasH / 512.0f;
        final int cc = layer.colors().length;
        final double speed = layer.speed();
        final float ps = layer.patternScale();
        final int scrollDir = layer.scrollDir();
        final float scrollOffset = layer.scrollOffset();
        String key = "block|" + layer.design() + "|" + speed + "|" + ps + "|" + colorIdx + "|" + layerIdx
                + "|" + cc + "|" + scrollDir + "|" + scrollOffset;
        RenderType cached = BY_BLOCK_GLINT.computeIfAbsent(key, k -> {
            RenderType rt = GlintPipelines.blockGlintType(MOD_ID + ":custom_block_glint|" + k.hashCode(),
                    gray, TextureAtlas.LOCATION_BLOCKS, LayeringTransform.VIEW_OFFSET_Z_LAYERING,
                    () -> GlintPipelines.itemAnimationMatrix(speed, scaleU, scaleV, ps, colorIdx, cc, scrollDir, scrollOffset));
            registerFixed(rt);
            return rt;
        });
        registerLiveFixedBuffer(cached);
        return cached;
    }

    // Armor glint matches armor_cutout_no_cull's VIEW_OFFSET_Z layering (EQUAL depth, D-ε) so the
    // glint depth-test lands exactly on the armor depth. See the project_armor_glint_bleed_fix memory.
    public static RenderType forArmorGlint(Data glint, int layerIdx, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        // Armor samples its own 0..1 texture UV and each body part is only a small sub-rect of it, so scale
        // the noise up (~8×) for a per-part density close to the item look, 1.0 made one part show <1 cell.
        if (CustomGlint.isChromatic(layer)) return chromaticRT(layer, LayeringTransform.VIEW_OFFSET_Z_LAYERING, "armor", CHROMATIC_MODEL_UV_SCALE);
        Identifier gray = getTexture(layer.design());
        if (gray == null) return null;
        final int cc = layer.colors().length;
        final double speed = layer.speed();
        final float ps = layer.patternScale();
        final int scrollDir = layer.scrollDir();
        final float scrollOffset = layer.scrollOffset();
        String key = "armor|" + layer.design() + "|" + speed + "|" + ps + "|" + colorIdx + "|" + cc + "|" + scrollDir + "|" + scrollOffset;
        RenderType cached = BY_ARMOR_GLINT.computeIfAbsent(key, k -> {
            RenderType rt = GlintPipelines.glintType(MOD_ID + ":custom_armor_glint|" + k.hashCode(), gray,
                    LayeringTransform.VIEW_OFFSET_Z_LAYERING,
                    () -> GlintPipelines.armorAnimationMatrix(speed, ps, colorIdx, cc, scrollDir, scrollOffset));
            registerFixed(rt);
            return rt;
        });
        registerLiveFixedBuffer(cached);
        return cached;
    }

    /**
     * Entity body glint (pigs, cows, zombies, … anything rendered through entityCutoutNoCull).
     * Aliases {@link #forEntityBodyGlint}; separate method only for caller clarity.
     */
    public static RenderType forEntityGlint(Data glint, int layerIdx, int colorIdx) {
        return forEntityBodyGlint(glint, layerIdx, colorIdx);
    }

    // Entity bodies render through entityCutoutNoCull (no polygon offset / no VIEW_OFFSET_Z_LAYERING).
    // forArmorGlint uses EQUAL + VIEW_OFFSET_Z_LAYERING, wrong offset → invisible on a body draw.
    // This variant keeps EQUAL + NO_LAYERING so depth matches, and scale 1.0 matches forArmorGlint visually.
    // (Named for horse armor in 1.20.1/1.21.1, where it backed barding; in 26.1 barding routes through the
    // unified EquipmentLayerRenderer → forArmorGlint, and this method's sole caller is the entity-body path.)
    public static RenderType forEntityBodyGlint(Data glint, int layerIdx, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        // Entity bodies sample their own 0..1 model UV, same per-part density bump as armor.
        if (CustomGlint.isChromatic(layer)) return chromaticRT(layer, LayeringTransform.NO_LAYERING, "entitybody", CHROMATIC_MODEL_UV_SCALE);
        Identifier gray = getTexture(layer.design());
        if (gray == null) return null;
        final int cc = layer.colors().length;
        final double speed = layer.speed();
        final float ps = layer.patternScale();
        final int scrollDir = layer.scrollDir();
        final float scrollOffset = layer.scrollOffset();
        String key = "entitybody|" + layer.design() + "|" + speed + "|" + ps + "|" + colorIdx + "|" + layerIdx + "|" + cc + "|" + scrollDir + "|" + scrollOffset;
        RenderType cached = BY_ENTITY_BODY_GLINT.computeIfAbsent(key, k -> {
            RenderType rt = GlintPipelines.glintType(MOD_ID + ":custom_entity_body_glint|" + k.hashCode(), gray,
                    LayeringTransform.NO_LAYERING,
                    () -> GlintPipelines.armorAnimationMatrix(speed, ps, colorIdx, cc, scrollDir, scrollOffset));
            registerFixed(rt);
            return rt;
        });
        registerLiveFixedBuffer(cached);
        return cached;
    }

    public static RenderType forGlint(Data glint, int layerIdx, boolean isItem, int colorIdx) {
        // isItem=true → flat item model → atlas-calibrated 8× scale (matches vanilla glint()).
        // isItem=false → 3D entity model (trident, etc.) → 1.0 for visible pattern detail.
        // 26.1: ModelManager.getAtlas is gone (atlas lives on the private AtlasManager). The block
        // atlas is registered in TextureManager under LOCATION_BLOCKS; read its dims there.
        // Pattern density across a sprite = scale * (spritePx / atlasDim), so scale must track the
        // atlas dimension to keep density (and thus the on-screen look) constant as the atlas grows.
        // The asymmetric 1024/512 denominators are the empirically-square 1.20.1/1.21.1 calibration:
        // they yield density_U = spriteW/128, density_V = spriteH/64, the proven values. On the larger
        // 2048² atlas this resolves to scaleU=16, scaleV=32, the bigger numbers just offset the bigger
        // atlas; the density (16*16/2048 = 16/64) is identical to the 1.21.1 atlas (8*16/512 = 16/64).
        // A previous port collapsed both to /2048 thinking the larger V scale was "too dense"; that
        // actually quartered V density and squared the designs. Do NOT re-symmetrize these.
        ensureAtlasDims();
        final int atlasW = cachedAtlasW, atlasH = cachedAtlasH;
        final float scaleU = isItem ? (8.0f * atlasW / 1024.0f) : 1.0f;
        final float scaleV = isItem ? (8.0f * atlasH / 512.0f) : 1.0f;
        Layer layer = glint.layers()[layerIdx];
        if (CustomGlint.isChromatic(layer)) {
            // Flat items sample the block atlas (UV0 spans only spritePx/atlasPx) → scale up to match the GUI
            // density; 3D items (trident) sample their own 0..1 model UV → scale 1.
            float uvScale = isItem ? (atlasW / 16.0f) : 1.0f;
            return chromaticRT(layer, LayeringTransform.NO_LAYERING, "item|" + isItem, uvScale);
        }
        Identifier gray = getTexture(layer.design());
        if (gray == null) return null;
        final int cc = layer.colors().length;
        final double speed = layer.speed();
        final float ps = layer.patternScale();
        final int scrollDir = layer.scrollDir();
        final float scrollOffset = layer.scrollOffset();
        String key = layer.design() + "|" + speed + "|" + isItem + "|" + ps + "|" + colorIdx + "|" + layerIdx
                + "|" + cc + "|" + scrollDir + "|" + scrollOffset;
        RenderType cached = BY_GLINT.computeIfAbsent(key, k -> {
            RenderType rt = GlintPipelines.glintType(MOD_ID + ":custom_glint|" + k.hashCode(), gray,
                    LayeringTransform.NO_LAYERING,
                    () -> GlintPipelines.itemAnimationMatrix(speed, scaleU, scaleV, ps, colorIdx, cc, scrollDir, scrollOffset));
            registerFixed(rt);
            return rt;
        });
        registerLiveFixedBuffer(cached);
        return cached;
    }

    public static int computeAnimatedColor(Data glint, int layerIdx) {
        Minecraft mc = Minecraft.getInstance();
        long gameTime = mc.level != null ? mc.level.getGameTime() : 0;
        return computeAnimatedColorAt(glint, layerIdx, gameTime);
    }

    /** GUI variant of {@link #computeAnimatedColor}: animates off wall-clock ticks ({@code Util.getMillis()/50})
     *  so a multi-color cycle keeps moving on GUI icons shown with no level loaded (main menu, off-world
     *  inventory screens), where {@code getGameTime()} is pinned to 0 and the world variant would freeze on
     *  color 0. The world path stays on game time so it still pauses with the game. */
    public static int computeAnimatedColorGui(Data glint, int layerIdx) {
        return computeAnimatedColorAt(glint, layerIdx, Util.getMillis() / 50L);
    }

    private static int computeAnimatedColorAt(Data glint, int layerIdx, long gameTime) {
        Layer[] layers = glint.layers();
        // A decoded Data may legally hold zero layers ({"layers":[]} via give-NBT / datapack / crafted
        // packet, Data.CODEC sets no minimum). Call sites that pass a fixed index 0 only null-check, so
        // guard here against an AIOOBE on the render thread.
        if (layerIdx < 0 || layerIdx >= layers.length) return 0xFFFFFFFF;
        Layer layer = layers[layerIdx];
        int[] colors = layer.colors();
        if (colors.length == 0) return 0xFFFFFFFF;
        if (colors.length == 1) return colors[0];
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
        Minecraft mc = Minecraft.getInstance();
        long gameTime = mc.level != null ? mc.level.getGameTime() : 0;
        return computeAnimatedGlowColorAt(colors, gameTime);
    }

    /** GUI variant of {@link #computeAnimatedGlowColor}: wall-clock ticks so a multi-color glow halo keeps
     *  animating on off-world GUI icons (see {@link #computeAnimatedColorGui}). */
    public static int computeAnimatedGlowColorGui(int[] colors) {
        return computeAnimatedGlowColorAt(colors, Util.getMillis() / 50L);
    }

    private static int computeAnimatedGlowColorAt(int[] colors, long gameTime) {
        if (colors.length == 0) return 0xFFFFFFFF;
        if (colors.length == 1) return colors[0];
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
     *, {@code setColor(int)} unpacks to the subclass's own {@code setColor(r,g,b,a)} (so each
     * wrapper's color policy, no-op, force-override, pass-through, is honored), and
     * {@code setLineWidth} is a no-op (these wrappers never drive line geometry).
     */
    private abstract static class WrappingConsumer implements VertexConsumer {
        @Override public VertexConsumer setColor(int argb) {
            return setColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
        }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }

    /** Wraps a VertexConsumer and overrides vertex colors with a fixed RGBA value, forwarding
     *  uv/overlay/uv2/normal to the wrapped buffer. Forwarding those is required when the underlying
     *  buffer uses a full entity vertex format (POSITION_COLOR_TEX_OVERLAY_LIGHTMAP_NORMAL), dropping
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
     *  {minX,minY,minZ,maxX,maxY,maxZ} accumulator. Feeds {@code glowMaskBox}, the per-object screen-space
     *  bounds the composite pass scissors to so each glow ring only touches its own region. */
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
    // Reflective (via IrisApi) so we don't need a compileOnly dep on Iris. The two Methods are resolved
    // once and cached. Used as a timing switch: under an active pack the glow drain happens mid-framegraph
    // (where Iris hijacks our framebuffer into its gbuffer → black screen), so the drain is relocated to
    // LevelRendererMixin (renderLevel TAIL, post-Iris) and the AfterWeather drain in CustomGlintClientInit
    // skips itself here. The actual pipeline→program wiring under a pack is handled by IrisCompat, not here.
    private static volatile boolean SHADER_LOOKUP_DONE = false;
    private static volatile Method SHADER_GET_INSTANCE = null;
    private static volatile Method SHADER_IS_IN_USE = null;

    /** Per-frame cache of {@link #computeShaderPackActive()}. A shader pack can't toggle mid-frame, and the
     *  reflective {@code isShaderPackInUse} probe is hit many times per frame (per equipment layer per wearer,
     *  every chromatic gate, every drain), so resolve it once in {@code RenderFrameEvent.Pre}. */
    private static volatile boolean frameShaderActive;

    /** Refreshes {@link #frameShaderActive}; called once per frame from {@code CustomGlintClientInit}. */
    public static void refreshFrameShaderActive() { frameShaderActive = computeShaderPackActive(); }

    public static boolean isShaderPackActive() { return frameShaderActive; }

    private static boolean computeShaderPackActive() {
        if (!SHADER_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!SHADER_LOOKUP_DONE) {
                    try {
                        // Iris ships the same API package on both loaders, do not change this path.
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
