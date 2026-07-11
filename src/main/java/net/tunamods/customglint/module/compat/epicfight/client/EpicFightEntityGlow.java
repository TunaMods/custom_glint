package net.tunamods.customglint.module.compat.epicfight.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client-only helper for the Epic Fight entity compat. Epic Fight cancels vanilla
 * {@code LivingEntityRenderer.render} through {@code RenderLivingEvent.Pre} and draws its own skinned mesh
 * in {@code PatchedLivingEntityRenderer.render}, so our core {@code LivingEntityRendererMixin} — the
 * in-phase body tee that feeds the glow silhouette — never fires for patched entities. The outline just
 * vanishes.
 *
 * <p>{@link net.tunamods.customglint.module.compat.epicfight.mixin.PatchedLivingEntityRendererMixin} wraps
 * the {@code MultiBufferSource} arg at HEAD with {@link #wrap} and calls {@link #flush} at RETURN. wrap does
 * two jobs, both because Epic Fight draws its mesh through a TRIANGLES-mode RenderType:
 *
 * <p>1. Strip the core QUADS {@code GlintWrappingBufferSource} (installed on vanilla render before Epic
 * Fight cancels it) and re-install a TRIANGLES-mode glint fan-out via
 * {@link EntityGlintRender#rewrapTriangles} — a QUADS glint RT fed a triangle stream shatters into facets.
 *
 * <p>2. When the entity glows, tee every triangle-mode {@code entity_*} mesh draw into a record-only buffer
 * ({@link CaptureSource}); at RETURN the accumulated silhouettes are queued via
 * {@link GlowOutlineRenderer#queueModelOutlineTriangles} keyed so the whole figure composes as ONE ring.
 *
 * <p>Known limitation: Epic Fight's optional GPU-skinning path ("activate compute shader") transforms
 * vertices on the GPU and never touches the CPU {@code VertexConsumer}, so the silhouette can't be captured
 * there. That path is off by default; the outline reappears with it disabled.
 */
public final class EpicFightEntityGlow {
    private EpicFightEntityGlow() {}

    // One entry per active PatchedLivingEntityRenderer.render frame (a Deque, not a single slot, so a nested
    // patched render stays balanced). Pushed at HEAD, popped at RETURN. ArrayDeque forbids null, so a
    // non-glowing frame pushes NONE rather than null.
    private static final ThreadLocal<Deque<CaptureSource>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final CaptureSource NONE = new CaptureSource(null, 0, 0);

    /** HEAD hook: re-install the TRIANGLES-mode glint, and tee the mesh into the glow silhouette when the
     *  entity glows. Always pushes a stack frame (NONE when not capturing) so the RETURN pop stays balanced. */
    public static MultiBufferSource wrap(LivingEntity entity, MultiBufferSource original) {
        if (original instanceof CaptureSource) { STACK.get().push((CaptureSource) original); return original; }
        // Strip the core QUADS glint wrapper and re-install a TRIANGLES-mode glint fan-out.
        MultiBufferSource glintLayer = EntityGlintRender.rewrapTriangles(entity, original);
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
     * real (glint-fanned) buffer, so the entity renders identically and its silhouette is recorded in the
     * same walk.
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
            // The held item routes through entity_* RTs too (ItemInHandLayer). It has its own per-item glint /
            // glow via ItemRendererMixin — don't fold it into the entity's body ring.
            if (CustomGlintRenderer.CURRENT_ITEM_STACK.get() != null) return base;
            if (!isTriangleEntityRt(rt)) return base;
            if (GlowOutlineRenderer.resolveRenderTypeTexture(rt) == null) return base;
            EntityGlintRender.CapturingModelConsumer cap =
                    captures.computeIfAbsent(rt, k -> new EntityGlintRender.CapturingModelConsumer());
            cap.delegate = base;
            return cap;
        }

        /** Queue each texture's silhouette as one triangle mesh under the figure's shared id. The captured
         *  vertices are camera-relative (the pose is baked in), so the AFTER_WEATHER world drain replays them
         *  under the world matrices exactly like the vanilla body tee. */
        void emit() {
            if (captures.isEmpty()) return;
            for (Map.Entry<RenderType, EntityGlintRender.CapturingModelConsumer> e : captures.entrySet()) {
                EntityGlintRender.CapturingModelConsumer cap = e.getValue();
                cap.delegate = null;
                if (cap.count <= 0) continue;
                ResourceLocation tex = GlowOutlineRenderer.resolveRenderTypeTexture(e.getKey());
                if (tex == null) continue;
                GlowOutlineRenderer.queueModelOutlineTriangles(cap.data, cap.count, tex,
                        color, key, GlowOutlineRenderer.CAT_ENTITY, 0);
            }
        }
    }

    private static boolean isTriangleEntityRt(RenderType rt) {
        if (rt.mode() != VertexFormat.Mode.TRIANGLES) return false;
        String n = rt.toString();
        return n.startsWith("entity_") || n.startsWith("RenderType[entity_");
    }
}
