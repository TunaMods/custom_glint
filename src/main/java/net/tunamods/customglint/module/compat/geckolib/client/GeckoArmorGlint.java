package net.tunamods.customglint.module.compat.geckolib.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Glue for {@code GeoArmorRendererMixin}. {@link #stash} records the stack/wearer GeckoLib is about to
 * render (captured at {@code prepForRender}); {@link #stashTexture} records the armor texture off the
 * {@code actuallyRender} {@link RenderType} arg; {@link #wrapGlint} wraps the {@link VertexConsumer}
 * GeckoLib is about to draw the Geo mesh into, fanning the same draw across our glint render types and
 * (when glowing) a record-only silhouette capture; {@link #flush} (at {@code actuallyRender} RETURN)
 * queues that silhouette as a glow ring keyed on the wearer + {@code CAT_ARMOR}.
 */
public final class GeckoArmorGlint {
    private GeckoArmorGlint() {}

    private static final ThreadLocal<ItemStack> CURRENT_STACK = new ThreadLocal<>();
    private static final ThreadLocal<LivingEntity> CURRENT_ENTITY = new ThreadLocal<>();
    private static final ThreadLocal<ResourceLocation> CURRENT_TEX = new ThreadLocal<>();
    private static final ThreadLocal<EntityGlintRender.CapturingModelConsumer> PENDING_GLOW = new ThreadLocal<>();

    public static void stash(Entity entity, ItemStack stack) {
        // Only fan glint / capture glow while the 3D world is rendering. The inventory player preview also
        // routes GeckoLib armor through actuallyRender, but under the GUI ortho our entity-space glint/glow
        // would stretch into a giant projected ray, so skip it (wrapGlint sees a null stack and no-ops).
        if (!CustomGlintRenderer.isRenderingWorld()) {
            CURRENT_STACK.set(null);
            CURRENT_ENTITY.set(null);
            return;
        }
        CURRENT_STACK.set(stack);
        CURRENT_ENTITY.set(entity instanceof LivingEntity ? (LivingEntity) entity : null);
    }

    /** Record the armor texture from {@code actuallyRender}'s RenderType arg (used to trace the glow
     *  silhouette so cutout holes don't outline). Returns the type unchanged. */
    public static RenderType stashTexture(RenderType rt) {
        CURRENT_TEX.set(GlowOutlineRenderer.resolveRenderTypeTexture(rt));
        return rt;
    }

    public static VertexConsumer wrapGlint(VertexConsumer base) {
        PENDING_GLOW.set(null);
        ItemStack stack = CURRENT_STACK.get();
        if (stack == null || stack.isEmpty()) return base;
        CustomGlint.Data glint = CustomGlint.readCached(stack);
        boolean glowing = CustomGlint.isGlowing(stack);
        if (glint == null && !glowing) return base;

        MultiBufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        List<VertexConsumer> list = new ArrayList<>();
        list.add(base);

        if (glint != null) {
            CustomGlint.Layer[] layers = glint.layers();
            float[] buf = CustomGlintRenderer.COLOR_BUF.get();
            for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
                // Chromatic has no design PNG, so forArmorGlint returns null and the layer would silently
                // vanish. Mirrors HumanoidArmorLayerMixin's chromatic branch; chromaticWorldBuffer is a
                // plain getBuffer, kept as the single seam for chromatic surfaces.
                if (CustomGlint.isChromatic(layers[layerIdx])) {
                    RenderType crt = CustomGlintRenderer.forChromaticArmorGlint(glint, layerIdx);
                    if (crt != null) list.add(CustomGlintRenderer.chromaticWorldBuffer(bufferSource, crt));
                    continue;
                }
                int[] colors = layers[layerIdx].colors();
                if (layers[layerIdx].simultaneous()) {
                    for (int i = 0; i < colors.length; i++) {
                        CustomGlintRenderer.fillPremul(buf, colors[i]);
                        RenderType rt = CustomGlintRenderer.forArmorGlint(glint, layerIdx, buf, i);
                        if (rt != null) list.add(bufferSource.getBuffer(rt));
                    }
                } else {
                    int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                    CustomGlintRenderer.fillPremul(buf, color);
                    RenderType rt = CustomGlintRenderer.forArmorGlint(glint, layerIdx, buf, 0);
                    if (rt != null) list.add(bufferSource.getBuffer(rt));
                }
            }
        }

        // Record-only silhouette for the glow ring, replayed at RETURN against the stashed texture.
        if (glowing) {
            EntityGlintRender.CapturingModelConsumer cap = new EntityGlintRender.CapturingModelConsumer();
            cap.delegate = null;
            PENDING_GLOW.set(cap);
            list.add(cap);
        }

        if (list.size() == 1) return base;
        return VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
    }

    /** Queue the recorded armor silhouette as one glow ring, keyed on the wearer + CAT_ARMOR so it
     *  composes with the body ring (matches the vanilla-armor path). */
    public static void flush() {
        EntityGlintRender.CapturingModelConsumer cap = PENDING_GLOW.get();
        PENDING_GLOW.set(null);
        if (cap == null || cap.count <= 0) return;
        ResourceLocation tex = CURRENT_TEX.get();
        LivingEntity entity = CURRENT_ENTITY.get();
        ItemStack stack = CURRENT_STACK.get();
        if (tex == null || entity == null || stack == null) return;
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        GlowOutlineRenderer.queueModelOutline(cap.data, cap.count, tex, modelView,
                CustomGlintRenderer.resolveGlowColor(stack),
                GlowOutlineRenderer.glowKeyFor(entity, GlowOutlineRenderer.CAT_ARMOR),
                GlowOutlineRenderer.CAT_ARMOR);
    }
}
