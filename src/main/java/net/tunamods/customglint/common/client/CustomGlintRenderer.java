package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.animal.equine.HorseModel;
import net.minecraft.client.model.object.equipment.ElytraModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.tunamods.customglint.common.CustomGlint;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedMap;
import java.util.SequencedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

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
 * supplier. See {@code .claude/context/26/09-renderer-api-verified.md}.
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

    /**
     * Lazily-built parallel of LOCATION_BLOCKS where every pixel has RGB=255 and the original
     * alpha. Bound as Sampler0 in the held-sprite outline RT under shader packs so the pack's
     * gbuffers_entities shader computes {@code white × vertexColor = vertexColor} — i.e. the
     * ring renders in the chosen outline color instead of being tinted by the underlying
     * item's texture. Built once on first use; cleared on resource reload via
     * {@link #clearTextures} so the next use rebuilds against the freshly stitched atlas.
     */
    public static final Identifier BLOCKS_ALPHA_MASK_LOC =
            Identifier.fromNamespaceAndPath(MOD_ID,"textures/atlas/blocks_alpha_mask");
    private static boolean blocksAlphaMaskBuilt = false;

    /** Returns the alpha-mask atlas location for the held-sprite shader-pack outline path.
     *  <p>26.1.2: {@code NativeImage.downloadTexture} (the GL readback that built this mask from the
     *  stitched block atlas) is gone — readback now goes through the {@code GpuTexture}/command-encoder
     *  model with no drop-in. This is a shader-pack-only enhancement (colored outline tinting under
     *  Iris); the core stencil outline path does not depend on it. Degraded to return the plain block
     *  atlas until the GpuTexture readback is ported and tested in-game. See
     *  {@code .claude/context/26/09-renderer-api-verified.md}. */
    public static Identifier getBlocksAlphaMask() {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    /** Per-armor-texture alpha-mask cache. Parallel of the source armor PNG with RGB=255 and
     *  alpha preserved. Bound as Sampler0 in {@link #forShaderArmorOutlineTextured} under shader
     *  packs so the pack's gbuffers_entities shader computes {@code white × vertexColor =
     *  vertexColor} — outline ring renders in the chosen color instead of being tinted by the
     *  armor's albedo (worst case: white outline × red leather armor = red). Cleared on resource
     *  reload via {@link #clearTextures}. */
    private static final Map<Identifier, Identifier> ARMOR_ALPHA_MASKS = new HashMap<>();

    public static Identifier getArmorAlphaMask(Identifier original) {
        if (original == null) return null;
        Identifier cached = ARMOR_ALPHA_MASKS.get(original);
        if (cached != null) return cached;
        Minecraft mc = Minecraft.getInstance();
        NativeImage src;
        boolean srcOwned;  // close src only if we read from a resource stream
        try {
            var res = mc.getResourceManager().getResource(original);
            if (res.isPresent()) {
                try (InputStream stream = res.get().open()) {
                    src = NativeImage.read(stream);
                }
                srcOwned = true;
            } else {
                // Resource not in the pack — might be a registered DynamicTexture (e.g. a
                // compat-synthesized armor texture variant).
                // Falling back to `return original` here would bind the dynamic texture's
                // actual armor pixels for the outline, so gbuffers_entities multiplies
                // vertexColor × armor RGB and the outline picks up the armor's albedo
                // (red WingedHussar → purple-outline-tinted-red). Read the DynamicTexture's
                // backing NativeImage instead so the alpha mask is RGB=255 + binarized alpha.
                AbstractTexture tex =
                        mc.getTextureManager().getTexture(original);
                if (tex instanceof DynamicTexture dt && dt.getPixels() != null) {
                    src = dt.getPixels();
                    srcOwned = false;
                } else {
                    return original;
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[{}/CustomGlint] getArmorAlphaMask: read threw for {}", MOD_ID, original, t);
            return original;
        }
        NativeImage mask = new NativeImage(src.getWidth(), src.getHeight(), false);
        try {
            // Binarize alpha: any source alpha > 0 → 255 in the mask, else 0. ENTITY_CUTOUT_NO_CULL
            // (used by shader-pack outline RTs) discards at alpha < 0.1 (~25/255), while the
            // shaders-off OUTLINE_SHADER stencil path discards at alpha == 0. Textures with
            // anti-aliased edges (chainmail, lace, fine detail) have sub-threshold alpha at the
            // perimeter pixels — they outline correctly under stencil but vanish under shaders.
            // Binarizing here equalizes the two paths so the shader-pack outline covers every
            // pixel the stencil pipeline would have stamped. RGB stays 255 so the pack computes
            // {@code white × vertexColor = vertexColor} (outline color is preserved, not tinted
            // by the source albedo).
            // 26.1.2: getPixelRGBA/setPixelRGBA removed; getPixel/setPixel are ARGB (0xAARRGGBB).
            // The packing is channel-symmetric for an RGB=255 mask, so only the alpha extraction
            // shift changes (alpha is the top byte under both).
            for (int y = 0; y < src.getHeight(); y++) {
                for (int x = 0; x < src.getWidth(); x++) {
                    int alpha = (src.getPixel(x, y) >> 24) & 0xFF;
                    int outAlpha = alpha > 0 ? 0xFF : 0x00;
                    mask.setPixel(x, y, (outAlpha << 24) | 0x00FFFFFF);
                }
            }
        } finally {
            if (srcOwned) src.close();
        }
        String safePath = original.getNamespace() + "_" + original.getPath().replace('/', '_').replace('.', '_');
        Identifier loc = Identifier.fromNamespaceAndPath(MOD_ID,"armor_alpha_mask/" + safePath);
        // Wrap/filter now live on the per-binding GpuSampler (CLAMP_TO_EDGE + NEAREST is supplied at
        // bind time by the outline RenderType), so the old dt.bind()/glTexParameteri setup is gone.
        DynamicTexture dt = new DynamicTexture(() -> "customglint/armor_alpha_mask/" + safePath, mask);
        mc.getTextureManager().register(loc, dt);
        ARMOR_ALPHA_MASKS.put(original, loc);
        return loc;
    }

    public static void clearTextures() {
        Minecraft mc = Minecraft.getInstance();
        for (Identifier loc : textureCache.values())
            if (loc != null) mc.getTextureManager().release(loc);
        textureCache.clear();
        if (blocksAlphaMaskBuilt) {
            try { mc.getTextureManager().release(BLOCKS_ALPHA_MASK_LOC); } catch (Throwable ignored) {}
            blocksAlphaMaskBuilt = false;
        }
        for (Identifier loc : ARMOR_ALPHA_MASKS.values()) {
            try { mc.getTextureManager().release(loc); } catch (Throwable ignored) {}
        }
        ARMOR_ALPHA_MASKS.clear();
        for (Runnable r : additionalReloadCleanup) {
            try { r.run(); } catch (Throwable t) {
                LOGGER.warn("[{}/CustomGlint] additional reload cleanup threw", MOD_ID, t);
            }
        }
        if (fixedBufferRegistry != null) {
            for (RenderType rt : BY_GLINT.values())             fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_ARMOR_GLINT.values())       fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_HORSE_ARMOR_GLINT.values()) fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_BODY_DEPTH_FILL.values())   fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_OUTLINE_WRITE.values())     fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_OUTLINE_WRITE_ITEM.values()) fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_OUTLINE_TEST.values())      fixedBufferRegistry.remove(rt);
            for (RenderType rt : OUTLINE_SLOT_RT.values())      fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_GLOW_MASK.values())          fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_GLOW_MASK_FLAT.values())     fixedBufferRegistry.remove(rt);
        }
        BY_GLINT.clear();
        BY_ARMOR_GLINT.clear();
        BY_HORSE_ARMOR_GLINT.clear();
        BY_BODY_DEPTH_FILL.clear();
        BY_OUTLINE_WRITE.clear();
        BY_OUTLINE_WRITE_ITEM.clear();
        BY_OUTLINE_TEST.clear();
        OUTLINE_SLOT_RT.clear();
        BY_GLOW_MASK.clear();
        BY_GLOW_MASK_FLAT.clear();
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
    // The 26.1 replacement for the entity-body stencil ring (confirmed dead end — see
    // GlintPipelines.outlineTestPipe TRIED block + 26/13-outlines.md). Orchestrated by
    // EntityGlintRender.drainBodyOutlines at RenderLevelStageEvent.AfterOpaqueFeatures.

    private static final Map<Identifier, RenderType> BY_GLOW_MASK = new HashMap<>();
    /** Occlusion-OFF mask RTs (config outlineOcclusion = false), cached separately so toggling the
     *  setting live just swaps which map glowMaskRT reads. */
    private static final Map<Identifier, RenderType> BY_GLOW_MASK_FLAT = new HashMap<>();

    /** Identifier the combined-mask RenderType binds for the scene-depth sampler. Resolved through
     *  TextureManager to {@link #sceneDepthTex}, a holder whose view is re-pointed at the live main-target
     *  depth each frame by {@link #bindSceneDepth}. The mask shader (core/glow_silhouette) samples this to
     *  decide per-fragment occlusion, which is why no separate depth-downsample pass is needed any more. */
    public static final Identifier SCENE_DEPTH_ID = Identifier.fromNamespaceAndPath(MOD_ID, "scene_depth");

    /** Per-texture combined-mask RenderType (GLOW_MASK_PIPE: ALWAYS_PASS depth, shape + per-fragment
     *  visibility encoded in alpha by core/glow_silhouette). One render per mob writes everything the
     *  composite needs — no separate shape pass. The draw is redirected to the half-res mask target by
     *  the caller via RenderSystem.outputColor/DepthTextureOverride. */
    /** {@code seeThrough} selects the cheaper no-occlusion variant ({@link GlintPipelines#GLOW_MASK_FLAT_PIPE})
     *  whose outline draws through walls — a per-glow developer option (CustomGlint.setEntityGlowSeeThrough),
     *  NOT a client setting. Default false = occluded. */
    public static RenderType glowMaskRT(Identifier texture, boolean seeThrough) {
        RenderType rt;
        if (seeThrough) {
            rt = BY_GLOW_MASK_FLAT.computeIfAbsent(texture, t ->
                    GlintPipelines.glowMaskTypeFlat(MOD_ID + ":glow_mask_flat_" + texTag(t), t));
        } else {
            rt = BY_GLOW_MASK.computeIfAbsent(texture, t ->
                    GlintPipelines.glowMaskType(MOD_ID + ":glow_mask_" + texTag(t), t, SCENE_DEPTH_ID));
        }
        registerFixed(rt);
        registerLiveFixedBuffer(rt);
        return rt;
    }

    /** Occluded glow-mask RT (the default). Convenience for the armor/item paths that don't yet carry a
     *  per-glow see-through flag. */
    public static RenderType glowMaskRT(Identifier texture) {
        return glowMaskRT(texture, false);
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
            int color, Identifier texture, float bbWidth, float bbHeight, boolean seeThrough,
            Object entityState) {
        if (texture == null || pose == null || !GlintClientConfig.entityOutlines()) return bodyBuffer;
        org.joml.Matrix4f m = pose.pose();
        double distSq = (double) m.m30() * m.m30() + (double) m.m31() * m.m31() + (double) m.m32() * m.m32();
        if (distSq > GlintClientConfig.outlineMaxDistanceSq()) return bodyBuffer; // too far: skip the outline
        int maxEnt = GlintClientConfig.outlineMaxEntities();
        if (maxEnt > 0 && bodyGlowCount >= maxEnt) return bodyBuffer; // entity cap reached this frame
        bodyGlowCount++;
        RenderType rt = glowMaskRT(texture, seeThrough);
        GLOW_BODY_FIXED.computeIfAbsent(rt, k -> new ByteBufferBuilder(k.bufferSize()));
        if (glowBodyBuffer == null) {
            glowBodyBuffer = MultiBufferSource.immediateWithBuffers(GLOW_BODY_FIXED, new ByteBufferBuilder(256));
        }
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        // Tight per-body silhouette AABB, filled as the body draws through the returned consumer (added to
        // bgTightBoxes by reference now; populated by drain time). Lets the drain scissor a lone glowing
        // entity's composite to its real silhouette, not the loose pose-origin box tracked below.
        float[] tight = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        VertexConsumer mask = new AABBTrackingConsumer(new FullColorOverrideConsumer(
                glowBodyBuffer.getBuffer(rt), r, g, b, glowKeyFor(entityState, CAT_ENTITY)), tight);
        bgTightBoxes.add(tight);
        // Generous camera-relative box (see EntityGlintRender.computeGroupScissor for the radius rationale).
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
        return com.mojang.blaze3d.vertex.VertexMultiConsumer.create(bodyBuffer, mask);
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
    public static void accumulatePartGlowMask(PoseStack pose, net.minecraft.client.model.geom.ModelPart part,
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
        Identifier loc = Identifier.fromNamespaceAndPath(MOD_ID,"glint/" + safePath);
        // 26.1: DynamicTexture needs a label supplier; wrap/filter (REPEAT + NEAREST) is no longer set
        // on the texture — GlintPipelines.glintSampler() supplies it per binding. See 26/09.
        DynamicTexture dt = new DynamicTexture(() -> MOD_ID + ":glint/" + safePath, gray);
        mc.getTextureManager().register(loc, dt);
        return loc;
    }

    // ── Render types ──────────────────────────────────────────────────────────

    /** Assigned by RenderBuffersMixin on RenderBuffers construction; null until then. */
    public static SequencedMap<RenderType, ByteBufferBuilder> fixedBufferRegistry;
    public static final ThreadLocal<ItemStack> CURRENT_ITEM_STACK = new ThreadLocal<>();
    public static final ThreadLocal<float[]> COLOR_BUF = ThreadLocal.withInitial(() -> new float[4]);

    /** Per-key mutable float[4] holders; RenderType lambdas close over these references and read them each frame. */
    private static final Map<String, float[]>    GLINT_COLORS          = new HashMap<>();
    private static final Map<String, RenderType> BY_GLINT              = new HashMap<>();
    private static final Map<String, RenderType> BY_ARMOR_GLINT        = new HashMap<>();
    private static final Map<String, RenderType> BY_HORSE_ARMOR_GLINT  = new HashMap<>();
    private static final Map<Identifier, RenderType> BY_BODY_DEPTH_FILL = new HashMap<>();

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
    // glint depth-test lands exactly on the armor depth. See the long depth-test history in 26/09
    // and the project_armor_glint_bleed_fix memory.
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

    /**
     * Depth-only fill of an entity model's full geometry silhouette, ignoring texture alpha.
     * Companion to {@link #doModelOutline} for IaF mount armor: IaF dragon / hippogryph /
     * hippocampus body textures have transparent regions (between scales, wing membranes,
     * feather gaps); the normal {@code entityCutoutNoCull} body render discards those fragments
     * so no depth is written for them. With nothing in the depth buffer at those pixels, the
     * outline TEST pass's LEQUAL test passes for back-side armor's dilated mesh — the player
     * sees the BACK armor outline through the FRONT of the mob. Running this RT against the
     * parent body model BEFORE doModelOutline writes depth at every geometry fragment so the
     * outline test is correctly occluded by the mob's full silhouette regardless of alpha.
     *
     * Uses RENDERTYPE_ENTITY_SOLID_SHADER (no alpha-discard) + DEPTH-only write mask + LEQUAL
     * depth. Bound texture is irrelevant since color isn't written; the shader still needs a
     * Sampler0 binding so pass any valid texture (callers pass the armor texture for simplicity).
     *
     * NOTE: no longer used by the mount-armor mixins. Writing depth across the full silhouette
     * (gaps included) leaves "invisible planes" — the gap pixels get the body's surface depth with
     * no colour, so world translucents (water, clouds, ice) and the mount's own far-side glint
     * drawn afterwards get depth-rejected there. The back-side-outline-through-gap leak it was meant
     * to fix is now handled in {@link #doModelOutline} by stamping the FULL geometry silhouette into
     * the stencil slot (stencil-only, no depth) for the {@code slot == null} mount/body path.
     */
    public static RenderType forBodyDepthFill(Identifier tex) {
        RenderType cached = BY_BODY_DEPTH_FILL.computeIfAbsent(tex, t -> {
            RenderType rt = GlintPipelines.entityMaskType(
                    MOD_ID + ":body_depth_fill|" + t.toString().hashCode(), GlintPipelines.BODY_DEPTH_FILL, t);
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

    // ── Outline rendering ─────────────────────────────────────────────────────

    /** Guards re-entrance: set true during outline stencil passes so applyGlint skips the item. */
    public static final ThreadLocal<Boolean> IN_OUTLINE = ThreadLocal.withInitial(() -> false);

    /**
     * Opt-out for the {@code slot == null} full-silhouette stencil WRITE in {@link #doModelOutline}.
     * That path stamps the whole body silhouette (white.png) so the outline ring wraps the entire
     * creature — correct for the IaF dragon (full-body barding, transparent wings). The hippocampus
     * armor only covers part of the body, so the full silhouette makes the glow wrap the whole
     * creature; it sets this true around its outline call to stamp the armor texture instead, so the
     * ring hugs the armor. Default false: dragon and hippogryph keep the full-silhouette behavior.
     * Callers must reset it (try/finally).
     */
    public static final ThreadLocal<Boolean> OUTLINE_HUG_TEXTURE = ThreadLocal.withInitial(() -> false);

    /**
     * Once-per-frame stencil clear gate. Set true at frame start by a RenderTickEvent.START
     * listener in CustomGlintClientInit; the first STENCIL_WRITE_LAYERING.setup of the frame
     * sees it true, calls glClear(STENCIL_BUFFER_BIT), and unsets it so later WRITE setups
     * (different texture → different cached RT) do NOT wipe earlier silhouettes from the
     * stencil buffer. Without this gate, under a shader mod's FullyBuffered drain (RT order is not
     * call order) a later WRITE.setup's glClear can erase an earlier item's stencil stamp,
     * causing its TEST pass (stencil EQUAL 0) to pass everywhere → filled-blob outline.
     * Reproducible in 3.5D FPM whenever a held outlined item shares the screen with a
     * different-texture outlined item (e.g. BEWLR + sprite).
     */
    public static volatile boolean pendingFrameStencilClear = true;

    /**
     * Optional gate consulted at the top of {@link #doModelOutline} and {@link #doItemOutline}.
     * Compat modules (e.g. First Person Mod's 3.5D view, where the player model is hidden/transformed
     * in ways the stencil pass can't track and the dilated outline geometry then renders unmasked)
     * install a supplier here to suppress outline passes for the current frame.
     */
    public static BooleanSupplier outlineSuppressor = () -> false;

    /**
     * FPM 3.5D detection gate for the shader-pack item outline path. Installed by
     * {@code FirstPersonClientCompat} when FPM is present. Returns true while FPM is actively
     * rendering the local player in 3.5D view (camera at eye, player body in 3P).
     *
     * Used in {@link #doItemOutline}'s shader-pack sprite branch to switch from the standard
     * eye-space Z push ({@code dz = 0.03}) to {@code dz = 0.0}. At FPM's near-1P camera
     * distance (~0.7 eye-space Z vs. ~3.0 for vanilla 3P), the same {@code -dz} translate
     * causes ~4× more perspective shrinkage on screen, visibly shifting the 4 translated
     * sprite copies toward screen-center (i.e. "misaligned" outline). Setting dz=0 eliminates
     * the shift and relies on the RT's baked {@code PUSH_BACK_LAYERING} polygon offset for
     * depth separation, exactly as the 1P hand-render path does.
     */
    public static BooleanSupplier fpmRenderingPlayerGate = () -> false;

    /** True if FPM (First Person Mod by tr7zw) is loaded and wired. Set by {@code FirstPersonClientCompat}. */
    public static boolean fpmPresent = false;

    /** Reload hooks appended by compat modules; invoked by {@link #clearTextures()} so each
     *  compat can release its own {@code DynamicTexture}s without {@code CustomGlintRenderer}
     *  needing to know about them. */
    public static final List<Runnable> additionalReloadCleanup = new CopyOnWriteArrayList<>();

    /**
     * Frame-scoped identity set for shader-mode outline deduplication when FPM is active.
     * FPM renders the local player's held items through multiple paths (entity pass + arm/hand
     * pass); the forward-pass outline path is additive, so the second render stacks a visible
     * second ring on top of the first. We deduplicate by {@link ItemStack} reference: the same
     * instance is passed through all render paths for a given inventory slot, so reference
     * equality correctly identifies "same item, same frame, different path."
     * Reset at frame start by {@code CustomGlintClientInit}.
     */
    public static final Set<Object> shaderOutlinedThisFrame =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /** Separate from BY_GLINT: outline uses POSITION_COLOR_TEX + OUTLINE_SHADER, not POSITION_TEX + GLINT_SHADER. */
    private static final Map<Identifier, RenderType> BY_SHADER_ARMOR_OUTLINE_TEX = new ConcurrentHashMap<>();
    private static final Map<Identifier, RenderType> BY_OUTLINE_WRITE = new HashMap<>();
    private static final Map<Identifier, RenderType> BY_OUTLINE_WRITE_ITEM = new HashMap<>();
    private static final Map<Identifier, RenderType> BY_OUTLINE_TEST  = new HashMap<>();
    private static final Map<Identifier, RenderType> BY_OUTLINE_TEST_CULLED = new HashMap<>();

    // ── Per-outline stencil-value isolation (slot pool) ────────────────────────
    //
    // The 8-bit stencil buffer holds values 0..255. We reserve 0 for "no silhouette"
    // and allocate values 1..255 as unique "slots" — one per outline call per frame.
    //   • WRITE shard for slot V: stamp stencil=V at this object's silhouette.
    //   • TEST  shard for slot V: draw the dilated ring where stencil != V.
    // Other objects' silhouettes (stencil=V_other) PASS the != V test, so the dilation
    // is allowed to draw on top of them — depth-test still gates per-pixel, so the ring
    // is hidden where it's physically occluded.
    //
    // Each slot has its own RenderType instance. Iris/Oculus's
    // FullyBufferedMultiBufferSource queues draws and at endBatch() drains them in
    // GraphTranslucencyRenderOrderManager order: WRITEs in GENERAL_TRANSPARENT bucket
    // (per-slot call order via digraph edges within the active entity group), TESTs in
    // the LINES bucket (tagAsLateRenderForShaders) so all writes complete first. Each RT
    // drains independently with its own setup() → stencilFunc(V) → draw → clear(). Two
    // identical swords get different slots → different RTs → independent stencils.
    //
    // Ceiling = 255 isolated outlines per frame. Beyond that, slot wraps to 1, sharing
    // a stencil value (= cross-contamination with the colliding earlier outline only).
    //
    // Counter is reset to 0 at frame start by CustomGlintClientInit's RenderTickEvent.START.
    private static int stencilSlotCounter = 0;

    /** Reserve a unique stencil slot value (1..255) for this outline call. Wraps if exceeded. */
    public static int nextStencilSlot() {
        stencilSlotCounter++;
        if (stencilSlotCounter > 255) stencilSlotCounter = 1;
        return stencilSlotCounter;
    }

    /** Frame-start reset; called from CustomGlintClientInit. */
    public static void resetStencilSlots() { stencilSlotCounter = 0; }

    // 26.1: the per-slot WRITE/TEST stencil state is baked into immutable pipelines
    // (GlintPipelines.outlineWritePipe/outlineTestPipe) — the old raw-GL LayeringStateShards and the
    // per-slot mutable texture holders are gone. Texture is fixed at RenderType.create, so RTs are
    // cached per (slot, texture). Output is forced to the main target via OutputTarget.MAIN_TARGET
    // in GlintPipelines.outlineType (replaces FORCE_MAIN_TARGET).
    private static final Map<String, RenderType> OUTLINE_SLOT_RT = new HashMap<>();

    private static RenderType outlineSlotRT(String tag, RenderPipeline pipe, Identifier tex, boolean lateTag) {
        RenderType rt = OUTLINE_SLOT_RT.computeIfAbsent(tag, k -> {
            RenderType r = GlintPipelines.outlineType(MOD_ID + ":" + k, pipe, tex);
            registerFixed(r);
            return r;
        });
        registerLiveFixedBuffer(rt);
        // LINES bucket → drains AFTER all writes under shader-mod FullyBuffered.
        if (lateTag) tagAsLateRenderForShaders(rt);
        return rt;
    }

    /** Slot-based WRITE RT for armor (polygon-offset variant, matches armor_cutout_no_cull depth). */
    public static RenderType forOutlineStencilWrite(int v, Identifier texture) {
        return outlineSlotRT("glint_outline_write_v" + v + "_" + texture.toString().hashCode(),
                GlintPipelines.outlineWritePipe(v, true), texture, false);
    }

    /** Slot-based WRITE RT for items (no polygon offset). */
    public static RenderType forOutlineStencilWriteItem(int v, Identifier texture) {
        return outlineSlotRT("glint_outline_write_item_v" + v + "_" + texture.toString().hashCode(),
                GlintPipelines.outlineWritePipe(v, false), texture, false);
    }

    /** Slot-based TEST RT. */
    public static RenderType forOutlineStencilTest(int v, Identifier texture) {
        return outlineSlotRT("glint_outline_test_v" + v + "_" + texture.toString().hashCode(),
                GlintPipelines.outlineTestPipe(v), texture, true);
    }

    /** Slot-based TEST RT, elytra-cape variant. NOTE: the old front-face cull (raw glCullFace(FRONT))
     *  has no declarative equivalent in the immutable pipeline; using the standard TEST pipe for now —
     *  revisit the cape inner-face look in-game (26/09). */
    public static RenderType forOutlineStencilTestCulled(int v, Identifier texture) {
        return outlineSlotRT("glint_outline_test_culled_v" + v + "_" + texture.toString().hashCode(),
                GlintPipelines.outlineTestPipe(v), texture, true);
    }

    // Per-RT flush that survives Iris's post-shader-toggle buffer-source wrapper. Vanilla
    // BufferSource and FullyBufferedMultiBufferSource both extend MultiBufferSource$BufferSource,
    // so the direct cast works for the working states. AFTER an Iris pack toggle (activate then
    // deactivate), EntityRenderDispatcher receives a synthetic lambda wrapper that does NOT
    // extend BufferSource — `if (buffer instanceof BufferSource bs) bs.endBatch(rt)` silently
    // skipped at every per-slot flush, leaving WRITE/TEST geometry queued without per-slot
    // ordering → stencil collisions → "lens through walls" outlines. Fall back to the global
    // bufferSource (the canonical singleton the wrapper delegates to under the hood) so the
    // underlying BufferBuilders the geometry was written to actually drain.
    private static void flushRT(MultiBufferSource buffer, RenderType rt) {
        if (buffer instanceof MultiBufferSource.BufferSource bs) { bs.endBatch(rt); return; }
        try {
            MultiBufferSource.BufferSource g = Minecraft.getInstance().renderBuffers().bufferSource();
            if (g != null) g.endBatch(rt);
        } catch (Throwable ignored) {}
    }
    private static void flushAll(MultiBufferSource buffer) {
        if (buffer instanceof MultiBufferSource.BufferSource bs) { bs.endBatch(); return; }
        try {
            MultiBufferSource.BufferSource g = Minecraft.getInstance().renderBuffers().bufferSource();
            if (g != null) g.endBatch();
        } catch (Throwable ignored) {}
    }

    /**
     * Clears the main render target's stencil buffer once per frame, on the first outline of the frame
     * (gated by {@link #pendingFrameStencilClear}, re-armed each frame in {@code CustomGlintClientInit}'s
     * RenderFrameEvent.Pre). 26.1's per-frame target clear ({@code RenderTargetDescriptor.prepare}) only
     * clears stencil on transient framegraph targets — the MAIN target's stencil aspect is added late by
     * {@code ConfigureMainRenderTargetEvent.enableStencil()} and is NOT in vanilla's main clear, so it
     * accumulates stale slot values across entities and frames. Without this, the per-slot
     * {@code NOT_EQUAL}/{@code EQUAL} stencil tests read garbage and the outline ring shows only from
     * angles where the stale stencil happens to line up. Must run outside a render pass (call it between
     * batches, after {@link #flushAll}); {@code clearStencilTexture} clears only the stencil aspect, so
     * scene depth is untouched.
     */
    private static void clearStencilIfPending() {
        if (!pendingFrameStencilClear) return;
        pendingFrameStencilClear = false;
        clearMainStencil();
    }

    /**
     * Clears the main render target's stencil to 0. MUST be called OUTSIDE any render pass — in 26.1
     * {@code clearStencilTexture} throws if a render pass is active. The stencil-ring outline replays
     * INSIDE the framegraph's main pass (via the {@code submitCustomGeometry} callback), so calling this
     * from {@link #clearStencilIfPending} there silently failed (caught below) → the main stencil was
     * never cleared → stale slot values accumulated frame-to-frame and the {@code != slot} TEST read
     * garbage → the outline "appeared only from certain angles". Fix: call this from
     * {@code RenderFrameEvent.Pre} (outside any pass) once per frame instead. See 26/13-outlines.md.
     * {@code clearStencilTexture} touches only the stencil aspect, and vanilla's per-frame depth clear
     * leaves stencil alone, so a 0 written here survives until our WRITE pass.
     */
    public static void clearMainStencil() {
        try {
            com.mojang.blaze3d.pipeline.RenderTarget mrt = Minecraft.getInstance().getMainRenderTarget();
            com.mojang.blaze3d.textures.GpuTexture depth = mrt == null ? null : mrt.getDepthTexture();
            if (depth != null && depth.getFormat().hasStencilAspect()) {
                RenderSystem.getDevice().createCommandEncoder().clearStencilTexture(depth, 0);
            }
        } catch (Throwable ignored) {
            // Defensive: never let a stencil-clear failure break the outline draw.
        }
    }

    // ── Shader-pack forward-pass outline factories (26.1: delegate to GlintPipelines.forward*) ───
    // 26.1: the old RenderStateShard layering/write/depth shards (NO_WRITE, COLOR_ONLY_WRITE,
    // CULL_FRONT[_PUSH_BACK]_LAYERING, PUSH_BACK[_DEPTH_RANGE]_LAYERING, LESS_DEPTH_TEST) that drove
    // these by mutating GL stencil/cull/polygon-offset/depth-range inside per-RenderType runnables are
    // gone — GPU state is immutable now. The cull-front / glDepthRange / normal-push / LESS-vs-LEQUAL
    // nuances have no declarative equivalent and are baked (collapsed) into the FORWARD_OUTLINE*
    // pipelines in GlintPipelines. Shader-pack-only; expect in-game tuning (Iris/Sodium).
    // The cull-front / glDepthRange / normal-push / LESS-vs-LEQUAL nuances of the old shards have no
    // declarative equivalent and are collapsed into the FORWARD_OUTLINE* pipelines. Shader-pack-only;
    // expect in-game tuning (Iris/Sodium). Signatures + caches + late-render tagging preserved.
    private static RenderType shaderOutlineReg(RenderType rt) {
        registerFixed(rt);
        registerLiveFixedBuffer(rt);
        tagAsLateRenderForShaders(rt);
        return rt;
    }
    private static String texTag(Identifier t) {
        return t.getNamespace() + "_" + t.getPath().replace('/', '_');
    }

    private static RenderType SHADER_OUTLINE_ARMOR_TYPE;
    public static RenderType forShaderArmorOutline() {
        if (SHADER_OUTLINE_ARMOR_TYPE == null)
            SHADER_OUTLINE_ARMOR_TYPE = GlintPipelines.forwardOutline(MOD_ID + ":shader_outline_armor");
        return shaderOutlineReg(SHADER_OUTLINE_ARMOR_TYPE);
    }

    private static RenderType SHADER_OUTLINE_ITEM_NP_TYPE;
    public static RenderType forShaderItemOutlineNormalPush() {
        if (SHADER_OUTLINE_ITEM_NP_TYPE == null)
            SHADER_OUTLINE_ITEM_NP_TYPE = GlintPipelines.forwardOutline(MOD_ID + ":shader_outline_item_np");
        return shaderOutlineReg(SHADER_OUTLINE_ITEM_NP_TYPE);
    }

    private static final Map<Identifier, RenderType> BY_SHADER_SPRITE_NP = new HashMap<>();
    public static RenderType forShaderSpriteOutlineNormalPush(Identifier tex) {
        return shaderOutlineReg(BY_SHADER_SPRITE_NP.computeIfAbsent(tex, t ->
                GlintPipelines.forwardOutlineEntity(MOD_ID + ":shader_sprite_outline_np_" + texTag(t), t)));
    }

    private static RenderType SHADER_OUTLINE_ITEM_FPM_TYPE;
    public static RenderType forShaderItemOutlineFpm() {
        if (SHADER_OUTLINE_ITEM_FPM_TYPE == null)
            SHADER_OUTLINE_ITEM_FPM_TYPE = GlintPipelines.forwardOutline(MOD_ID + ":shader_outline_item_fpm");
        return shaderOutlineReg(SHADER_OUTLINE_ITEM_FPM_TYPE);
    }

    public static RenderType forShaderArmorOutlineTextured(Identifier texture) {
        return shaderOutlineReg(BY_SHADER_ARMOR_OUTLINE_TEX.computeIfAbsent(texture, tex ->
                GlintPipelines.forwardOutlineEntity(MOD_ID + ":shader_outline_armor_tex_" + texTag(tex), tex)));
    }

    private static final Map<Identifier, RenderType> BY_SHADER_HELD_SPRITE_OUTLINE = new ConcurrentHashMap<>();
    public static RenderType forShaderHeldSpriteOutline(Identifier texture) {
        return shaderOutlineReg(BY_SHADER_HELD_SPRITE_OUTLINE.computeIfAbsent(texture, tex ->
                GlintPipelines.forwardOutlineTex(MOD_ID + ":shader_held_sprite_outline_" + texTag(tex), tex)));
    }

    private static final Map<Identifier, RenderType> BY_SHELL_OUTLINE_TEXTURED = new HashMap<>();
    public static RenderType forShellOutlineTextured(Identifier texture) {
        return shaderOutlineReg(BY_SHELL_OUTLINE_TEXTURED.computeIfAbsent(texture, tex ->
                GlintPipelines.forwardOutlineTex(MOD_ID + ":shell_outline_textured_" + texTag(tex), tex)));
    }

    private static RenderType SHADER_OUTLINE_TYPE;
    public static RenderType forShaderOutline() {
        if (SHADER_OUTLINE_TYPE == null)
            SHADER_OUTLINE_TYPE = GlintPipelines.forwardOutline(MOD_ID + ":shader_outline");
        return shaderOutlineReg(SHADER_OUTLINE_TYPE);
    }

    private static final Map<Identifier, RenderType> SHADER_SPRITE_OUTLINE_TYPES = new ConcurrentHashMap<>();
    public static RenderType forShaderSpriteOutline(Identifier tex) {
        return shaderOutlineReg(SHADER_SPRITE_OUTLINE_TYPES.computeIfAbsent(tex, t ->
                GlintPipelines.forwardOutlineEntity(MOD_ID + ":shader_sprite_outline_" + texTag(t), t)));
    }

    // Flat-color sprite outline RT for the shader-pack path. POSITION_COLOR (no texture sample)
    // so the shader's gbuffers_basic-style mapping emits pure outlineColor pixels instead of
    // outlineColor × spriteTexel. Avoids the "second copy of the textured sprite" artifact
    // that the textured variant (forShaderSpriteOutline) produces under shaders, where the
    // additive blend state is ignored by the deferred composite. Translated copies + LEQUAL +
    // PUSH_BACK_LAYERING reject inside the original sprite's depth → ring at silhouette edges.
    private static RenderType SHADER_SPRITE_OUTLINE_FLAT_TYPE;
    public static RenderType forShaderSpriteOutlineFlat() {
        if (SHADER_SPRITE_OUTLINE_FLAT_TYPE == null)
            SHADER_SPRITE_OUTLINE_FLAT_TYPE = GlintPipelines.forwardOutline(MOD_ID + ":shader_sprite_outline_flat");
        return shaderOutlineReg(SHADER_SPRITE_OUTLINE_FLAT_TYPE);
    }

    /** GUI flat-item outline RT — vanilla OUTLINE shader masks the BLOCKS atlas alpha → glow-color
     *  silhouette of the sprite. Plain (no stencil), main target. */
    private static RenderType GUI_ITEM_OUTLINE_TYPE;
    public static RenderType forGuiItemOutline() {
        if (GUI_ITEM_OUTLINE_TYPE == null)
            GUI_ITEM_OUTLINE_TYPE = GlintPipelines.plainOutlineType(MOD_ID + ":gui_item_outline", TextureAtlas.LOCATION_BLOCKS);
        registerFixed(GUI_ITEM_OUTLINE_TYPE);
        registerLiveFixedBuffer(GUI_ITEM_OUTLINE_TYPE);
        return GUI_ITEM_OUTLINE_TYPE;
    }

    /** GUI outline RT for 3D BEWLR items — binds white.png so the OUTLINE shader sees alpha==1 across
     *  the whole model and emits a solid glow silhouette. */
    private static RenderType GUI_BEWLR_OUTLINE_TYPE;
    public static RenderType forGuiBewlrOutline() {
        if (GUI_BEWLR_OUTLINE_TYPE == null)
            GUI_BEWLR_OUTLINE_TYPE = GlintPipelines.plainOutlineType(MOD_ID + ":gui_bewlr_outline",
                    Identifier.withDefaultNamespace("textures/misc/white.png"));
        registerFixed(GUI_BEWLR_OUTLINE_TYPE);
        registerLiveFixedBuffer(GUI_BEWLR_OUTLINE_TYPE);
        return GUI_BEWLR_OUTLINE_TYPE;
    }

    /** Per-texture GUI BEWLR outline (IaF troll weapons): binds the variant texture for alpha-discard. */
    private static final Map<Identifier, RenderType> GUI_BEWLR_OUTLINE_TEXTURED = new ConcurrentHashMap<>();
    public static RenderType forGuiBewlrOutlineTextured(Identifier texture) {
        RenderType rt = GUI_BEWLR_OUTLINE_TEXTURED.computeIfAbsent(texture, tex -> {
            RenderType r = GlintPipelines.plainOutlineType(MOD_ID + ":gui_bewlr_outline_tex_" + texTag(tex), tex);
            registerFixed(r);
            return r;
        });
        registerLiveFixedBuffer(rt);
        return rt;
    }

    /**
     * VertexConsumer wrapper for the shader-pack forward outline path. Underlying buffer is
     * POSITION_COLOR only; entity models call vertex().color().uv().overlayCoords().uv2().normal().endVertex().
     * We forward position + color + endVertex, override the color with a fixed RGBA (outline color),
     * and silently swallow uv/overlay/uv2/normal so the buffer's format constraints aren't violated.
     */
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

    /** VertexConsumer that swallows every call. Used to drive geometry through AABBTrackingConsumer
     *  without actually rendering anything (shader-pack outline first pass: just capture bounds). */
    public static final class NullConsumer extends WrappingConsumer {
        public NullConsumer() {}
        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer addVertex(Matrix4fc m, float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(float r, float g, float b, float a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }

    private static final class PositionColorOnlyConsumer extends WrappingConsumer {
        private final VertexConsumer wrapped;
        private final int r, g, b, a;
        PositionColorOnlyConsumer(VertexConsumer wrapped, int r, int g, int b, int a) {
            this.wrapped = wrapped; this.r = r; this.g = g; this.b = b; this.a = a;
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) { wrapped.addVertex(x, y, z); return this; }
        @Override public VertexConsumer addVertex(Matrix4fc m, float x, float y, float z) { wrapped.addVertex(m, x, y, z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { wrapped.setColor(this.r, this.g, this.b, this.a); return this; }
        @Override public VertexConsumer setColor(float r, float g, float b, float a) { wrapped.setColor(this.r, this.g, this.b, this.a); return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }

    /**
     * VertexConsumer that pushes each vertex {@code push} blocks along its world-space normal before
     * emitting POSITION_COLOR output. Used by {@link #forShaderItemOutlineNormalPush} for the
     * FPM/1P shader-pack item outline. Unlike the centroid-scale approach, each face's vertices
     * move along their own normals — back faces (normals away from camera) always increase in depth
     * regardless of camera angle or model shape, eliminating z-fighting on non-convex items.
     *
     * Expects vertices in world/render space (after pose matrix applied) and world-space normals
     * (after normal matrix applied) — exactly what item rendering provides via
     * {@code vertex(Matrix4f pose, x,y,z)} and {@code normal(Matrix3f normalMat, nx,ny,nz)}.
     */
    private static final class NormalPushConsumer extends WrappingConsumer {
        private final VertexConsumer wrapped;
        private final int r, g, b, a;
        private final float push;
        private float vx, vy, vz;
        private float nx = 0, ny = 0, nz = 1;

        NormalPushConsumer(VertexConsumer wrapped, int r, int g, int b, int a, float push) {
            this.wrapped = wrapped; this.r = r; this.g = g; this.b = b; this.a = a; this.push = push;
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            vx = (float)x; vy = (float)y; vz = (float)z; return this;
        }
        @Override public VertexConsumer addVertex(Matrix4fc m, float x, float y, float z) {
            Vector4f v = m.transform(new Vector4f(x, y, z, 1.0f));
            vx = v.x; vy = v.y; vz = v.z; return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(float r, float g, float b, float a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            nx = x; ny = y; nz = z;
            float len = (float) Math.sqrt(nx*nx + ny*ny + nz*nz);
            float inv = len > 1e-4f ? push / len : 0f;
            wrapped.addVertex(vx + nx*inv, vy + ny*inv, vz + nz*inv);
            wrapped.setColor(r, g, b, a);
            nx = ny = nz = 0f;
            return this;
        }
    }

    /**
     * Textured companion to {@link NormalPushConsumer}. NEW_ENTITY vertex format requires
     * position, color, uv, overlay, uv2, normal — all forwarded except color (overridden).
     * UV forwarding is mandatory so the texture's alpha-discard preserves the sprite silhouette;
     * without it, the voxel mesh fills its entire bounding rectangle.
     */
    private static class NormalPushTexturedConsumer extends WrappingConsumer {
        private final VertexConsumer wrapped;
        private final int r, g, b, a;
        private final float push;
        private float vx, vy, vz;
        private float nx = 0, ny = 0, nz = 1;
        private float u, v;
        private int ox, oy, lu, lv;

        NormalPushTexturedConsumer(VertexConsumer wrapped, int r, int g, int b, int a, float push) {
            this.wrapped = wrapped; this.r = r; this.g = g; this.b = b; this.a = a; this.push = push;
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            vx = (float)x; vy = (float)y; vz = (float)z; return this;
        }
        @Override public VertexConsumer addVertex(Matrix4fc m, float x, float y, float z) {
            Vector4f t = m.transform(new Vector4f(x, y, z, 1.0f));
            vx = t.x; vy = t.y; vz = t.z; return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(float r, float g, float b, float a) { return this; }
        @Override public VertexConsumer setUv(float uu, float vv) { this.u = uu; this.v = vv; return this; }
        @Override public VertexConsumer setUv1(int x, int y) { this.ox = x; this.oy = y; return this; }
        @Override public VertexConsumer setUv2(int x, int y) { this.lu = x; this.lv = y; return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            nx = x; ny = y; nz = z;
            float len = (float) Math.sqrt(nx*nx + ny*ny + nz*nz);
            float inv = len > 1e-4f ? push / len : 0f;
            wrapped.addVertex(vx + nx*inv, vy + ny*inv, vz + nz*inv);
            wrapped.setColor(r, g, b, a);
            wrapped.setUv(u, v);
            wrapped.setUv1(ox, oy);
            wrapped.setUv2(lu, lv);
            wrapped.setNormal(nx, ny, nz);
            nx = ny = 0f; nz = 1f;
            return this;
        }
    }

    /**
     * Textured frontface filter. Reads each vertex's eye-space normal; if |nz| (the
     * camera-axis component) is below the threshold the vertex belongs to a side-face
     * quad of the voxelization extrusion and gets collapsed to the AABB centroid so its
     * quad degenerates to a point and emits zero fragments. Front-face vertices pass
     * through unchanged.
     *
     * Used together with a pose-stack scale-around-centroid dilation applied to the caller's
     * poseStack BEFORE renderStatic: the surviving front-face quads draw at 1.10× their
     * original eye-space extent, producing a clean outline ring around the projected sprite
     * silhouette. Side faces — which on a tilted FPM held item would otherwise visually
     * dominate as a "standing up" outline of the slab's thin extrusion edge — contribute
     * nothing.
     */
    /**
     * Keeps only the EXTRUDED side faces of a voxelized 2D sprite; drops the front/back 2D face
     * quads. Decision is based on the quad's MODEL-SPACE Z extent (which is pose-invariant)
     * rather than the surface normal (which is eye-space after {@code pose.normal()} is applied
     * by {@code VertexConsumer.putBulkData}, so unusable for this purpose — 1P transforms like
     * the sword's {@code -90° Y, +25° Z} rotate model Z entirely out of eye Z).
     *
     * Why: vanilla voxelizes flat sprite items (swords, tools, apples, ingots) by extruding the
     * sprite along the model Z axis — front face at {@code mz≈0}, back face at {@code mz≈1/16},
     * side faces span both. Under the model-space 4-translate outline path, the front/back face
     * quads are full sprite-shaped, and a 1-pixel additive translate of them overlays the item's
     * interior with outline color (the "wrapped" look). The side faces form a thin perimeter
     * band around the silhouette; translating only those by 1 pixel produces a clean ring.
     *
     * Mechanism: buffer all 4 vertices of a quad, check {@code maxMz − minMz}. If small (< eps),
     * the quad is a 2D face → collapse all 4 vertices to the origin → degenerate quad → zero
     * fragments. If large, it's a side face → forward the 4 vertices unchanged.
     */
    private static class SideFaceOnlyConsumer extends WrappingConsumer {
        private final VertexConsumer wrapped;
        private final int r, g, b, a;
        private final float zExtentThreshold;
        // true → keep side-face (extruded) quads, drop 2D face quads.
        // false → keep 2D face quads, drop side-face quads.
        private final boolean keepSides;

        // Buffered per-vertex data for the in-flight quad.
        private final float[] mx = new float[4], my = new float[4], mz = new float[4];
        private final float[] tx = new float[4], ty = new float[4], tz = new float[4];
        private final float[] u = new float[4], v = new float[4];
        private final int[] ox = new int[4], oy = new int[4], lu = new int[4], lv = new int[4];
        private final float[] nx = new float[4], ny = new float[4], nz = new float[4];
        private int idx = 0;

        // In-flight vertex (between vertex() and endVertex()).
        private float cMx, cMy, cMz, cTx, cTy, cTz;
        private float cU, cV;
        private int cOx, cOy, cLu, cLv;
        private float cNx = 0, cNy = 0, cNz = 1;

        SideFaceOnlyConsumer(VertexConsumer wrapped, int r, int g, int b, int a, float zExtentThreshold, boolean keepSides) {
            this.wrapped = wrapped; this.r = r; this.g = g; this.b = b; this.a = a;
            this.zExtentThreshold = zExtentThreshold;
            this.keepSides = keepSides;
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            cMx = (float)x; cMy = (float)y; cMz = (float)z;
            cTx = cMx; cTy = cMy; cTz = cMz;
            return this;
        }
        @Override public VertexConsumer addVertex(Matrix4fc m, float x, float y, float z) {
            cMx = x; cMy = y; cMz = z;
            Vector4f t = m.transform(new Vector4f(x, y, z, 1.0f));
            cTx = t.x; cTy = t.y; cTz = t.z;
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(float r, float g, float b, float a) { return this; }
        @Override public VertexConsumer setUv(float uu, float vv) { this.cU = uu; this.cV = vv; return this; }
        @Override public VertexConsumer setUv1(int x, int y) { this.cOx = x; this.cOy = y; return this; }
        @Override public VertexConsumer setUv2(int x, int y) { this.cLu = x; this.cLv = y; return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            cNx = x; cNy = y; cNz = z;
            mx[idx] = cMx; my[idx] = cMy; mz[idx] = cMz;
            tx[idx] = cTx; ty[idx] = cTy; tz[idx] = cTz;
            u[idx] = cU; v[idx] = cV;
            ox[idx] = cOx; oy[idx] = cOy; lu[idx] = cLu; lv[idx] = cLv;
            nx[idx] = cNx; ny[idx] = cNy; nz[idx] = cNz;
            idx++;
            // reset in-flight normal for next vertex
            cNx = cNy = 0; cNz = 1;

            if (idx == 4) {
                float minMz = Math.min(Math.min(mz[0], mz[1]), Math.min(mz[2], mz[3]));
                float maxMz = Math.max(Math.max(mz[0], mz[1]), Math.max(mz[2], mz[3]));
                boolean isSideFace = (maxMz - minMz) > zExtentThreshold;
                boolean keep = keepSides ? isSideFace : !isSideFace;
                for (int i = 0; i < 4; i++) {
                    if (keep) {
                        wrapped.addVertex(tx[i], ty[i], tz[i]);
                    } else {
                        // Collapse to a single point → degenerate quad → zero fragments.
                        wrapped.addVertex(0f, 0f, 0f);
                    }
                    wrapped.setColor(r, g, b, a);
                    wrapped.setUv(u[i], v[i]);
                    wrapped.setUv1(ox[i], oy[i]);
                    wrapped.setUv2(lu[i], lv[i]);
                    wrapped.setNormal(nx[i], ny[i], nz[i]);
                }
                idx = 0;
            }
            return this;
        }
    }

    private static class FrontFaceFilterConsumer extends WrappingConsumer {
        private final VertexConsumer wrapped;
        private final int r, g, b, a;
        private final float cx, cy, cz;
        private final float normalThreshold;
        private float vx, vy, vz;
        private float nx = 0, ny = 0, nz = 1;
        private float u, v;
        private int ox, oy, lu, lv;

        FrontFaceFilterConsumer(VertexConsumer wrapped, int r, int g, int b, int a,
                                float cx, float cy, float cz, float normalThreshold) {
            this.wrapped = wrapped; this.r = r; this.g = g; this.b = b; this.a = a;
            this.cx = cx; this.cy = cy; this.cz = cz; this.normalThreshold = normalThreshold;
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            vx = (float)x; vy = (float)y; vz = (float)z; return this;
        }
        @Override public VertexConsumer addVertex(Matrix4fc m, float x, float y, float z) {
            Vector4f t = m.transform(new Vector4f(x, y, z, 1.0f));
            vx = t.x; vy = t.y; vz = t.z; return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(float r, float g, float b, float a) { return this; }
        @Override public VertexConsumer setUv(float uu, float vv) { this.u = uu; this.v = vv; return this; }
        @Override public VertexConsumer setUv1(int x, int y) { this.ox = x; this.oy = y; return this; }
        @Override public VertexConsumer setUv2(int x, int y) { this.lu = x; this.lv = y; return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            nx = x; ny = y; nz = z;
            if (Math.abs(nz) >= normalThreshold) {
                wrapped.addVertex(vx, vy, vz);
            } else {
                // All 4 vertices of a side-face quad share the same normal so all 4
                // collapse to (cx,cy,cz) → degenerate quad, zero fragments.
                wrapped.addVertex(cx, cy, cz);
            }
            wrapped.setColor(r, g, b, a);
            wrapped.setUv(u, v);
            wrapped.setUv1(ox, oy);
            wrapped.setUv2(lu, lv);
            wrapped.setNormal(nx, ny, nz);
            nx = ny = 0f; nz = 1f;
            return this;
        }
    }

    // Force-binds the vanilla main render target (which has a stencil attachment via the shader
    // mod's stencil-enabling mixin) and restores the previously-bound FBO on clear.
    // Why: vanilla's MAIN_TARGET OutputStateShard is a no-op runnable that assumes the main FBO
    // is already bound. Under the shader mod's HAND_SOLID phase the gbuffer FBO is bound instead —
    // it has no stencil attachment, so stencil ops are silently dropped and our outline draws
    // the dilated mesh inside the silhouette (visible as a filled plane). Capturing the previous
    // FBO and restoring it on clear keeps the shader pipeline state intact.
    // (26.1: FORCE_MAIN_TARGET removed — output routing is RenderSetup's OutputTarget.MAIN_TARGET.)


    // 26.1: the single-arg legacy outline factories (ref=1 write / EQUAL-0 test) now delegate to
    // slot 1 of the slot-based pipeline scheme. The armor variant keeps the polygon offset.
    public static RenderType forOutlineStencilWrite(Identifier texture) {
        return forOutlineStencilWrite(1, texture);
    }
    public static RenderType forOutlineStencilWriteItem(Identifier texture) {
        return forOutlineStencilWriteItem(1, texture);
    }
    public static RenderType forOutlineStencilTest(Identifier texture) {
        return forOutlineStencilTest(1, texture);
    }
    public static RenderType forOutlineStencilTestCulled(Identifier texture) {
        return forOutlineStencilTestCulled(1, texture);
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

    /**
     * Stencil-based colored outline for entity/armor models.
     *
     * Pass 1 (stencil write): render model with colorMask=false so only the stencil buffer is
     * written. Stencil func is ALWAYS so the stencil test never blocks a write. StencilOp uses
     * dpfail=REPLACE (not KEEP) — armor is rendered with VIEW_OFFSET_Z_LAYERING (polygonOffset
     * -1,-10), which shifts its depth slightly toward the camera. Our stencil pass renders the same
     * geometry without that offset, so its fragments sit slightly BEHIND the already-written armor
     * depth. That causes LEQUAL depth test to fail. With dpfail=KEEP the stencil would never be
     * written and the outline pass (stencil==0) would render everywhere → solid face. dpfail=REPLACE
     * writes stencil=1 regardless of depth test outcome, correctly marking the model's silhouette.
     *
     * Pass 2 (outline): renders scaled model (1.04× — 4% larger) only where stencil==0, i.e.
     * the ring of pixels outside the original silhouette.2
     *
     * texture: caller should pass the actual armor layer texture (not SOLID) so the outline shader
     * discards transparent pixels (areas with no armor coverage) and the ring follows the real armor
     * shape instead of the full model-bone geometry.
     *
     * TRIED, did not work:
     * - SOLID texture: stencil/outline follows full bone geometry (arms extend past pauldrons).
     * - dpfail=KEEP: polygon-offset depth mismatch causes stencil pass to always fail → solid face.
     * - scale 1.06: outline too thick for armor; 1.025 too thin; 1.04 is the current sweet spot.
     */
    /**
     * Marker RenderType used purely as the bucket key for outline {@code submitCustomGeometry} nodes.
     * It is never drawn into (the custom-geometry callback ignores the supplied buffer and runs the
     * stencil outline against the live {@code renderBuffers().bufferSource()} directly). It is solid
     * ({@link GlintPipelines#BODY_DEPTH_FILL} has no blend), so {@code CustomFeatureRenderer.renderSolid}
     * runs the callback in the solid feature phase — after {@code ModelFeatureRenderer} has drawn every
     * entity/armor body model for that order, i.e. depth is committed before the outline tests it.
     */
    private static RenderType OUTLINE_TRIGGER;
    public static RenderType outlineTriggerType() {
        if (OUTLINE_TRIGGER == null) {
            OUTLINE_TRIGGER = GlintPipelines.entityMaskType(MOD_ID + ":outline_trigger",
                    GlintPipelines.BODY_DEPTH_FILL, Identifier.withDefaultNamespace("textures/misc/white.png"));
        }
        return OUTLINE_TRIGGER;
    }

    public static void doModelOutline(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, EntityModel<?> model, Identifier texture, Data glint, EquipmentSlot slot) {
        doModelOutline(poseStack, buffer, packedLight, model, texture, glintOutlineColor(glint), slot);
    }

    /** Stack-aware overload: pulls outline color from glowColors or glint layer 0 on the stack. */
    public static void doModelOutline(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, EntityModel<?> model, Identifier texture, ItemStack stack, EquipmentSlot slot) {
        doModelOutline(poseStack, buffer, packedLight, model, texture, glintOutlineColor(stack), slot);
    }

    public static void doModelOutline(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, EntityModel<?> model, Identifier texture, int color, EquipmentSlot slot) {
        // Don't run outline geometry during the shader mod's shadow pass — wrong buffer format → endVertex crash.
        if (isInShadowPass()) return;
        if (outlineSuppressor.getAsBoolean()) return;
        float oR = ((color >> 16) & 0xFF) / 255.0f;
        float oG = ((color >>  8) & 0xFF) / 255.0f;
        float oB = ( color        & 0xFF) / 255.0f;

        // Under an active shader pack: stencil path is dead (no OUTLINE entry in ShaderKey enum),
        // and entity-outline-target routing depends on shaderpack final composite sampling that
        // target — most don't. Forward-pass approach: render dilated model with POSITION_COLOR
        // shader (universal shader-mod mapping). Result is a flat-colored silhouette drawn 1.04× larger,
        // visible as a ring of pure color around the actual item. Shaderpack lighting still tints
        // the color since this goes through gbuffers_basic.
        //
        // ⚠ Gate is `isShaderPackActive()` only — NOT `|| isShaderModInstalled()`. Forward-pass uses
        // POSITION_COLOR with no texture, so the dilated silhouette traces the full HumanoidModel
        // bone geometry (head/body/arms/legs) instead of the armored coverage. Under
        // shader-mod-installed-no-pack the stencil path below works correctly because both passes
        // are driven through fresh local BufferBuilders + RenderType.end(...) — bypassing
        // FullyBufferedMultiBufferSource (whose endBatch(RenderType) is a no-op).
        // Items still gate on isShaderPackActive() only for a different reason —
        // see doItemOutline / doBewlrOutline.
        if (isShaderPackActive()) {
            int rByte = (color >> 16) & 0xFF;
            int gByte = (color >>  8) & 0xFF;
            int bByte =  color        & 0xFF;
            // Force-flush so the armor piece's depth is committed before our outline tests
            // against it — see doItemOutline for full rationale.
            if (buffer instanceof MultiBufferSource.BufferSource preBs) preBs.endBatch();
            // Armor uses front-face-culled render type so the dilated mesh forms a ring
            // rather than a filled silhouette. Scale around THIS piece's own AABB centroid
            // (captured on a first AABB-only pass) instead of a shared player-pose pivot —
            // otherwise a helmet's inflated mesh extends downward into the chest space and
            // the dilated back faces of one piece read through the front faces of neighbors,
            // producing the "plane bleeding through" artifact the user reported.
            // Textured RT: ENTITY_CUTOUT_NO_CULL_SHADER + armor texture → alpha-discard on
            // transparent texels. Fixes the "whole texture space" artifact where the untextured
            // POSITION_COLOR path drew the full bone hull (including transparent holes in chainmail,
            // boots, etc.) as a solid colored ring instead of tracing only opaque pixel coverage.
            // NOT wrapped with asShaderOutline: IsOutlineRenderStateShard routes to a separate
            // outline framebuffer that is composited before clouds, so depth-writing there has no
            // effect on the cloud composite's scene-depth read. Using the plain RT keeps the outline
            // in the main scene depth buffer, which cloud composites DO sample to mask geometry.
            // Bind the alpha-mask parallel of this armor texture (RGB=255, alpha preserved) so
            // the pack's gbuffers_entities does `vertexColor × white = vertexColor` and the ring
            // renders in the chosen outline color rather than picking up the armor's albedo.
            // Same fix as held sprites — see getBlocksAlphaMask / getArmorAlphaMask.
            RenderType outlineRT = forShaderArmorOutlineTextured(getArmorAlphaMask(texture));
            float[] minMax = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                               Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
            poseStack.pushPose();
            model.renderToBuffer(poseStack, new AABBTrackingConsumer(new NullConsumer(), minMax),
                    packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            poseStack.popPose();
            if (!(minMax[3] > minMax[0])) return;
            float cx = (minMax[0] + minMax[3]) * 0.5f;
            float cy = (minMax[1] + minMax[4]) * 0.5f;
            float cz = (minMax[2] + minMax[5]) * 0.5f;
            float outlineScale = slot == EquipmentSlot.FEET ? 1.03f : 1.04f;
            // FullColorOverrideConsumer (not PositionColorOnlyConsumer): forwards UV so the
            // entity cutout shader can sample the texture for alpha-discard.
            VertexConsumer outlineBuf = new FullColorOverrideConsumer(
                    buffer.getBuffer(outlineRT), rByte, gByte, bByte, 255);
            poseStack.pushPose();
            poseStack.last().pose().mulLocal(new Matrix4f()
                    .translate(cx, cy, cz)
                    .scale(outlineScale, outlineScale, outlineScale)
                    .translate(-cx, -cy, -cz));
            model.renderToBuffer(poseStack, outlineBuf, net.minecraft.util.LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, net.minecraft.util.ARGB.colorFromFloat(1.0f, oR, oG, oB));
            if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch(outlineRT);
            poseStack.popPose();
            return;
        }

        // Elytra cape branch — bypass stencil entirely and use the shell-outline forward path
        // (same approach as the shaderpack branch above). Each wing is a 3D box but the cape sits
        // suspended in air with the player body right behind it, and the stencil approach always
        // produces a visible ring on the body-facing fringe regardless of cull direction. The
        // forward shell technique uses CULL_FRONT + polygon-offset push-back: only the dilated
        // shell's back-facing faces draw, pushed behind in screen depth so LEQUAL fails wherever
        // the original cape exists — the ring forms only where the dilated shell extends past
        // the original silhouette and into pure-air pixels.
        // Per-outline stencil-value isolation: each call gets a unique slot V (1..255).
        // WRITE stamps V; TEST tests stencil != V. Two outlined objects no longer share a
        // single stencil bit, so a sword overlapping armor no longer wipes/blocks the
        // armor's outline (and vice versa).
        int stencilSlot = nextStencilSlot();
        // WRITE texture selection. For humanoid armor pieces (slot != null) we stamp only the
        // armor's opaque texels so the ring traces the armored coverage (a SOLID stamp would
        // follow the full bone hull — arms past pauldrons; see the TRIED note above).
        //
        // For the slot == null path (IaF mounts: dragon / hippogryph / hippocampus, whose armor
        // reuses the mount's OWN body model), stamp the FULL geometry silhouette via white.png
        // instead. These body textures have large transparent regions (wing membranes, scale
        // gaps); stamping only opaque texels leaves those gaps unmarked, so the dilated back-side
        // ring passes NOTEQUAL there and the player sees the BACK armor outline through the FRONT
        // of the mob. white.png defeats the outline shader's alpha-discard so every geometry
        // fragment marks the slot, closing the gaps. This replaces the old forBodyDepthFill hack,
        // which closed the gaps in the DEPTH buffer and thereby left invisible world-occluding
        // planes; the WRITE pass uses NO_WRITE (stencil only), so nothing leaks into world depth.
        Identifier writeTex = (slot == null && !OUTLINE_HUG_TEXTURE.get())
                ? Identifier.withDefaultNamespace("textures/misc/white.png")
                : texture;
        // Occlusion comes from the WRITE pass stamping only depth-passing (visible) fragments
        // (dpfail=KEEP). The body is already committed to the depth buffer by the time we run (the
        // buffer source flushes the previous RenderType bucket on each getBuffer, so model bodies are
        // on the GPU), so the WRITE re-renders the SAME geometry and would z-fight its own depth at
        // equal values → torn stamp. forOutlineStencilWrite carries a -1,-10 toward-camera polygon
        // offset, which biases the WRITE silhouette slightly nearer so every visible fragment passes
        // LEQUAL reliably (no z-fight), while a fragment behind a wall/fence (committed depth much
        // nearer) still fails by far more than the bias → not stamped → occluded.
        RenderType writeType = forOutlineStencilWrite(stencilSlot, writeTex);
        RenderType testType  = forOutlineStencilTest(stencilSlot, texture);
        // (26.1: stencil enabled once via ConfigureMainRenderTargetEvent — see CustomGlintClientInit)

        // Pre-flush the outer source before our stencil passes. Every other working stencil
        // outline path in this mod does this (doItemOutline → preBs.endBatch; EK's
        // applyDecorationGlint → bs.endLastBatch). Without it, the armor's own vertices —
        // just queued by HumanoidArmorLayer into a FullyBuffered SegmentedBufferBuilder —
        // are mixed into the same deferred flush as our stencil-write and stencil-test, and
        // the eventual batched draw fills the silhouette instead of forming a ring under
        // shader-mod-no-pack in 3P. Items work without this issue because doItemOutline already
        // pre-flushes; armor was the odd one out.
        flushAll(buffer);
        clearStencilIfPending();

        // Route both stencil passes through the outer buffer source (NOT local BufferBuilders).
        // Why: under the shader mod's FullyBufferedMultiBufferSource, `endBatch(RenderType)` is a
        // no-op, so single-RT flushes don't draw immediately — but consecutive `getBuffer(rt)`
        // calls inside an entity render get an insertion-order EDGE added to
        // GraphTranslucencyRenderOrderManager's digraph (only when `inGroup`, set by the shader
        // mod's per-entity render hook). That edge forces WRITE→TEST order in the eventual no-arg
        // endBatch flush.
        //
        // Local-BufferBuilder + RenderType.end() (previous approach) bypasses the buffer source
        // entirely. Empirically that BREAKS armor outline in 3P under shader mods — even though the
        // two passes draw synchronously in order, injecting drawWithShader calls while the
        // FullyBuffered SegmentedBufferBuilders are mid-recording vertices for this same player
        // entity corrupts state (filled silhouette / Z artifacts / per-frame flicker). Hand
        // items rendered via HeldItemLayer (also inside an entity group) work fine with the
        // buffer.getBuffer path, so mirror that pattern for armor.
        //
        // dpfail=KEEP occlusion note: the WRITE stamps the slot only where the silhouette passes the
        // depth test (visible), so occluded parts (behind walls) leave no stencil → no ring. The
        // -1,-10 toward-camera offset on writeType prevents the body from z-fighting its own committed
        // depth (equal-depth re-render) while still failing by a wide margin behind real occluders.
        model.renderToBuffer(poseStack, buffer.getBuffer(writeType), packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        flushRT(buffer, writeType);

        // Pass 2 (dilated outline). Stencil EQUAL 0 test baked into testType's shards.
        float outlineScale = slot == EquipmentSlot.FEET ? 1.03f : 1.04f;
        poseStack.pushPose();
        if (model instanceof HorseModel) {
            // Horse model bones are positioned far from the pose origin used for humanoid armor;
            // the +0.9/-0.95 pivot for humanoid distorts the horse outline. Scale around the pose
            // origin (entity feet) — outline ring stays concentric with the horse silhouette.
            poseStack.scale(outlineScale, outlineScale, outlineScale);
        } else if (model instanceof ElytraModel) {
            // Each elytra wing is a 3D box. Uniform 1.04× scale dilates in Z (cube thickness) too,
            // which projects the front and back face silhouettes to slightly different screen
            // positions — visible as a ghost outline ring on the body-facing side of the elytra.
            // Fix: dilate ONLY in X/Y (silhouette plane), keep Z untouched so both faces project
            // to the same screen silhouette. Scale around the elytra's own AABB centroid (captured
            // via a NullConsumer pre-pass) so the dilated ring stays concentric with the wings in
            // both folded and spread poses.
            float[] minMax = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                               Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
            poseStack.pushPose();
            model.renderToBuffer(poseStack, new AABBTrackingConsumer(new NullConsumer(), minMax),
                    packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            poseStack.popPose();
            if (minMax[3] > minMax[0]) {
                float cx = (minMax[0] + minMax[3]) * 0.5f;
                float cy = (minMax[1] + minMax[4]) * 0.5f;
                poseStack.last().pose().mulLocal(new Matrix4f()
                        .translate(cx, cy, 0)
                        .scale(outlineScale, outlineScale, 1.0f)
                        .translate(-cx, -cy, 0));
            } else {
                poseStack.scale(outlineScale, outlineScale, 1.0f);
            }
        } else if (slot == null) {
            // Non-humanoid pivot: entity body outlines from EntityGlintRender AND IaF mount armor
            // (dragon, hippogryph, hippocampus) — anything where the model is not a HumanoidModel
            // and the chest-height humanoid pivot below would displace geometry incorrectly.
            // The humanoid pivot (y≈0.9 in entity-local) is correct for a player but sits far from
            // a 5m dragon's centroid; scaling 1.04× around that misplaced pivot shifts back-side
            // geometry several units in Z, enough that the dilated outline mesh for back-side
            // armor passes LEQUAL against the body depth and draws visibly on the front — symptom:
            // looking at one side of the mob shows the outline from the opposite side through it.
            // Scale around the model's own 3D AABB centroid (NullConsumer pre-pass) so dilation
            // stays concentric with whatever silhouette the model actually has. IaF mount armor
            // mixins explicitly pass slot=null to reach this branch.
            float[] minMax = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                               Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
            poseStack.pushPose();
            model.renderToBuffer(poseStack, new AABBTrackingConsumer(new NullConsumer(), minMax),
                    packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            poseStack.popPose();
            if (minMax[3] > minMax[0]) {
                float cx = (minMax[0] + minMax[3]) * 0.5f;
                float cy = (minMax[1] + minMax[4]) * 0.5f;
                float cz = (minMax[2] + minMax[5]) * 0.5f;
                poseStack.last().pose().mulLocal(new Matrix4f()
                        .translate(cx, cy, cz)
                        .scale(outlineScale, outlineScale, outlineScale)
                        .translate(-cx, -cy, -cz));
            } else {
                poseStack.scale(outlineScale, outlineScale, outlineScale);
            }
        } else {
            poseStack.translate(0.0f, -0.95f, 0.0f); // -0.9 - 0.05 downward alignment correction
            poseStack.scale(outlineScale, outlineScale, outlineScale);
            poseStack.translate(0.0f, 0.9f, 0.0f);
        }
        model.renderToBuffer(poseStack, buffer.getBuffer(testType), net.minecraft.util.LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, net.minecraft.util.ARGB.colorFromFloat(1.0f, oR, oG, oB));
        flushRT(buffer, testType);
        poseStack.popPose();
    }

    /**
     * Multi-model entity outline: stamp every entry into ONE shared stencil slot, then dilate-
     * test each entry's model individually. Resolves the "stray clothing covers torso but base
     * skeleton outline still draws under it" duplication: each TEST pass only draws ring pixels
     * where stencil != V, and since every body+overlay silhouette stamped V before any TEST
     * fired, the ring only forms outside the union of all surfaces. The per-entry dilation
     * pivot is still computed per model (AABB centroid) so each surface gets a tight 1.04× ring.
     *
     * Entries are processed in order. The shader-pack path falls back to per-entry forward
     * outline (no stencil available) — that path already accepts visible duplication across
     * overlays because the dilation depth ordering hides most of the conflict.
     */
    public static void doMultiModelOutline(PoseStack poseStack, MultiBufferSource buffer,
            int color, List<EntityGlintRender.PendingOutline> entries) {
        if (entries == null || entries.isEmpty()) return;
        if (isInShadowPass()) return;
        if (outlineSuppressor.getAsBoolean()) return;

        // Shader-pack: stencil path is dead, dispatch each entry through doModelOutline which
        // takes its own forward-pass branch. Each entry uses the running ColorModulator color so
        // they all match. Duplicated rings across overlaps are accepted in this branch; the
        // shader-pack outline is already imprecise.
        if (isShaderPackActive()) {
            for (var e : entries) {
                poseStack.pushPose();
                poseStack.last().pose().set(e.pose);
                poseStack.last().normal().set(e.normal);
                doModelOutline(poseStack, buffer, e.light, e.model, e.texture, color, null);
                poseStack.popPose();
            }
            return;
        }

        float oR = ((color >> 16) & 0xFF) / 255.0f;
        float oG = ((color >>  8) & 0xFF) / 255.0f;
        float oB = ( color        & 0xFF) / 255.0f;
        float outlineScale = 1.04f;

        int stencilSlot = nextStencilSlot();
        // (26.1: stencil enabled once via ConfigureMainRenderTargetEvent — see CustomGlintClientInit)
        // Force-flush queued body / glint / layer drawcalls before the stencil passes so the
        // stencil-write geometry only contributes stencil bits, not commingled colour vertices.
        flushAll(buffer);
        clearStencilIfPending();

        // PHASE 1: WRITE every entry's silhouette into the shared slot. REPLACE on pass/dpfail
        // means later WRITEs on overlapping pixels still leave stencil = V (idempotent).
        for (var e : entries) {
            RenderType writeType = forOutlineStencilWrite(stencilSlot, e.texture);
            poseStack.pushPose();
            poseStack.last().pose().set(e.pose);
            poseStack.last().normal().set(e.normal);
            e.model.renderToBuffer(poseStack, buffer.getBuffer(writeType), e.light,
                    OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            flushRT(buffer, writeType);
            poseStack.popPose();
        }

        // PHASE 2: TEST every entry dilated around its own AABB centroid. stencilFunc NOTEQUAL V
        // on the shared slot — since every body+overlay stamped V in phase 1, the dilated ring
        // is suppressed everywhere any surface exists, leaving the outline only on the union's
        // outer perimeter.
        for (var e : entries) {
            RenderType testType = forOutlineStencilTest(stencilSlot, e.texture);
            poseStack.pushPose();
            poseStack.last().pose().set(e.pose);
            poseStack.last().normal().set(e.normal);

            float[] minMax = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                               Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
            poseStack.pushPose();
            e.model.renderToBuffer(poseStack, new AABBTrackingConsumer(new NullConsumer(), minMax),
                    e.light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            poseStack.popPose();
            if (minMax[3] > minMax[0]) {
                float cx = (minMax[0] + minMax[3]) * 0.5f;
                float cy = (minMax[1] + minMax[4]) * 0.5f;
                float cz = (minMax[2] + minMax[5]) * 0.5f;
                poseStack.last().pose().mulLocal(new Matrix4f()
                        .translate(cx, cy, cz)
                        .scale(outlineScale, outlineScale, outlineScale)
                        .translate(-cx, -cy, -cz));
            } else {
                poseStack.scale(outlineScale, outlineScale, outlineScale);
            }
            e.model.renderToBuffer(poseStack, buffer.getBuffer(testType), net.minecraft.util.LightCoordsUtil.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY, net.minecraft.util.ARGB.colorFromFloat(1.0f, oR, oG, oB));
            flushRT(buffer, testType);
            poseStack.popPose();
        }
    }

    /**
     * Compat hook: BEWLR classes registered here opt out of the generic doItemOutline path
     * and draw their own outline (typically via doBewlrOutline with the item's actual texture
     * so each variant's silhouette matches its opaque texels rather than the combined model AABB).
     */
    public static final Set<Class<?>> CUSTOM_OUTLINE_BEWLRS = ConcurrentHashMap.newKeySet();

    /**
     * Per-item-class outline offset overrides for BEWLR items that take the AABB scale-dilation
     * outline path. Key = item class FQN (string, so compat code can register modded items
     * without a compileOnly dep). Value = {cx1P, cy1P, cz1P, cx3P, cy3P, cz3P} — offsets added
     * to the AABB centroid before the 1.06× scale. Used by doItemOutline when the rendered
     * model's visual center sits off the AABB centroid (e.g. IaF tide trident).
     */
    public static final Map<String, float[]> BEWLR_OUTLINE_OFFSETS = new ConcurrentHashMap<>();

    /**
     * Item class FQNs whose BEWLR swaps to a flat 2D inventory sprite in GROUND/FIXED contexts
     * (vanilla trident does this internally; some modded "trident-style" items do too). Items
     * registered here take the flat-sprite outline path in GROUND/FIXED instead of the BEWLR
     * AABB scale-dilation path. Populated by compat code.
     */
    public static final Set<String> FLAT_ON_GROUND_ITEMS = ConcurrentHashMap.newKeySet();

    /**
     * Per-item override texture for the BEWLR AABB scale-dilation outline pass. Key = item class
     * FQN, value = texture Identifier. Default is white.png (fills every model face
     * opaquely), which produces squared blocks of color on model cubes whose UVs cover
     * transparent texture areas. Registering a texture here makes the outline shader
     * alpha-discard against those texels, so the outline traces only opaque-pixel silhouettes.
     * Keyed by item (not BEWLR class) because the vanilla default BEWLR is shared across many
     * items (trident, shield, banner, skull, …) and needs per-item textures.
     */
    public static final Map<String, Identifier> BEWLR_OUTLINE_TEXTURES = new ConcurrentHashMap<>();

    /**
     * Per-stack override resolver for the BEWLR outline texture. Key = item class FQN, value = a
     * function from the stack to its outline texture (or null to fall through). Takes priority over
     * the static {@link #BEWLR_OUTLINE_TEXTURES} map. Needed when one item class renders many visual
     * variants from a single shared model with per-variant textures (Ice &amp; Fire troll weapons:
     * one {@code TrollWeaponItem} class, one {@code TrollWeaponModel}, a different texture per weapon).
     * The static map can't tell those apart, so without per-stack resolution every variant's outline
     * traces the full shared model geometry and looks identical. Returning the variant's own texture
     * makes the outline shader alpha-discard against its texels, tracing just that weapon's silhouette.
     */
    public static final Map<String, Function<ItemStack, Identifier>> BEWLR_OUTLINE_TEXTURE_RESOLVERS = new ConcurrentHashMap<>();

    /**
     * Stencil-based colored outline for BEWLR (block-entity-without-level-renderer) items whose
     * geometry is a single combined model with per-variant texture (e.g. Ice & Fire troll weapons,
     * death worm gauntlet). Unlike doModelOutline, scales around the pose origin without any
     * humanoid pivot translate, and uses 1.06× to match the BEWLR scale used in doItemOutline.
     * Caller is responsible for pose translates that match the BEWLR's internal pose.
     */
    public static void doBewlrOutline(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, Model model, Identifier texture, Data glint) {
        doBewlrOutline(poseStack, buffer, packedLight, model, texture, glintOutlineColor(glint));
    }

    /** Stack-aware overload: outline color resolved from glowColors / glint layer 0. */
    public static void doBewlrOutline(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, Model model, Identifier texture, ItemStack stack) {
        doBewlrOutline(poseStack, buffer, packedLight, model, texture, glintOutlineColor(stack));
    }

    public static void doBewlrOutline(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, Model model, Identifier texture, int color) {
        if (isInShadowPass()) return;
        float oR = ((color >> 16) & 0xFF) / 255.0f;
        float oG = ((color >>  8) & 0xFF) / 255.0f;
        float oB = ( color        & 0xFF) / 255.0f;

        // Shader-pack forward-pass path: see doModelOutline for rationale.
        //
        // ⚠ DO NOT widen this gate to `|| isShaderModInstalled()` to match doModelOutline. Items go
        // INVISIBLE under shader-mod-installed-no-pack on the forward-pass path: BEWLR/sprite RTs
        // use entity-shader-based render types, which the shader mod's FullyBufferedMultiBufferSource
        // batches and flushes at a phase where the depth target is wrong for the outline geometry.
        // Stencil path under shader-mod-no-pack is broken too (solid fill), but visible.
        // Visible-but-wrong > invisible.
        if (isShaderPackActive()) {
            int rByte = (color >> 16) & 0xFF;
            int gByte = (color >>  8) & 0xFF;
            int bByte =  color        & 0xFF;
            if (buffer instanceof MultiBufferSource.BufferSource preBs) preBs.endBatch();
            IN_OUTLINE.set(true);
            try {
                // Textured outline path — alpha-discards against the variant's own texture so
                // BEWLRs that pack multiple variants into a single shared Model (IaF troll weapon,
                // death worm gauntlet) only outline the currently-active variant. The prior
                // texture-less forShaderOutline() drew the dilated mesh on every cube in the
                // shared model regardless of which variant's texture was bound — under a shader
                // pack you'd see all troll weapon outlines mixed together. forShaderArmorOutlineTextured
                // uses RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER which maps to gbuffers_entities and
                // discards transparent texels; the parallel alpha-mask (RGB=255, alpha preserved)
                // keeps the ring in the chosen outline color rather than tinting it with the texture
                // albedo. Caller must pass FullColorOverrideConsumer so UV reaches the buffer.
                //
                // Scale around the model's own AABB centroid (NullConsumer pre-pass), not the pose
                // origin — BEWLR models are typically offset from origin and a uniform pose-origin
                // scale shifts the dilated shell off-center.
                RenderType outlineRT = (texture != null)
                        ? forShaderArmorOutlineTextured(getArmorAlphaMask(texture))
                        : forShaderOutline();
                if (texture != null) {
                    float[] minMax = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                                       Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
                    poseStack.pushPose();
                    model.renderToBuffer(poseStack, new AABBTrackingConsumer(new NullConsumer(), minMax),
                            packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
                    poseStack.popPose();
                    if (!(minMax[3] > minMax[0])) return;
                    float cx = (minMax[0] + minMax[3]) * 0.5f;
                    float cy = (minMax[1] + minMax[4]) * 0.5f;
                    float cz = (minMax[2] + minMax[5]) * 0.5f;
                    VertexConsumer outlineBuf = new FullColorOverrideConsumer(
                            buffer.getBuffer(outlineRT), rByte, gByte, bByte, 255);
                    poseStack.pushPose();
                    poseStack.last().pose().mulLocal(new Matrix4f()
                            .translate(cx, cy, cz)
                            .scale(1.06f, 1.06f, 1.06f)
                            .translate(-cx, -cy, -cz));
                    model.renderToBuffer(poseStack, outlineBuf, net.minecraft.util.LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, net.minecraft.util.ARGB.colorFromFloat(1.0f, oR, oG, oB));
                    if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch(outlineRT);
                    poseStack.popPose();
                } else {
                    // Texture-less fallback (kept for callers that pass null) — original behavior.
                    VertexConsumer outlineBuf = new PositionColorOnlyConsumer(
                            buffer.getBuffer(outlineRT), rByte, gByte, bByte, 255);
                    poseStack.pushPose();
                    poseStack.scale(1.06f, 1.06f, 1.06f);
                    model.renderToBuffer(poseStack, outlineBuf, net.minecraft.util.LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, net.minecraft.util.ARGB.colorFromFloat(1.0f, oR, oG, oB));
                    if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch(outlineRT);
                    poseStack.popPose();
                }
            } finally {
                IN_OUTLINE.set(false);
            }
            return;
        }

        // Item-variant write: no polygon offset. BEWLR items' base draws don't use polygon offset,
        // and the armor-matching offset can clip the silhouette in front of the near plane in 3.5D FPM.
        int slot = nextStencilSlot();
        RenderType writeType = forOutlineStencilWriteItem(slot, texture);
        RenderType testType  = forOutlineStencilTest(slot, texture);
        flushAll(buffer);
        // (26.1: stencil enabled once via ConfigureMainRenderTargetEvent — see CustomGlintClientInit)
        IN_OUTLINE.set(true);
        try {
            // Pass 1 (stencil silhouette) — masks + stencil setup baked into writeType shards (shader-mod-safe).
            model.renderToBuffer(poseStack, buffer.getBuffer(writeType), packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            flushRT(buffer, writeType);

            // Pass 2 (dilated outline) — EQUAL,0 stencil test baked into testType's pipeline.
            // 26.1: outline color is carried by the per-vertex color (the renderToBuffer int arg);
            // the old RenderSystem.setShaderColor global is gone.
            poseStack.pushPose();
            poseStack.scale(1.06f, 1.06f, 1.06f);
            model.renderToBuffer(poseStack, buffer.getBuffer(testType), net.minecraft.util.LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, net.minecraft.util.ARGB.colorFromFloat(1.0f, oR, oG, oB));
            flushRT(buffer, testType);
            poseStack.popPose();
            // Trailing full drain — see doItemOutline for the rationale (atomic per-item stencil
            // pipeline under shader-mod FullyBuffered, otherwise the last item of the frame stays
            // queued and its stencil pass sees disrupted state at end-of-frame drain).
            flushAll(buffer);
        } finally {
            IN_OUTLINE.set(false);
        }
    }

    /**
     * Stencil-based colored outline for item/weapon/tool renders.
     * Skips GUI context. Uses an isolated BufferSource for the stencil pass to avoid
     * flushing other items in the main batch while colorMask is disabled. Uses IN_OUTLINE
     * to prevent recursive glint application during the stencil/outline passes.
     *
     * ── OPEN BUG: FPM 3.5D + shader mod, two held items → 2nd item's outline FILLS ──
     * Reproduces only in FirstPersonMod 3.5D mode with a shader mod installed (no shader pack needed).
     * The FIRST-rendered held item outlines correctly; the SECOND fills entirely with the
     * outline color. 3P, vanilla 1P, ground items, and 1 held item all work flawlessly. Repros
     * with two swords (no BEWLR needed), so it's not a BEWLR-path interaction. Both items are
     * rendered inside FPM's player-as-entity render at HEAD of LevelRenderer.render (begins != 0
     * → the shader mod's FullyBufferedMultiBufferSource is in effect).
     *
     * Attempts that did NOT fix it (all reverted unless noted):
     *   1. Trailing `preBs.endBatch()` at the END of doItemOutline / doBewlrOutline to force
     *      each item's writeType+testType pair to drain atomically (so two items never share
     *      a FullyBuffered drain). Built fine. Did not fix the fill. Currently KEPT — benign
     *      and theoretically reduces drain-order risk.
     *   2. Setting `pendingFrameStencilClear = true` per-item at the top of doItemOutline /
     *      doBewlrOutline (re-arm the once-per-frame stencil clear so each item gets a fresh
     *      stencil baseline). REGRESSION: broke 3rd-person outlines. Reverted.
     *   3. Same per-item re-arm as #2 but gated on `FirstPersonAPI.isRenderingPlayer()` so it
     *      only fires inside the FPM 3.5D player render (leaves 3P/1P/ground untouched).
     *      Reverted at user request. Outcome on the 2-item fill: unconfirmed.
     *   4. Per-item re-arm of `pendingFrameStencilClear` in doItemOutline + doBewlrOutline +
     *      doModelOutline, gated on FPM-3.5D-active-in-first-person via a new
     *      `CustomGlintRenderer.perItemStencilClear` BooleanSupplier installed by
     *      FirstPersonCompat (FirstPersonAPI.isEnabled() && camera.isFirstPerson()).
     *      REGRESSION: under shader-mod-no-pack + FPM 3.5D, the 1-item case AND armor both started
     *      filling (worse than pre-fix). Reason: the WRITE shard's `setup()` only runs at drain
     *      time under FullyBuffered, not at recording time, so recording-side re-arm doesn't
     *      produce per-item glClear(STENCIL) — it just keeps the gate true long enough that the
     *      single glClear during drain fires at a moment that wipes a silhouette mid-batch
     *      between writeType segments, leaving stencil=0 inside silhouettes → EQUAL 0 passes →
     *      fills. The gate mechanism is timing-fragile and not the right lever here. Reverted.
     *   5. FPM-3.5D forward-pass fallback: added `heldItemForwardPassGate` BooleanSupplier OR'd
     *      into the `isShaderPackActive()` branches of doItemOutline / doBewlrOutline /
     *      doModelOutline, installed by FirstPersonCompat to `FirstPersonAPI.isRenderingPlayer()`
     *      so all three outline entry points took the existing POSITION_COLOR / 4-translate path
     *      whenever FPM was rendering the player. Intent: trade shape-perfect stencil for stability
     *      inside FPM's player-as-entity render. REGRESSION (significantly worse): in FPM 3.5D with
     *      shader-mod-installed-no-pack, items had NO outline at all, and armor's outline traced the
     *      full bone geometry (the "hole texture space" — bone rectangles where armor coverage is
     *      transparent) instead of the armor silhouette. Root cause: the shaderpack-active branch's
     *      RTs (`forShaderArmorOutline` / `forShaderOutline` / `forShaderSpriteOutline`) and the
     *      eye-space-Z trick rely on a real shaderpack composite to land their gbuffers_* output —
     *      with the shader mod loaded but no pack, those RTs route through entity-shader programs whose
     *      depth/color targets don't match the main FBO at draw time, so items go invisible. And
     *      the armor branch uses POSITION_COLOR with no texture sampling, so alpha-discard can't
     *      reject transparent armor texels → outline becomes the full HumanoidModel bone hull. The
     *      same warnings already in doBewlrOutline:1083 and doModelOutline:923 ("DO NOT widen to
     *      `|| isShaderModInstalled()`") apply equally to FPM-active-no-pack: shader-mod + no-pack
     *      is the poison context regardless of whether FPM is the trigger. Reverted entirely.
     *
     * Things to try next (notes from analysis, none implemented yet):
     *   - Log inside STENCIL_WRITE_LAYERING_ITEM.setup() / .clear() to confirm how many times
     *     each runs per frame in the 2-sword FPM 3.5D case, and whether glClear(STENCIL) fires
     *     at all for the second item. Speculation has run its course — runtime evidence first.
     *   - Inspect the shader mod's `vertices/MixinBufferBuilder` and `MixinGameRenderer` (not yet
     *     decompiled — context2 line 159/161). Either may explain why the 2nd item's stencil
     *     stamps don't land where expected under FullyBuffered drain.
     *   - Consider an FPM-3.5D-specific code path that bypasses stencil entirely for held items
     *     (e.g. POSITION_COLOR forward-pass outline like the shader-pack branch, but gated on
     *     `isRenderingPlayer()`). Last-resort: gives up shape-perfect outline for stability.
     *
     * Cross-references: context2.md (prior session handoff), `FirstPersonCompat.shouldSuppress`
     * (FPM detection wired but commented out), `pendingFrameStencilClear` javadoc (line ~395).
     */
    /**
     * GUI icon glow outline (hotbar / inventory / chest / JEI / any 2D context). Fires from
     * {@code ItemRendererMixin}'s HEAD inject BEFORE the actual item renders so the 4 translated
     * copies land in the same RT buckets in submission order; the real item is appended last and
     * naturally overdraws the overlap, leaving only the +/- 1 GUI-pixel halo visible.
     *
     * Mechanism: wrap the bufferSource so every {@code getBuffer(rt)} returns a
     * {@link FullColorOverrideConsumer} clamped to the glow color. Recursively call
     * {@link net.minecraft.client.renderer.entity.ItemRenderer#render} with the wrapped source 4
     * times at offsets (-1, 0), (1, 0), (0, -1), (0, 1) GUI pixels. The pose stack at this point
     * has been scaled by 16, so 1 GUI pixel == 1/16 model-space unit.
     *
     * {@link #IN_OUTLINE} is set so {@code ItemRendererMixin.applyGlint} short-circuits to the
     * bare base buffer (no glint overlay during outline copies).
     */
    /**
     * Reverted to the first-iteration working state: 4 translated render() copies through a
     * FullColorOverrideConsumer that forwards to whatever RT vanilla's ItemRenderer chose for
     * each draw (no RT redirect, no alpha-mask atlas, no scissor). Outline IS visible in GUI
     * but its color BLEEDS the sprite's edge pixels — shader emits `texColor × vertexColor`,
     * so a green sword sprite tints the red glow into a muddy green. Fixing the color is still
     * an open problem; see "Attempted color fixes (all broke the draw entirely)" below.
     *
     * Attempted color fixes (all broke the draw entirely — outline disappeared):
     *   1. Redirect every getBuffer(rt) to a single `RenderType.entityCutoutNoCull(getBlocksAlphaMask())`.
     *      Idea: the alpha-mask atlas has RGB clamped to 255, so shader output =
     *      `white × vertexColor = glowColor`. Same trick used successfully by world-outline
     *      shader-pack path. RESULT: no outline rendered at all in GUI. Possibly the
     *      alpha-mask texture isn't fully built / bound in GUI context, or the RT's
     *      LIGHTMAP/OVERLAY state interacts badly with GUI's render state.
     *   2. Redirect every getBuffer(rt) to a single `RenderType.entityCutoutNoCull(LOCATION_BLOCKS)`
     *      (the real items atlas, not the mask). Same redirect mechanism, just different texture.
     *      Idea: at least prove the redirect path works. RESULT: also no outline at all.
     *      Suggests the issue isn't the texture but the RT redirect itself — possibly because
     *      a single shared outlineRT can't satisfy whatever format/state ItemRenderer's chosen
     *      per-quad RT needed (e.g. translucentCullBlockSheet uses BLOCK format, not NEW_ENTITY).
     *   3. Added slot scissor (glScissor on a 16x16 slot-bounds box). Initially read pose stack's
     *      m30/m31 (wrong — that PoseStack is identity in GUI; the GUI transform lives on
     *      RenderSystem.getModelViewStack). Even after switching to modelView stack, scissor
     *      was disabled and outlines still didn't render — so scissor wasn't the cause.
     *   4. Added BEWLR skip via `IClientItemExtensions.of(stack).getCustomRenderer() != null`.
     *      WRONG — Forge returns the default vanilla BlockEntityWithoutLevelRenderer for EVERY
     *      item, so this filtered out every 2D item including swords/apples. Use
     *      `model.isCustomRenderer()` instead if you need to gate on real BEWLR rendering.
     *      But even with that fix, the redirect approach (attempts 1+2) still broke the draw.
     *
     * Things to try next:
     *   - Keep the per-rt routing (don't redirect), but intercept `color()` on the
     *     FullColorOverrideConsumer differently. E.g. force a white texture via a TextureStateShard
     *     override at the consumer level — but BufferBuilder doesn't bind textures, the RT does.
     *   - Bind BLOCKS_ALPHA_MASK_LOC as Sampler0 via `RenderSystem.setShaderTexture(0, …)` for
     *     the duration of the outline copies, leaving the RT itself alone. The RT's
     *     TextureStateShard.setupRenderState happens at flush time and will REBIND Sampler0 to
     *     the RT's declared texture, undoing this — would need to either flush per copy or use
     *     a custom RT whose TextureStateShard.setupRenderState() does the alpha-mask bind.
     *   - Make a custom RT identical to translucentCullBlockSheet but with a setup-time
     *     `setShaderTexture(0, BLOCKS_ALPHA_MASK_LOC)` override. Keeps BLOCK format, keeps
     *     LIGHTMAP/OVERLAY state, only swaps the Sampler0 binding.
     *   - Skip the texture entirely: render dilated POSITION_COLOR quads built from the
     *     BakedModel's sprite UV bounds. Loses sprite-shape silhouette but guarantees pure
     *     glow color. Would need to manually iterate quads / handleCameraTransforms etc.
     */
    // ──────────────────────────────────────────────────────────────────────────────────────────
    // ITEM-MODEL OUTLINE — STUBBED FOR THE 26.1 PORT (green-compile floor; needs in-game redesign).
    //
    // doGuiItemOutline / textureOf / doItemOutline drove the held / GUI / ground item glow outline by
    // recursively re-rendering the item through the now-deleted BEWLR + BakedModel pipeline
    // (ItemRenderer.render(..., BakedModel), IClientItemExtensions.getCustomRenderer(),
    // ItemStack.isCustomRenderer()/isGui3d(), Minecraft.getItemRenderer(), and the
    // CompositeState/TextureStateShard accessors that backed textureOf). The 1.21.5 item-model rework
    // replaced all of that with ItemModel / ItemStackRenderState. Re-tracing the item silhouette has
    // to move onto that system — a focused pass that needs in-game iteration (see
    // .claude/context/26/06-status.md bucket 3, plus 02/09). The full original implementation is
    // preserved in git history (working-1.21.1 branch). The detailed design notes above are kept as
    // reference for that redesign.
    // ──────────────────────────────────────────────────────────────────────────────────────────

    /** Stubbed for the 26.1 port — GUI item glow outline must be rebuilt on ItemModel. See above. */
    public static void doGuiItemOutline(ItemStack stack, ItemDisplayContext displayContext,
            boolean leftHand, PoseStack poseStack, MultiBufferSource buffer, int packedLight,
            int packedOverlay, Object model) {
        // TODO(26.1): redesign the GUI item glow outline onto ItemModel / ItemStackRenderState.
    }

    /** Stubbed for the 26.1 port — the CompositeState/TextureStateShard accessors it read are gone. */
    private static Identifier textureOf(RenderType rt) {
        // TODO(26.1): recover an item RenderType's bound texture from the new RenderSetup texture map.
        return null;
    }

    /** Stubbed for the 26.1 port — held/ground item glow outline must be rebuilt on ItemModel. */
    public static void doItemOutline(ItemStack stack, ItemDisplayContext displayContext,
            PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        // TODO(26.1): redesign the held/ground item glow outline onto ItemModel / ItemStackRenderState.
    }

    /** Wraps a VertexConsumer and overrides vertex colors with a fixed RGBA value. */
    private static final class ColorOverrideConsumer extends WrappingConsumer {
        private final VertexConsumer wrapped;
        private final int r, g, b, a;
        ColorOverrideConsumer(VertexConsumer wrapped, int r, int g, int b, int a) {
            this.wrapped = wrapped; this.r = r; this.g = g; this.b = b; this.a = a;
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) { wrapped.addVertex(x, y, z); return this; }
        @Override public VertexConsumer addVertex(Matrix4fc matrix, float x, float y, float z) { wrapped.addVertex(matrix, x, y, z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return wrapped.setColor(this.r, this.g, this.b, this.a); }
        @Override public VertexConsumer setColor(float r, float g, float b, float a) { return wrapped.setColor(this.r, this.g, this.b, this.a); }
        @Override public VertexConsumer setUv(float u, float v) { return wrapped.setUv(u, v); }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }

    /** Same as ColorOverrideConsumer but forwards overlay/uv2/normal to the wrapped buffer.
     *  Needed when the underlying buffer uses the NEW_ENTITY vertex format
     *  (POSITION_COLOR_TEX_OVERLAY_LIGHTMAP_NORMAL) — dropping those fields leaves the
     *  BufferBuilder with unfilled elements and crashes on endVertex. The original
     *  ColorOverrideConsumer drops them on purpose because its only wrapped target is
     *  the stencil-test RenderType which uses POSITION_COLOR_TEX. */
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

    /**
     * True iff a shader mod is installed (detection API class resolved), regardless of whether
     * a shaderpack is currently active. The shader mod replaces the buffer-source pipeline
     * whenever it's loaded, which breaks our stencil-based outline path even with no pack —
     * so the forward-pass outline branch needs to trigger on presence, not just active-pack.
     */
    public static boolean isShaderModInstalled() {
        if (!SHADER_LOOKUP_DONE) isShaderPackActive();
        return SHADER_IS_IN_USE != null;
    }

    // Under shader mods, every RenderType is mixed in to implement BlendingStateHolder with a
    // TransparencyType field (default GENERAL_TRANSPARENT). The batched FullyBufferedMultiBuffer-
    // Source flushes by TransparencyType in enum order (OPAQUE → OPAQUE_DECAL → GENERAL_TRANSPARENT
    // → DECAL → WATER_MASK → LINES). Items use GENERAL_TRANSPARENT; if our outline RT also sits
    // there, the order between item and outline within the same bucket is undefined → outline can
    // flush before item → depth buffer empty when outline draws → polygon-offset/front-face-cull
    // can't reject the interior → outline reads as a filled silhouette. Tagging the outline RT as
    // LINES (last bucket) forces the shader mod to flush ALL item geometry first, then our outline — depth
    // ordering works in every camera context (1P / 3P / GROUND). Reflective to avoid compileOnly.
    private static volatile boolean SHADER_TT_LOOKUP_DONE = false;
    private static volatile Method SHADER_TT_SET = null;
    private static volatile Object SHADER_TT_LINES = null;
    private static final Set<RenderType> SHADER_TT_TAGGED =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static void tagAsLateRenderForShaders(RenderType rt) {
        if (rt == null) return;
        if (SHADER_TT_TAGGED.contains(rt)) return;
        if (!SHADER_TT_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!SHADER_TT_LOOKUP_DONE) {
                    try {
                        Class<?> ttCls = Class.forName("net.irisshaders.batchedentityrendering.impl.TransparencyType");
                        Class<?> bshCls = Class.forName("net.irisshaders.batchedentityrendering.impl.BlendingStateHolder");
                        SHADER_TT_SET = bshCls.getMethod("setTransparencyType", ttCls);
                        @SuppressWarnings({"rawtypes", "unchecked"})
                        Object lines = Enum.valueOf((Class<? extends Enum>) ttCls, "LINES");
                        SHADER_TT_LINES = lines;
                    } catch (Throwable ignored) {
                        SHADER_TT_SET = null;
                        SHADER_TT_LINES = null;
                    }
                    SHADER_TT_LOOKUP_DONE = true;
                }
            }
        }
        if (SHADER_TT_SET != null && SHADER_TT_LINES != null) {
            try { SHADER_TT_SET.invoke(rt, SHADER_TT_LINES); } catch (Throwable ignored) {}
        }
        SHADER_TT_TAGGED.add(rt);
    }

    // The shader mod's official extension point for tagging a RenderType as "outline geometry."
    // Wrapping a RT with IsOutlineRenderStateShard.INSTANCE via OuterWrappedRenderType causes
    // GbufferPrograms.beginOutline()/endOutline() to fire around the draw call, which is what
    // the shader mod itself uses to mark the vanilla block-selection outline.
    //
    // ⚠ NOT USED for our forward-pass outline RTs (forShaderArmorOutlineTextured, forShaderOutline,
    // forShaderArmorOutline, forShaderSpriteOutline). IsOutlineRenderStateShard routes draws to a
    // SEPARATE outline framebuffer that is composited at a different point than the main scene.
    // Cloud composites (and other post-process effects) read the MAIN SCENE depth texture to mask
    // geometry — they do not see depth written into the outline framebuffer — so the outline
    // disappears behind clouds even with depth-write enabled. By NOT wrapping, the outline geometry
    // goes directly into the main scene depth buffer. Pre-flush + tagAsLateRenderForShaders (LINES
    // bucket) ensure entity depth is committed before LEQUAL runs, preserving the ring silhouette.
    // Reflective lookup retained for potential future use; no compileOnly dep.
    private static volatile boolean SHADER_OUTLINE_LOOKUP_DONE = false;
    private static volatile Method SHADER_WRAP_OUTLINE_RT = null;
    // 26.1: the reflected Iris wrap target was a RenderStateShard (now deleted in vanilla, and the
    // Iris 26.1 signature is unverified against the shader-mod jar). Hold it as Object and resolve
    // wrapExactlyOnce by name/arity so this compiles and no-ops cleanly until re-verified in-game.
    private static volatile Object SHADER_OUTLINE_SHARD = null;
    private static final Map<RenderType, RenderType> SHADER_OUTLINE_WRAP_CACHE = new ConcurrentHashMap<>();

    public static RenderType asShaderOutline(String name, RenderType rt) {
        if (!SHADER_OUTLINE_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!SHADER_OUTLINE_LOOKUP_DONE) {
                    try {
                        Class<?> wrapCls = Class.forName("net.irisshaders.iris.layer.OuterWrappedRenderType");
                        Class<?> shardCls = Class.forName("net.irisshaders.iris.layer.IsOutlineRenderStateShard");
                        for (Method m : wrapCls.getMethods()) {
                            if (m.getName().equals("wrapExactlyOnce") && m.getParameterCount() == 3) {
                                SHADER_WRAP_OUTLINE_RT = m;
                                break;
                            }
                        }
                        SHADER_OUTLINE_SHARD = shardCls.getField("INSTANCE").get(null);
                    } catch (Throwable ignored) {
                        SHADER_WRAP_OUTLINE_RT = null;
                        SHADER_OUTLINE_SHARD = null;
                    }
                    SHADER_OUTLINE_LOOKUP_DONE = true;
                }
            }
        }
        if (SHADER_WRAP_OUTLINE_RT == null || SHADER_OUTLINE_SHARD == null) return rt;
        // Without an active shaderpack, the wrap actively harms entity-shader-based RTs: the
        // shader mod auto-injects EntityRenderStateShard onto RENDERTYPE_ENTITY_*_SHADER types,
        // so the resulting composite fires beginEntities() then beginOutline() — the second call
        // hits GbufferPrograms.checkReentrancy() with entities=true and throws, aborting the draw.
        // The shader mod's outline routing only matters during pack composite anyway, so skip it here.
        if (!isShaderPackActive()) return rt;
        return SHADER_OUTLINE_WRAP_CACHE.computeIfAbsent(rt, k -> {
            try {
                return (RenderType) SHADER_WRAP_OUTLINE_RT.invoke(null, name, k, SHADER_OUTLINE_SHARD);
            } catch (Throwable t) {
                return k;
            }
        });
    }

    // The shader mod's shadow pass re-invokes the full entity/item render pipeline to populate the
    // shadowmap. If we run our outline path during shadows, two things go wrong:
    //   1) The shadow pass writes into a separate framebuffer with its own format constraints,
    //      and getBuffer() returns a buffer with a different vertex format than we expect →
    //      "Not filled all elements of the vertex" crash on endVertex.
    //   2) Even if it didn't crash, outline geometry has no business depth-writing into the
    //      shadowmap — it would just produce wrong shadows for the outline shell.
    // Skip the whole outline path when shadows are being rendered. Reflective lookup so we
    // don't need a compileOnly dep.
    private static volatile boolean SHADOW_LOOKUP_DONE = false;
    private static volatile Method SHADOW_IS_RENDERING = null;

    public static boolean isInShadowPass() {
        if (!SHADOW_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!SHADOW_LOOKUP_DONE) {
                    try {
                        Class<?> cls = Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState");
                        SHADOW_IS_RENDERING = cls.getMethod("areShadowsCurrentlyBeingRendered");
                    } catch (Throwable ignored) {
                        SHADOW_IS_RENDERING = null;
                    }
                    SHADOW_LOOKUP_DONE = true;
                }
            }
        }
        if (SHADOW_IS_RENDERING == null) return false;
        try {
            return (Boolean) SHADOW_IS_RENDERING.invoke(null);
        } catch (Throwable t) {
            return false;
        }
    }
}
