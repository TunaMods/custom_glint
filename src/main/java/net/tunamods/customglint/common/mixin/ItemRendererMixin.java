package net.tunamods.customglint.common.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
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

    /**
     * Mute vanilla's glowing outline on an item that carries OUR glow, the item-pipeline twin of
     * {@code ModelFeatureRendererMixin.cg_muteVanillaGlow}. Covers dropped items on the floor and
     * 3rd-person held items: when such an item is also vanilla-glowing (e.g. a glowing dropped entity,
     * or held by a glowing mob), {@code renderItem} would tee its silhouette into the OutlineBufferSource
     * via {@code submit.outlineColor()}. We draw our own ring for it, so force the colour to 0 to drop
     * vanilla's tee and avoid two stacked outlines. Gated on the same glow condition this mixin queues our
     * item ring on (see {@code cg_renderItemHead}).
     */
    @ModifyExpressionValue(method = "renderItem",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeStorage$ItemSubmit;outlineColor()I"),
            require = 0)
    private int cg_muteVanillaItemGlow(int original, @Local(argsOnly = true) SubmitNodeStorage.ItemSubmit submit) {
        CgGlintHolder holder = (CgGlintHolder) (Object) submit;
        int[] glowColors = holder.customglint$getGlowColors();
        if (holder.customglint$isGlowing() || (glowColors != null && glowColors.length > 0)) {
            return 0;
        }
        return original;
    }

    @Inject(method = "renderItem", at = @At("HEAD"), require = 0)
    private void cg_renderItemHead(MultiBufferSource.BufferSource bufferSource,
            OutlineBufferSource outlineBufferSource, SubmitNodeStorage.ItemSubmit submit, CallbackInfo ci) {
        CgGlintHolder holder = (CgGlintHolder) (Object) submit;
        GlintCarrier.DRAW_GLINT.set(holder.customglint$getGlint());
        // Queue the item's glow outline for the AfterWeather drain (the same isolated
        // silhouette-mask + composite pass that draws entity rings, see EntityGlintRender). The item's
        // base quads have just been (or are about to be) drawn to the main target, so by drain time the
        // scene depth the mask shader samples for occlusion is committed.
        boolean glowing = holder.customglint$isGlowing();
        int[] glowColors = holder.customglint$getGlowColors();
        // GUI icons get their glow from the flat halo blit (GuiRendererMixin), not this 3D silhouette
        // composite, which drains at AfterWeather (a world phase) and would otherwise just queue
        // per-glowing-icon jobs every atlas bake that never draw correctly. Skip them.
        boolean isGui = submit.displayContext() == ItemDisplayContext.GUI;
        if (!isGui && (glowing || (glowColors != null && glowColors.length > 0))) {
            // First-person hand items go to a separate queue drained only at the hand point (view-space
            // pose; the world drain would project it against the world matrix and the ring would float
            // off the item, most visible under Iris, which renders the hand inside the level framegraph).
            // OR in the hand-render window flag: a special-model quad item (a Sophisticated Backpack) submits
            // its base model with ItemDisplayContext.NONE, so the node's own context can't report first-person;
            // the flag is live through the deferred hand draw (renderHandsWithItems HEAD..RETURN).
            boolean heldFp = (submit.displayContext() != null && submit.displayContext().firstPerson())
                    || EntityGlintRender.inFirstPersonHand();
            EntityGlintRender.queueItemOutline(submit.quads(), submit.pose(), submit.lightCoords(),
                    holder.customglint$getGlint(), glowing, glowColors,
                    holder.customglint$getGlowSpeed(), holder.customglint$getGlowInterp(), heldFp);
        }
        // Under an active shader pack NO glint layer can draw in-phase correctly: Iris replaces our program,
        // so chromatic goes flat white and normal glint goes SOLID (every gbuffer entity program it can pick
        // is opaque, it replaces the item surface instead of adding onto it). Queue EVERY layer for the
        // post-Iris overlay drain (world drain for 3rd-person/dropped, hand drain for first-person); the
        // in-phase applyGlint below skips all layers under a pack. Off the pack, applyGlint draws normally.
        CustomGlint.Data glint = holder.customglint$getGlint();
        if (!isGui && glint != null && CustomGlintRenderer.isShaderPackActive()) {
            // Same NONE-context fallback as the glow queue above (SB backpack): route by the hand-window flag.
            boolean heldFp = (submit.displayContext() != null && submit.displayContext().firstPerson())
                    || EntityGlintRender.inFirstPersonHand();
            CustomGlint.Layer[] gl = glint.layers();
            for (int layerIdx = 0; layerIdx < gl.length; layerIdx++) {
                if (CustomGlint.isChromatic(gl[layerIdx])) {
                    EntityGlintRender.queueChromaticItem(submit.quads(), submit.pose(),
                            glint, layerIdx, submit.lightCoords(), heldFp);
                    continue;
                }
                int[] colors = gl[layerIdx].colors();
                if (colors.length == 0) colors = new int[]{0xFFFFFFFF}; // unchosen layer → white placeholder
                if (gl[layerIdx].simultaneous()) {
                    for (int i = 0; i < colors.length; i++)
                        EntityGlintRender.queueGlintOverlayItem(submit.quads(), submit.pose(),
                                glint, layerIdx, i, colors[i], submit.lightCoords(), heldFp);
                } else {
                    int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                    EntityGlintRender.queueGlintOverlayItem(submit.quads(), submit.pose(),
                            glint, layerIdx, 0, color, submit.lightCoords(), heldFp);
                }
            }
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
        CustomGlint.Data glint = GlintCarrier.DRAW_GLINT.get();
        // Under an active shader pack our glint is drawn by the post-Iris overlay drain (queued in
        // cg_renderItemHead), never in-phase (an in-phase glint goes SOLID). Return a swallowing consumer so
        // vanilla's enchant foil is eaten here (our glint EATS it, never stacks) and nothing draws in-phase.
        if (glint != null && CustomGlintRenderer.isShaderPackActive()) {
            cir.setReturnValue(CustomGlintRenderer.NO_OP_CONSUMER);
            return;
        }
        VertexConsumer consumer = applyGlint(bufferSource, glint);
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

        List<VertexConsumer> list = new ArrayList<>();
        // Off the shader path this draws the glint in-phase. Under a pack the foil-buffer hook returns the
        // swallowing NO_OP consumer before ever reaching here, so this loop is the non-pack path only.
        for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
            int[] colors = layers[layerIdx].colors();
            if (colors.length == 0) colors = new int[]{0xFFFFFFFF}; // unchosen layer → white placeholder
            // Under a shader pack a chromatic layer is drawn by the post-Iris overlay drain (queued in
            // cg_renderItemHead), not here; drawing it in-phase would flash flat white. Skip it.
            if (CustomGlint.isChromatic(layers[layerIdx]) && CustomGlintRenderer.isShaderPackActive()) continue;
            // Chromatic composites every color in ONE draw (palette texture), so never loop per-color.
            if (layers[layerIdx].simultaneous() && !CustomGlint.isChromatic(layers[layerIdx])) {
                for (int i = 0; i < colors.length; i++) {
                    RenderType rt = CustomGlintRenderer.forGlint(glint, layerIdx, true, i);
                    if (rt != null) list.add(cg_colored(cg_buffer(buffer, rt), colors[i]));
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                RenderType rt = CustomGlintRenderer.forGlint(glint, layerIdx, true, 0);
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
     * and requesting a second such type flushes the first, which silently dropped every glint layer
     * past the first (and differs between the world source and the GUI source). Giving each layer a
     * dedicated buffer lets them all accumulate and draw together.
     */
    private static VertexConsumer cg_buffer(MultiBufferSource buffer, RenderType rt) {
        if (buffer instanceof MultiBufferSource.BufferSource src && !src.fixedBuffers.containsKey(rt)) {
            try {
                src.fixedBuffers.put(rt, new ByteBufferBuilder(rt.bufferSize()));
            } catch (UnsupportedOperationException ignored) {
                // Immutable fixedBuffers (Iris/Sodium), falls back to the shared builder.
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
