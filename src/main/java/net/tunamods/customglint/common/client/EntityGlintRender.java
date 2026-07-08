package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.CustomGlint.GlintState;
import net.tunamods.customglint.common.CustomGlintComponents;
import net.tunamods.customglint.common.mixin.LivingEntityRendererMixin;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-only entity glint draw. Wraps the renderer's {@link MultiBufferSource} so every
 * {@code entity_*} RenderType requested during an entity render (base model + every RenderLayer)
 * gets a glint overlay fan-out.
 *
 * Resolution order: per-instance via the {@link InstanceResolver} (default reads the synced
 * {@link CustomGlintComponents#ENTITY_GLINT} attachment), then {@link CustomGlint#ENTITY_GLINTS} type
 * registry. Both live in the api jar, so api-only embedders get instance + type glints with no wiring.
 */
public final class EntityGlintRender {
    private EntityGlintRender() {}

    public interface InstanceResolver {
        @Nullable Resolution resolve(LivingEntity entity);
    }

    public static final class Resolution {
        @Nullable public final CustomGlint.Data data;
        public final boolean glowing;
        public final int[] glowColors;
        public Resolution(@Nullable CustomGlint.Data data, boolean glowing, int[] glowColors) {
            this.data = data;
            this.glowing = glowing;
            this.glowColors = glowColors;
        }
    }

    /** Default: read the per-instance glint state from the synced {@link CustomGlintComponents#ENTITY_GLINT}
     *  attachment. The server writes it and NeoForge syncs it to this client, so a client-side mutation is
     *  visible immediately with no cache or sync packet. Embedders may replace this to source glint elsewhere. */
    public static InstanceResolver instanceResolver = entity -> {
        GlintState s = entity.getExistingDataOrNull(CustomGlintComponents.ENTITY_GLINT);
        if (s == null || s.isEmpty()) return null;
        return new Resolution(s.data(), s.glowing(), s.glowColors());
    };

    /**
     * Wraps the renderer's MultiBufferSource so EVERY entity-* RenderType requested during
     * the entity render (base model + every RenderLayer like StrayClothingLayer, EyesLayer,
     * VillagerProfessionLayer, …) gets a glint overlay fan-out. The wrapper is a no-op if the
     * entity has no glint data, so we just return the original buffer in that case.
     *
     * Called from {@link LivingEntityRendererMixin} at HEAD.
     */
    public static MultiBufferSource wrapForEntity(LivingEntity entity, MultiBufferSource original) {
        // Idempotent: never wrap an already-wrapped source (would double-wrap and break
        // body-builder vertex routing).
        if (original instanceof GlintWrappingBufferSource) return original;
        CustomGlint.Data data = resolveData(entity);
        if (data == null) return original;
        return new GlintWrappingBufferSource(original, data);
    }

    // ── Glow outline capture ────────────────────────────────────────────────────────
    // Each glowing model (entity body, an entity-surface layer, or a worn-armor piece) is re-rendered into
    // a record-only buffer (capturing camera-relative [x,y,z,u,v]) and queued, tracing the real shape
    // against its own texture. The re-render is in-phase (the model is still posed from the live draw), so
    // there is no stale-pose problem and no second setupAnim. All silhouettes of ONE figure share that
    // figure's id (glowKeyFor) — body + surface layers (CAT_ENTITY) + every worn-armor piece (CAT_ARMOR) —
    // so they compose as ONE ring, distinct from other figures.

    /** Re-render a posed {@code model} into the glow mask under {@code identity}'s shared id. Silhouettes
     *  sharing an {@code identity} compose as ONE ring (no internal seam); a distinct identity gets its own
     *  ring. Pass the {@code entity} for the figure-wide merge (body + armor), or a per-piece object (e.g.
     *  its ItemStack) to keep that piece separate. {@code entity} is still used for the invisibility skip.
     *  The caller has already decided this should glow and resolved the {@code color}; {@code category}
     *  picks the ring thickness (CAT_ENTITY for body/surface, CAT_ARMOR for worn armor). */
    public static void captureModelSilhouette(LivingEntity entity, Object identity, Model model,
                                              ResourceLocation texture, PoseStack pose, int light,
                                              int color, int category, int priority) {
        if (model == null || texture == null || entity.isInvisible()) return;
        // Reuse one consumer (and its grown backing array) instead of allocating per glowing model per
        // frame — body + every armor piece + elytra + surface layer all hit this each frame. queueModelOutline
        // copies the data into a right-sized array, so the buffer is free to reuse on the next capture. The
        // capture runs on the render thread and never re-enters (renderToBuffer only records vertices).
        CapturingModelConsumer cap = CAPTURE_POOL.get();
        cap.reset();
        model.renderToBuffer(pose, cap, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        GlowOutlineRenderer.queueModelOutline(cap.data, cap.count, texture, color,
                GlowOutlineRenderer.glowKeyFor(identity, category), category, priority);
    }

    /** As {@link #captureModelSilhouette} but from raw {@code ModelPart[]}. Epic Knights armor decorations
     *  (plumes, surcoats, crowns) draw via {@code ModelPart.render} + {@code getArmorFoilBuffer}, not a
     *  {@link Model} / {@code renderColoredCutoutModel}, so no generic tee reaches them. Re-renders the parts
     *  into the pooled recording consumer and traces the silhouette against {@code texture}. Call once per
     *  texture (base + overlay) with the SAME {@code identity} to union a split-shape decoration into one ring. */
    public static void captureModelPartsSilhouette(LivingEntity entity, Object identity, ModelPart[] parts,
                                                   ResourceLocation texture, PoseStack pose, int light,
                                                   int color, int category, int priority) {
        if (parts == null || texture == null || entity.isInvisible()) return;
        CapturingModelConsumer cap = CAPTURE_POOL.get();
        cap.reset();
        for (ModelPart part : parts) {
            if (part != null) part.render(pose, cap, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        }
        GlowOutlineRenderer.queueModelOutline(cap.data, cap.count, texture, color,
                GlowOutlineRenderer.glowKeyFor(identity, category), category, priority);
    }

    /** Draw the entity body AND, when it glows, capture its silhouette in the SAME model walk — the in-phase
     *  tee. Replaces the old "re-render the body model into a record-only buffer after the fact" path (a full
     *  second {@code renderToBuffer} walk per glowing entity every frame), which was the dominant cost with
     *  many glowing entities on screen (matches 26.1.2's measured bottleneck; 26.1.2 fixed it the same way).
     *  Called from {@code LivingEntityRendererMixin}'s {@code @Redirect} on the body {@code renderToBuffer}.
     *
     *  <p>The {@code consumer} is the renderer's real (glint-fanned if the entity also has glint) body buffer.
     *  When glowing we route the walk through a {@link CapturingModelConsumer} whose {@code delegate} is that
     *  real buffer, so the body renders identically while we record its camera-relative {@code [x,y,z,u,v]}
     *  for the drain. Non-glowing entities just draw straight through (one extra method call). Teeing an
     *  entity model is safe under Sodium: the body uses the per-vertex {@code ModelPart} path, NOT the
     *  {@code putBulkData} intrinsic that made the ITEM tee a net loss. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void renderBodyTee(LivingEntityRenderer renderer,
                                     LivingEntity entity, Model model, PoseStack pose, VertexConsumer consumer,
                                     int light, int overlay, int color) {
        Resolution r = entity.isInvisible() ? null : instanceResolver.resolve(entity);
        boolean glow = r != null && (r.glowing || r.glowColors.length > 0);
        if (!glow) { model.renderToBuffer(pose, consumer, light, overlay, color); return; }
        ResourceLocation tex = renderer.getTextureLocation(entity);
        if (tex == null) { model.renderToBuffer(pose, consumer, light, overlay, color); return; }
        CapturingModelConsumer cap = CAPTURE_POOL.get();
        cap.reset();
        cap.delegate = consumer;
        try {
            model.renderToBuffer(pose, cap, light, overlay, color);
        } finally {
            cap.delegate = null;
        }
        GlowOutlineRenderer.queueModelOutline(cap.data, cap.count, tex, outlineColorFor(r),
                GlowOutlineRenderer.glowKeyFor(entity, GlowOutlineRenderer.CAT_ENTITY),
                GlowOutlineRenderer.CAT_ENTITY, 0);
    }

    /** Resolved silhouette-capture parameters for a teed model draw. */
    public static final class OutlineSpec {
        public final Object identity;
        public final ResourceLocation tex;
        public final int color, category, priority;
        public OutlineSpec(Object identity, ResourceLocation tex, int color, int category, int priority) {
            this.identity = identity; this.tex = tex; this.color = color;
            this.category = category; this.priority = priority;
        }
    }

    /** In-phase tee for a 5-arg {@code Model.renderToBuffer(pose, vc, light, overlay, color)} draw (worn
     *  humanoid armor base layer, horse armor, entity-surface layers). Forwards the real draw unchanged;
     *  when {@code spec} is non-null it ALSO records the silhouette in the SAME walk and queues it (no second
     *  {@code renderToBuffer}). {@code spec == null} (entity not glowing) → plain forward. Safe under Sodium
     *  (these are ModelPart per-vertex draws, not the {@code putBulkData} item intrinsic). */
    public static void teeOutline5(Model model, PoseStack pose, VertexConsumer realVc,
                                   int light, int overlay, int color, @Nullable OutlineSpec spec) {
        if (spec == null) { model.renderToBuffer(pose, realVc, light, overlay, color); return; }
        CapturingModelConsumer cap = CAPTURE_POOL.get();
        cap.reset();
        cap.delegate = realVc;
        try {
            model.renderToBuffer(pose, cap, light, overlay, color);
        } finally {
            cap.delegate = null;
        }
        GlowOutlineRenderer.queueModelOutline(cap.data, cap.count, spec.tex, spec.color,
                GlowOutlineRenderer.glowKeyFor(spec.identity, spec.category), spec.category, spec.priority);
    }

    /** As {@link #teeOutline5} for a 4-arg {@code Model.renderToBuffer(pose, vc, light, overlay)} draw
     *  (the elytra; vanilla's overload defaults colour to white). */
    public static void teeOutline4(Model model, PoseStack pose, VertexConsumer realVc,
                                   int light, int overlay, @Nullable OutlineSpec spec) {
        if (spec == null) { model.renderToBuffer(pose, realVc, light, overlay); return; }
        CapturingModelConsumer cap = CAPTURE_POOL.get();
        cap.reset();
        cap.delegate = realVc;
        try {
            model.renderToBuffer(pose, cap, light, overlay);
        } finally {
            cap.delegate = null;
        }
        GlowOutlineRenderer.queueModelOutline(cap.data, cap.count, spec.tex, spec.color,
                GlowOutlineRenderer.glowKeyFor(spec.identity, spec.category), spec.category, spec.priority);
    }

    /** Capture spec for an entity-surface layer (sheep wool, saddle, clothing, …), or null when the entity
     *  isn't glowing / is invisible. CAT_ENTITY + the entity id → folds into the body ring. */
    @Nullable
    public static OutlineSpec surfaceOutlineSpec(LivingEntity entity, ResourceLocation texture) {
        if (texture == null || entity.isInvisible()) return null;
        Resolution r = instanceResolver.resolve(entity);
        if (r == null || !(r.glowing || r.glowColors.length > 0)) return null;
        return new OutlineSpec(entity, texture, outlineColorFor(r), GlowOutlineRenderer.CAT_ENTITY, 0);
    }

    /** Body / entity-surface (sheep wool, saddle, stray clothing, …) silhouette: captured ONLY when the
     *  entity itself glows (entity NBT), traced against {@code texture} (the body or layer texture). Shares
     *  the figure's CAT_ENTITY id, so body + all its surface layers + its armor compose as one ring. Worn
     *  armor outlines itself separately (CAT_ARMOR). Re-renders the freshly-posed model (entity-local pose)
     *  into a record-only buffer to trace the silhouette. */
    public static void captureEntityOutline(LivingEntity entity, Model model, ResourceLocation texture,
                                            PoseStack pose, int light) {
        if (entity.isInvisible()) return;
        Resolution r = instanceResolver.resolve(entity);
        if (r == null || !(r.glowing || r.glowColors.length > 0)) return;
        captureModelSilhouette(entity, entity, model, texture, pose, light, outlineColorFor(r),
                GlowOutlineRenderer.CAT_ENTITY, 0);
    }

    /** Outline colour for an entity-glow figure: its glow colours (animated) if set, else its glint
     *  layer-0 colour, else white. Also used by the armor-outline path so body + armor of one figure
     *  ring in the same colour. */
    public static int outlineColorFor(@Nullable Resolution r) {
        float off = CustomGlintRenderer.GLOW_RING_PHASE_OFFSET;
        if (r != null && r.glowColors.length > 0) return CustomGlintRenderer.computeAnimatedGlowColor(r.glowColors, 1.0f, true, off);
        if (r != null && r.data != null) return CustomGlintRenderer.computeAnimatedColor(r.data, 0, off);
        return 0xFFFFFFFF;
    }

    @Nullable
    private static CustomGlint.Data resolveData(LivingEntity entity) {
        Resolution r = instanceResolver.resolve(entity);
        if (r != null) return r.data;
        return CustomGlint.getEntityGlint(entity.getType());
    }

    /**
     * Unwraps the entity-glint buffer wrapper to the real underlying {@code BufferSource}. Mixins
     * that must {@code endBatch} a specific RenderType to order passes (the stencil-mask-before-glint
     * sequence on mount/dragon armor) need the real BufferSource: during entity rendering the layer
     * receives {@link GlintWrappingBufferSource}, which is NOT a BufferSource, so a raw
     * {@code instanceof BufferSource} check silently skips the flush. That only bites when the entity
     * ALSO carries its own glint (so the wrapper is installed) — e.g. a glinted dragon wearing glinted
     * armor; the body part's glint then bled across the whole dragon.
     */
    public static MultiBufferSource unwrap(MultiBufferSource buffer) {
        return buffer instanceof GlintWrappingBufferSource w ? w.delegate : buffer;
    }

    private static void fillPremul(float[] buf, int argb) {
        float a = ((argb >> 24) & 0xFF) / 255.0f;
        buf[0] = ((argb >> 16) & 0xFF) / 255.0f * a;
        buf[1] = ((argb >>  8) & 0xFF) / 255.0f * a;
        buf[2] = ( argb        & 0xFF) / 255.0f * a;
        buf[3] = 1.0f;
    }

    /**
     * MultiBufferSource wrapper that auto-fans every entity-* RenderType request through the
     * glint render-types of all configured layers. The strategy mirrors what ItemRendererMixin's
     * getFoilBuffer path does for items: a VertexMultiConsumer of {base, glint_layer0, glint_layer1, …}.
     *
     * RenderType filter: only RTs whose toString begins with "entity_" get wrapped. That covers
     * entityCutoutNoCull / entitySolid / entityTranslucent / itemEntityTranslucentCull / etc.
     * (used by all stock entity models and overlay layers like StrayClothingLayer), and
     * excludes text/nametag/particles/our own glint RTs so they pass through untouched.
     */
    public static final class GlintWrappingBufferSource implements MultiBufferSource {
        final MultiBufferSource delegate;
        final CustomGlint.Data glint;

        GlintWrappingBufferSource(MultiBufferSource delegate, CustomGlint.Data glint) {
            this.delegate = delegate;
            this.glint = glint;
        }

        @Override
        public VertexConsumer getBuffer(RenderType rt) {
            if (!shouldApplyGlint(rt)) return delegate.getBuffer(rt);
            // Don't bleed entity glint onto the item the entity is holding. ItemRenderer.render
            // (mixin'd to set CURRENT_ITEM_STACK at HEAD, clear at RETURN) is invoked through
            // HeldItemLayer / ItemInHandLayer during the entity render — those item draws route
            // through entity_solid / entity_translucent RTs which would otherwise match
            // shouldApplyGlint and fan-out the entity's glint onto the item's vertex stream.
            // The item has its own per-item glint via ItemRendererMixin's getFoilBuffer, which
            // still fires correctly; we just want to skip the entity-glint overlay on it.
            if (CustomGlintRenderer.CURRENT_ITEM_STACK.get() != null) return delegate.getBuffer(rt);

            // Acquire glint buffers BEFORE the base. The body's entity_* RT is non-fixed so it
            // shares the BufferSource's single non-fixed BufferBuilder, while our glint RTs are
            // registered in fixedBuffers (dedicated builders). If we got `base` first, the first
            // `delegate.getBuffer(grt)` call would see lastState=body_rt (non-fixed) and switch
            // away from it, which in vanilla BufferSource.getBuffer ends the previous non-fixed
            // builder — i.e. flushes the body builder while it's still empty and leaves it in a
            // non-building state. Subsequent vertex writes to `base` then drop on the floor and
            // the body renders invisible. Acquiring `base` last leaves it as the current active
            // builder when the model writes.
            CustomGlint.Layer[] layers = glint.layers();
            float[] buf = CustomGlintRenderer.COLOR_BUF.get();
            List<VertexConsumer> list = new ArrayList<>(layers.length + 1);
            for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
                if (CustomGlint.isChromatic(layers[layerIdx])) {
                    RenderType crt = CustomGlintRenderer.forChromaticEntityGlint(glint, layerIdx);
                    if (crt != null) list.add(delegate.getBuffer(crt));
                    continue;
                }
                int[] colors = layers[layerIdx].colors().length == 0 ? CustomGlintRenderer.WHITE_COLOR : layers[layerIdx].colors();
                if (layers[layerIdx].simultaneous()) {
                    for (int i = 0; i < colors.length; i++) {
                        fillPremul(buf, colors[i]);
                        RenderType grt = CustomGlintRenderer.forEntityGlint(glint, layerIdx, buf, i);
                        if (grt != null) list.add(delegate.getBuffer(grt));
                    }
                } else {
                    int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                    fillPremul(buf, color);
                    RenderType grt = CustomGlintRenderer.forEntityGlint(glint, layerIdx, buf, 0);
                    if (grt != null) list.add(delegate.getBuffer(grt));
                }
            }
            VertexConsumer base = delegate.getBuffer(rt);
            if (list.isEmpty()) return base;
            list.add(base);
            return VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
        }

        // RenderType instances are stable singletons, so cache the (expensive) toString-based verdict
        // by identity — getBuffer is called for every RT requested during a glinted entity's render,
        // every frame, and rt.toString() allocated a fresh String each time.
        private static final Map<RenderType, Boolean> GLINT_RT_VERDICT = new IdentityHashMap<>();

        /** Dropped on resource reload (via {@link CustomGlintRenderer#clearTextures()}) so the verdict map
         *  doesn't pin RenderType singletons across reloads, matching the other reload-cleared caches. */
        public static void clearRtVerdictCache() { GLINT_RT_VERDICT.clear(); }

        private static boolean shouldApplyGlint(RenderType rt) {
            Boolean cached = GLINT_RT_VERDICT.get(rt);
            if (cached != null) return cached;
            String name = rt.toString();
            // Cover entity_cutout_no_cull / entity_solid / entity_translucent / etc.
            boolean verdict = name.startsWith("entity_") || name.startsWith("RenderType[entity_");
            GLINT_RT_VERDICT.put(rt, verdict);
            return verdict;
        }
    }

    /** {@link VertexConsumer} for tracing a posed model into a glow silhouette: captures each vertex's
     *  camera-relative position + UV as {@code [x,y,z,u,v]} and drops everything else. The model's big
     *  {@code addVertex(...)} convenience method decomposes into {@code addVertex(x,y,z)} then
     *  {@code setUv(u,v)} (among others), so we stash the position on {@code addVertex} and flush the
     *  5-tuple on {@code setUv}.
     *
     *  <p>When {@link #delegate} is set it ALSO forwards every call to that real buffer, so a single model
     *  walk both DRAWS the entity and RECORDS its silhouette — the in-phase tee (no second
     *  {@code renderToBuffer} walk). With {@code delegate == null} it is record-only (the re-render path used
     *  by armor / surface layers). The forwarded position is already pose-transformed (the convenience method
     *  transforms before calling {@code addVertex(x,y,z)}), so the entity renders identically. */
    private static final ThreadLocal<CapturingModelConsumer> CAPTURE_POOL =
            ThreadLocal.withInitial(CapturingModelConsumer::new);

    public static final class CapturingModelConsumer implements VertexConsumer {
        public float[] data = new float[4096];
        public int count = 0;
        public VertexConsumer delegate; // non-null = in-phase tee (forward + record); null = record-only
        private float px, py, pz;

        /** Reset for reuse: drop the recorded vertices + delegate but keep the (already-grown) backing array. */
        public void reset() { count = 0; delegate = null; }

        private void put(float u, float v) {
            if (count + 5 > data.length) data = Arrays.copyOf(data, data.length * 2);
            data[count++] = px; data[count++] = py; data[count++] = pz; data[count++] = u; data[count++] = v;
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) { px = x; py = y; pz = z; if (delegate != null) delegate.addVertex(x, y, z); return this; }
        @Override public VertexConsumer setUv(float u, float v) { put(u, v); if (delegate != null) delegate.setUv(u, v); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { if (delegate != null) delegate.setColor(r, g, b, a); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { if (delegate != null) delegate.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { if (delegate != null) delegate.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float nx, float ny, float nz) { if (delegate != null) delegate.setNormal(nx, ny, nz); return this; }
    }
}
