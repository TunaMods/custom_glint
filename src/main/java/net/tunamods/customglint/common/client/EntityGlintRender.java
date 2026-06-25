package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.LivingEntity;
import net.tunamods.customglint.common.CustomGlint;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Client-only entity glint draw. Wraps the renderer's {@link MultiBufferSource} so every
 * {@code entity_*} RenderType requested during an entity render (base model + every RenderLayer)
 * gets a glint overlay fan-out.
 *
 * Resolution order: per-instance via the registered {@link InstanceResolver} (standalone module
 * installs one that reads from EntityGlintCache), then {@link CustomGlint#ENTITY_GLINTS} type
 * registry. Mods that bundle only the api jar without a resolver still get type-registry-based glints.
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
     * Force the client glint cache to re-read this entity's current persistent NBT. Call after
     * a client-side mutation (e.g. {@link CustomGlint#writeEntity}, {@link CustomGlint#setEntityGlowing},
     * {@link CustomGlint#setEntityGlowColors}) when you need the change visible immediately
     * without waiting for a server broadcast — typically preview UIs, replay viewers, or mods
     * that reconstruct entities from stored NBT on the client.
     *
     * Server-side callers should use {@link net.tunamods.customglint.common.entity.EntityGlintEvents#broadcast}
     * instead; the broadcast packet handler refreshes the cache on every tracking client.
     *
     * No-op (clears the cache entry) if the entity has no glint NBT.
     */
    public static void refreshClientCache(LivingEntity entity) {
        EntityGlintCache.put(entity.getUUID(), CustomGlint.entityGlintTag(entity));
    }

    /**
     * Wraps the renderer's MultiBufferSource so EVERY entity-* RenderType requested during
     * the entity render (base model + every RenderLayer like StrayClothingLayer, EyesLayer,
     * VillagerProfessionLayer, …) gets a glint overlay fan-out. The wrapper is a no-op if the
     * entity has no glint data, so we just return the original buffer in that case.
     *
     * Called from {@link net.tunamods.customglint.common.mixin.LivingEntityRendererMixin} at HEAD.
     */
    public static MultiBufferSource wrapForEntity(LivingEntity entity, MultiBufferSource original) {
        // Idempotent: never wrap an already-wrapped source (would double-wrap and break
        // body-builder vertex routing).
        if (original instanceof GlintWrappingBufferSource) return original;
        CustomGlint.Data data = resolveData(entity);
        if (data == null) return original;
        return new GlintWrappingBufferSource(original, data);
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
