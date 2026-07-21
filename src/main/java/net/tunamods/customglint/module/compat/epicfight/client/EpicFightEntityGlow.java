package net.tunamods.customglint.module.compat.epicfight.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client-only helper for the Epic Fight glow-outline compat. Epic Fight cancels vanilla
 * {@code LivingEntityRenderer.render} through {@code RenderLivingEvent.Pre} and draws its own
 * skinned mesh in {@code PatchedLivingEntityRenderer.render}, so our core
 * {@code LivingEntityRendererMixin} (the in-phase body tee that feeds the glow silhouette) never
 * fires for patched entities, so the outline just vanishes.
 *
 * This re-establishes the glow capture on Epic Fight's own render path. {@link PatchedLivingEntityRendererMixin}
 * wraps the {@code MultiBufferSource} arg at HEAD with a {@link CaptureSource}; every triangle-mode
 * {@code entity_*} draw (body mesh + patched armor / cape / eyes layers) is teed into a record-only
 * buffer, and at RETURN the accumulated silhouettes are queued into {@link GlowOutlineRenderer} keyed
 * so the whole figure composes as ONE ring.
 *
 * <p>Epic Fight meshes are drawn through {@code EpicFightRenderTypes.getTriangulated(...)}, a
 * TRIANGLES-mode RenderType, so the capture is queued via {@link GlowOutlineRenderer#queueModelOutlineTriangles}
 * (the default queue replays in QUADS order and would scramble a triangle-list stream).
 *
 * <p>Known limitation: Epic Fight's optional GPU-skinning path ("activate compute shader") transforms
 * vertices on the GPU and never touches the CPU {@code VertexConsumer}, so the silhouette can't be
 * captured there. That path is off by default; the outline reappears with it disabled.
 */
public final class EpicFightEntityGlow {
    private EpicFightEntityGlow() {}

    // One entry per active PatchedLivingEntityRenderer.render frame (a Deque, not a single slot, so a
    // nested patched render, should one ever occur, stays balanced). Pushed at HEAD, popped at RETURN.
    // ArrayDeque forbids null elements, so a non-glowing frame pushes NONE rather than null.
    private static final ThreadLocal<Deque<CaptureSource>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final CaptureSource NONE = new CaptureSource(null, 0, 0);

    /** HEAD hook. Two jobs, both because Epic Fight draws its mesh through a TRIANGLES-mode RenderType:
     *
     *  <p>1. Strip the core {@code GlintWrappingBufferSource} the vanilla {@code LivingEntityRendererMixin}
     *  installed (its {@code RenderLivingEvent.Pre} fires from vanilla {@code render} before Epic Fight
     *  cancels it). That wrapper fans the glint through QUADS RTs; fed Epic Fight's triangle stream it
     *  shatters into giant facets. We re-install the same fan-out through TRIANGLES-mode glint RTs instead.
     *
     *  <p>2. When the entity glows, tee every mesh draw into the glow silhouette (a {@link CaptureSource}).
     *
     *  Always pushes a stack frame (NONE when not capturing) so the RETURN pop stays balanced. */
    public static MultiBufferSource wrap(LivingEntity entity, MultiBufferSource original) {
        if (original instanceof CaptureSource) { STACK.get().push((CaptureSource) original); return original; }
        MultiBufferSource raw = EntityGlintRender.unwrap(original); // drop the core QUADS glint wrapper
        CustomGlint.Data glint = entity.isInvisible() ? null : EntityGlintRender.glintDataFor(entity);
        MultiBufferSource glintLayer = glint != null
                ? new EntityGlintRender.GlintWrappingBufferSource(raw, glint, true) // TRIANGLES-mode glint
                : raw;
        EntityGlintRender.Resolution r = entity.isInvisible()
                ? null : EntityGlintRender.instanceResolver.resolve(entity);
        boolean glow = r != null && (r.glowing || r.glowColors.length > 0);
        if (!glow) { STACK.get().push(NONE); return glintLayer; }
        CaptureSource src = new CaptureSource(glintLayer, EntityGlintRender.outlineColorFor(r),
                GlowOutlineRenderer.glowKeyFor(entity, GlowOutlineRenderer.CAT_ENTITY));
        STACK.get().push(src);
        return src;
    }

    /** RETURN hook: pop this render's frame and queue whatever it captured. */
    public static void flush() {
        Deque<CaptureSource> stack = STACK.get();
        if (stack.isEmpty()) return;
        CaptureSource src = stack.pop();
        if (src != NONE) src.emit();
    }

    /**
     * Wrapping {@code MultiBufferSource} that tees every triangle-mode {@code entity_*} draw into a
     * per-RenderType {@link EntityGlintRender.CapturingModelConsumer} while forwarding it unchanged to the
     * real buffer, so the entity renders identically and its silhouette is recorded in the same walk.
     */
    private static final class CaptureSource implements MultiBufferSource {
        private final MultiBufferSource delegate;
        private final int color;
        private final int key;
        private final Map<RenderType, EntityGlintRender.CapturingModelConsumer> captures = new LinkedHashMap<>();

        CaptureSource(MultiBufferSource delegate, int color, int key) {
            this.delegate = delegate;
            this.color = color;
            this.key = key;
        }

        @Override
        public VertexConsumer getBuffer(RenderType rt) {
            VertexConsumer base = delegate.getBuffer(rt);
            // The held item routes through entity_* RTs too (ItemInHandLayer). It has its own per-item
            // glint / glow via ItemRendererMixin, so don't fold it into the entity's body ring.
            if (CustomGlintRenderer.CURRENT_ITEM_STACK.get() != null) return base;
            if (!isTriangleEntityRt(rt)) return base;
            if (GlowOutlineRenderer.resolveRenderTypeTexture(rt) == null) return base;
            EntityGlintRender.CapturingModelConsumer cap =
                    captures.computeIfAbsent(rt, k -> new EntityGlintRender.CapturingModelConsumer());
            cap.delegate = base;
            return cap;
        }

        /** Snapshot the draw-time modelview (camera transform; the per-entity position is already baked into
         *  the captured camera-relative vertices) and queue each texture's silhouette as one triangle mesh. */
        void emit() {
            if (captures.isEmpty()) return;
            Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
            for (Map.Entry<RenderType, EntityGlintRender.CapturingModelConsumer> e : captures.entrySet()) {
                EntityGlintRender.CapturingModelConsumer cap = e.getValue();
                cap.delegate = null;
                if (cap.count <= 0) continue;
                ResourceLocation tex = GlowOutlineRenderer.resolveRenderTypeTexture(e.getKey());
                if (tex == null) continue;
                GlowOutlineRenderer.queueModelOutlineTriangles(cap.data, cap.count, tex, modelView,
                        color, key, GlowOutlineRenderer.CAT_ENTITY);
            }
        }
    }

    private static boolean isTriangleEntityRt(RenderType rt) {
        if (rt.mode() != VertexFormat.Mode.TRIANGLES) return false;
        String n = rt.toString();
        return n.startsWith("entity_") || n.startsWith("RenderType[entity_");
    }
}
