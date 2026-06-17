package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CgGlintHolder;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlintCarrier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the custom per-item glint in the 26.1 deferred item pipeline.
 *
 * <p>The 1.21.5 item-model rework deleted {@code ItemRenderer}; item quads are now queued as
 * {@code ItemSubmit} nodes and drawn later in {@link ItemFeatureRenderer#renderItem}, which routes the
 * foil overlay through the private {@code getFoilBuffer(MultiBufferSource, RenderType, PoseStack.Pose)}.
 * The original stack is gone by draw time, so the glint rides the node (see {@link CgGlintHolder} /
 * {@link GlintCarrier}). At HEAD of {@code renderItem} we publish the node's glint into
 * {@link GlintCarrier#DRAW_GLINT}; the foil-buffer call then returns a {@link VertexMultiConsumer} of
 * our animated glint layers in place of vanilla's single glint sheet. The base item texture is drawn
 * separately by {@code renderItem}, so the replacement carries only the glint layers.
 */
@Mixin(ItemFeatureRenderer.class)
public class ItemRendererMixin {

    @Inject(method = "renderItem", at = @At("HEAD"), require = 0)
    private void cg_renderItemHead(MultiBufferSource.BufferSource bufferSource,
            OutlineBufferSource outlineBufferSource, SubmitNodeStorage.ItemSubmit submit, CallbackInfo ci) {
        CgGlintHolder holder = (CgGlintHolder) (Object) submit;
        GlintCarrier.DRAW_GLINT.set(holder.customglint$getGlint());
        // Queue the item's glow outline for the AfterOpaqueFeatures drain (the same isolated
        // silhouette-mask + composite pass that draws entity rings — see EntityGlintRender). The item's
        // base quads have just been (or are about to be) drawn to the main target, so by drain time the
        // scene depth the mask shader samples for occlusion is committed.
        boolean glowing = holder.customglint$isGlowing();
        int[] glowColors = holder.customglint$getGlowColors();
        if (glowing || (glowColors != null && glowColors.length > 0)) {
            EntityGlintRender.queueItemOutline(submit.quads(), submit.pose(), submit.lightCoords(),
                    holder.customglint$getGlint(), glowing, glowColors);
        }
    }

    @Inject(method = "renderItem", at = @At("RETURN"), require = 0)
    private void cg_renderItemReturn(MultiBufferSource.BufferSource bufferSource,
            OutlineBufferSource outlineBufferSource, SubmitNodeStorage.ItemSubmit submit, CallbackInfo ci) {
        GlintCarrier.DRAW_GLINT.remove();
    }

    @Inject(
        method = "getFoilBuffer(Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/renderer/rendertype/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack$Pose;)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
        at = @At("HEAD"), cancellable = true, require = 0
    )
    private static void cg_onFoilBuffer(MultiBufferSource bufferSource, RenderType renderType,
            PoseStack.Pose foilDecalPose, CallbackInfoReturnable<VertexConsumer> cir) {
        if (CustomGlintRenderer.IN_OUTLINE.get()) return;
        VertexConsumer consumer = applyGlint(bufferSource, GlintCarrier.DRAW_GLINT.get());
        if (consumer != null) cir.setReturnValue(consumer);
    }

    /**
     * Builds a VertexMultiConsumer of every glint layer, or null if there is no renderable glint.
     * The 26.1 glint shader ({@code customglint:core/glint_color}) tints the grayscale design by the
     * per-vertex {@code Color} attribute (there is no per-RenderType ColorModulator hook anymore), so
     * each layer buffer is wrapped in a {@link CustomGlintRenderer.FullColorOverrideConsumer} that
     * forces the vertices to the layer's animated colour. Without this the design is drawn tinted white.
     */
    private static VertexConsumer applyGlint(MultiBufferSource buffer, CustomGlint.Data glint) {
        if (glint == null) return null;

        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();

        List<VertexConsumer> list = new ArrayList<>();
        for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
            int[] colors = layers[layerIdx].colors();
            if (layers[layerIdx].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    RenderType rt = CustomGlintRenderer.forGlint(glint, layerIdx, buf, true, i);
                    if (rt != null) list.add(cg_colored(cg_buffer(buffer, rt), colors[i]));
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                RenderType rt = CustomGlintRenderer.forGlint(glint, layerIdx, buf, true, 0);
                if (rt != null) list.add(cg_colored(cg_buffer(buffer, rt), color));
            }
        }
        if (list.isEmpty()) return null;
        return VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
    }

    /**
     * Returns the buffer for a glint layer, first ensuring the layer's RenderType has its own
     * {@code ByteBufferBuilder} in the <em>actual</em> buffer source being drawn through. An immediate
     * {@code BufferSource} routes any RenderType not in {@code fixedBuffers} through one shared builder,
     * and requesting a second such type flushes the first — which silently dropped every glint layer
     * past the first (and differs between the world source and the GUI source). Giving each layer a
     * dedicated buffer lets them all accumulate and draw together.
     */
    private static VertexConsumer cg_buffer(MultiBufferSource buffer, RenderType rt) {
        if (buffer instanceof MultiBufferSource.BufferSource src && !src.fixedBuffers.containsKey(rt)) {
            try {
                src.fixedBuffers.put(rt, new ByteBufferBuilder(rt.bufferSize()));
            } catch (UnsupportedOperationException ignored) {
                // Immutable fixedBuffers (Iris/Sodium) — falls back to the shared builder.
            }
        }
        return buffer.getBuffer(rt);
    }

    /** Wraps a glint layer buffer so every quad is drawn with the given ARGB colour on its vertices. The
     *  alpha is honoured verbatim (A=0 → fully transparent): every colour source carries full alpha by
     *  default (CustomGlint.color()/the named constants/the dye table all OR in 0xFF), so a 0 alpha byte
     *  only ever comes from the editor's A slider and must NOT be forced opaque. */
    private static VertexConsumer cg_colored(VertexConsumer wrapped, int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >>  8) & 0xFF;
        int b =  argb        & 0xFF;
        return new CustomGlintRenderer.FullColorOverrideConsumer(wrapped, r, g, b, a);
    }
}
