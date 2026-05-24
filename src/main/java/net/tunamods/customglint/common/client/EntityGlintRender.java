package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.tunamods.customglint.common.CustomGlint;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Client-only entity glint draw. Called from {@link
 * net.tunamods.customglint.common.mixin.LivingEntityRendererMixin} at the point just before
 * the renderer's outer popPose, so the pose stack is still in entity-local space (matches the
 * vantage of armor/layer renderers).
 *
 * Resolution order: per-instance via the registered {@link InstanceResolver} (standalone module
 * installs one that reads from EntityGlintCache), then {@link CustomGlint#ENTITY_GLINTS} type
 * registry. API-jar-only embedders without a resolver still get type-registry-based glints.
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

    /** Default: no per-instance data — standalone module overrides this in client init. */
    public static InstanceResolver instanceResolver = entity -> null;

    /**
     * Wraps the renderer's MultiBufferSource so EVERY entity-* RenderType requested during
     * the entity render (base model + every RenderLayer like StrayClothingLayer, EyesLayer,
     * VillagerProfessionLayer, …) gets a glint overlay fan-out. The wrapper is a no-op if the
     * entity has no glint data, so we just return the original buffer in that case.
     *
     * Called from {@link net.tunamods.customglint.common.mixin.LivingEntityRendererMixin} at HEAD.
     */
    public static MultiBufferSource wrapForEntity(LivingEntity entity, MultiBufferSource original) {
        CustomGlint.Data data = resolveData(entity);
        if (data == null) return original;
        return new GlintWrappingBufferSource(original, data);
    }

    /**
     * Captured (model, texture, pose snapshot, light) for one render-layer surface, queued by
     * {@link net.tunamods.customglint.common.mixin.RenderLayerMixin} during the entity render and
     * drained at popPose by {@link #renderOutline}. Pose snapshot is taken because each layer
     * may have applied its own intermediate transforms onto the PoseStack before invoking the
     * shared static helpers in {@code RenderLayer}.
     */
    public static final class PendingOutline {
        public final EntityModel<?> model;
        public final ResourceLocation texture;
        public final Matrix4f pose;
        public final Matrix3f normal;
        public final int light;
        PendingOutline(EntityModel<?> m, ResourceLocation t, Matrix4f p, Matrix3f n, int l) {
            this.model = m; this.texture = t; this.pose = p; this.normal = n; this.light = l;
        }
    }

    /** Per-thread overlay queue. Cleared at the start of every entity outline drain so a
     *  non-glowing entity's queued (but never drained) entries don't leak into the next one. */
    private static final ThreadLocal<List<PendingOutline>> PENDING =
            ThreadLocal.withInitial(ArrayList::new);

    /**
     * Cheap-gate version of glow lookup used by the layer mixin before snapshotting pose.
     * Returns true iff the entity has a glow/glowColors signal that would trigger an outline.
     */
    private static boolean entityHasGlow(LivingEntity entity) {
        Resolution r = instanceResolver.resolve(entity);
        if (r == null) return false;
        return r.glowing || r.glowColors.length > 0;
    }

    /**
     * Called from {@link net.tunamods.customglint.common.mixin.RenderLayerMixin} at the RETURN of
     * the two shared static helpers in {@code RenderLayer} (coloredCutoutModelCopyLayerRender
     * and renderColoredCutoutModel). Snapshots the current pose and queues the overlay for
     * outline rendering at popPose-time, where it shares a single stencil slot with the base
     * body and every other overlay so the union of all silhouettes is stamped before any
     * dilated TEST pass runs. Without this union approach, an early layer's TEST ring would
     * spill into the area that a later overlay covers (e.g. stray's body outline visible
     * inside the clothing outline).
     */
    public static void queueLayerOutline(LivingEntity entity, EntityModel<?> model,
                                         ResourceLocation texture, PoseStack pose,
                                         int packedLight) {
        if (entity == null || model == null || texture == null) return;
        if (!entityHasGlow(entity)) return;
        PENDING.get().add(new PendingOutline(model, texture,
                new Matrix4f(pose.last().pose()), new Matrix3f(pose.last().normal()), packedLight));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void renderOutline(LivingEntityRenderer renderer, LivingEntity entity,
                                     PoseStack pose, MultiBufferSource buffer, int packedLight) {
        List<PendingOutline> pending = PENDING.get();
        CustomGlint.Data data;
        boolean glowing;
        int[] glowColors;
        Resolution r = instanceResolver.resolve(entity);
        if (r != null) {
            data = r.data;
            glowing = r.glowing;
            glowColors = r.glowColors;
        } else {
            data = CustomGlint.getEntityGlint(entity.getType());
            glowing = false;
            glowColors = new int[0];
        }
        if (!glowing && glowColors.length == 0) {
            // Stale entries left over if a layer queued without our glow gate matching
            // (shouldn't happen, but defensively clear).
            pending.clear();
            return;
        }
        int color = resolveOutlineColor(data, glowColors);
        EntityModel model = renderer.getModel();
        ResourceLocation texture = renderer.getTextureLocation(entity);

        List<PendingOutline> all = new ArrayList<>(pending.size() + 1);
        all.add(new PendingOutline(model, texture,
                new Matrix4f(pose.last().pose()), new Matrix3f(pose.last().normal()), packedLight));
        all.addAll(pending);
        pending.clear();

        // Unwrap so the outline's stencil RTs flow into the real buffer source (the wrap
        // re-fans entity_* RTs to glint, which would corrupt the stencil pass).
        MultiBufferSource raw = buffer instanceof GlintWrappingBufferSource w ? w.delegate : buffer;
        CustomGlintRenderer.doMultiModelOutline(pose, raw, color, all);
    }

    @Nullable
    private static CustomGlint.Data resolveData(LivingEntity entity) {
        Resolution r = instanceResolver.resolve(entity);
        if (r != null) return r.data;
        return CustomGlint.getEntityGlint(entity.getType());
    }

    private static void fillPremul(float[] buf, int argb) {
        float a = ((argb >> 24) & 0xFF) / 255.0f;
        buf[0] = ((argb >> 16) & 0xFF) / 255.0f * a;
        buf[1] = ((argb >>  8) & 0xFF) / 255.0f * a;
        buf[2] = ( argb        & 0xFF) / 255.0f * a;
        buf[3] = 1.0f;
    }

    private static int resolveOutlineColor(@Nullable CustomGlint.Data data, int[] glowColors) {
        if (glowColors.length > 0) return CustomGlintRenderer.computeAnimatedGlowColor(glowColors);
        if (data != null) return CustomGlintRenderer.computeAnimatedColor(data, 0);
        return 0xFFFFFFFF;
    }

    /**
     * MultiBufferSource wrapper that auto-fans every entity-* RenderType request through the
     * glint render-types of all configured layers. The strategy mirrors what
     * {@link CustomGlintRenderer#applyGlint(...)} (well, ItemRendererMixin's getFoilBuffer path)
     * does for items: a VertexMultiConsumer of {base, glint_layer0, glint_layer1, …}.
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
            // the body renders invisible (the dilated outline ring still appears because its
            // stencil-write pass re-renders the model into its own dedicated fixed builder).
            // Acquiring `base` last leaves it as the current active builder when the model writes.
            CustomGlint.Layer[] layers = glint.layers();
            float[] buf = CustomGlintRenderer.COLOR_BUF.get();
            List<VertexConsumer> list = new ArrayList<>(layers.length + 1);
            for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
                int[] colors = layers[layerIdx].colors();
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

        private static boolean shouldApplyGlint(RenderType rt) {
            String name = rt.toString();
            // Cover entity_cutout_no_cull / entity_solid / entity_translucent / etc.
            return name.startsWith("entity_") || name.startsWith("RenderType[entity_");
        }
    }
}
