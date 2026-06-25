package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Intercepts render() to capture item stack + trigger glowing outline; intercepts getFoilBuffer/getFoilBufferDirect to inject custom per-item glint. */
@Mixin(ItemRenderer.class)
public class ItemRendererMixin {

    // ── Stack capture (HEAD) ──────────────────────────────────────────────────

    /** Captures the stack before render begins. */
    @Inject(
        method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
        at = @At("HEAD"), require = 0, remap = false
    )
    private void cg_captureStack_named(ItemStack pItemStack, ItemDisplayContext pDisplayContext,
            boolean pLeftHand, PoseStack pPoseStack, MultiBufferSource pBuffer,
            int pCombinedLight, int pCombinedOverlay, BakedModel pModel, CallbackInfo ci) {
        cg_onRenderHead(pItemStack, pDisplayContext, pLeftHand, pPoseStack, pBuffer, pCombinedLight, pCombinedOverlay, pModel);
    }

    /** HEAD logic: capture the stack, and for glowing GUI items inject the 4-direction halo BEFORE
     *  the actual item renders so the real item naturally overdraws the overlap and only the +/- 1
     *  GUI-pixel ring remains. The recursive renders run through this mixin again and will clear
     *  CURRENT_ITEM_STACK on each inner RETURN; re-set it after so the outer body's getFoilBuffer
     *  (for glint) still sees the stack. */
    private static void cg_onRenderHead(ItemStack stack, ItemDisplayContext ctx, boolean lh,
            PoseStack pose, MultiBufferSource buffer, int light, int overlay, BakedModel model) {
        CustomGlintRenderer.CURRENT_ITEM_STACK.set(stack);
    }

    // ── Stack clear + item outline (RETURN) ─────────────────────────────────

    /** Applies item outline for glowing items, then clears the captured stack. */
    @Inject(
        method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void cg_clearStack_named(ItemStack pItemStack, ItemDisplayContext pDisplayContext,
            boolean pLeftHand, PoseStack pPoseStack, MultiBufferSource pBuffer,
            int pCombinedLight, int pCombinedOverlay, BakedModel pModel, CallbackInfo ci) {
        cg_captureGlowOutline(pItemStack, pDisplayContext, pLeftHand, pPoseStack, pCombinedLight, pModel);
        CustomGlintRenderer.CURRENT_ITEM_STACK.remove();
    }

    // ── getFoilBuffer intercepts ─────────────────────────────────────────────
    // getFoilBuffer = batched rendering (world items, item frames). @Inject stacks; isCancelled() yields.

    /** Intercepts getFoilBuffer. */
    @Inject(
        method = "getFoilBuffer(Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/renderer/RenderType;ZZ)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
        at = @At("HEAD"), cancellable = true, require = 0, remap = false
    )
    private static void cg_onFoilBuffer_named(MultiBufferSource buffer, RenderType renderType,
            boolean isItem, boolean hasFoil, CallbackInfoReturnable<VertexConsumer> cir) {
        if (cir.isCancelled()) return;
        VertexConsumer consumer = applyGlint(buffer, renderType, isItem);
        if (consumer != null) cir.setReturnValue(consumer);
    }

    // ── getFoilBufferDirect intercepts ───────────────────────────────────────
    // getFoilBufferDirect = direct/GUI (immediate-mode) rendering.

    /** Intercepts getFoilBufferDirect. */
    @Inject(
        method = "getFoilBufferDirect(Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/renderer/RenderType;ZZ)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
        at = @At("HEAD"), cancellable = true, require = 0, remap = false
    )
    private static void cg_onFoilBufferDirect_named(MultiBufferSource buffer, RenderType renderType,
            boolean noEntity, boolean withGlint, CallbackInfoReturnable<VertexConsumer> cir) {
        if (cir.isCancelled()) return;
        VertexConsumer consumer = applyGlint(buffer, renderType, noEntity);
        if (consumer != null) cir.setReturnValue(consumer);
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** Returns a VertexMultiConsumer combining all glint layers + base renderType, or null if no glint. */
    private static VertexConsumer applyGlint(MultiBufferSource buffer, RenderType renderType, boolean isItem) {
        // During our stencil/translate outline passes, route all foil requests to the bare base
        // buffer. Otherwise vanilla's getFoilBuffer returns a VertexMultiConsumer of (glint, base)
        // — and because our outline MultiBufferSource lambdas redirect every RenderType to the
        // same underlying builder, the two delegates would share one builder and tear its vertex
        // state (vertex,vertex,color,color,...,endVertex,endVertex). Items that hardcode
        // isFoil()=true (e.g. Ice & Fire's ItemAlchemySword — dragonbone_sword_fire/ice/lightning)
        // tripped this whenever a custom glint+outline was applied: glint worked, outline didn't.
        if (CustomGlintRenderer.IN_OUTLINE.get()) return buffer.getBuffer(renderType);
        ItemStack stack = CustomGlintRenderer.CURRENT_ITEM_STACK.get();
        if (stack == null) return null;
        CustomGlint.Data glint = CustomGlint.read(stack);
        if (glint == null) return null;

        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();

        List<VertexConsumer> list = new ArrayList<>();
        for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
            int[] colors = layers[layerIdx].colors();
            if (layers[layerIdx].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    float a = ((colors[i] >> 24) & 0xFF) / 255.0f;
                    buf[0] = ((colors[i] >> 16) & 0xFF) / 255.0f * a;
                    buf[1] = ((colors[i] >>  8) & 0xFF) / 255.0f * a;
                    buf[2] = ( colors[i]        & 0xFF) / 255.0f * a;
                    buf[3] = 1.0f;
                    RenderType rt = CustomGlintRenderer.forGlint(glint, layerIdx, buf, isItem, i);
                    if (rt != null) list.add(buffer.getBuffer(rt));
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                float a = ((color >> 24) & 0xFF) / 255.0f;
                buf[0] = ((color >> 16) & 0xFF) / 255.0f * a;
                buf[1] = ((color >>  8) & 0xFF) / 255.0f * a;
                buf[2] = ( color        & 0xFF) / 255.0f * a;
                buf[3] = 1.0f;
                RenderType rt = CustomGlintRenderer.forGlint(glint, layerIdx, buf, isItem, 0);
                if (rt != null) list.add(buffer.getBuffer(rt));
            }
        }
        if (list.isEmpty()) return null;
        list.add(buffer.getBuffer(renderType));
        return VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
    }

    // ── Glow-outline capture ─────────────────────────────────────────────────
    // For glowing items rendered in the world (third-person held, dropped, frames, other players),
    // snapshot the item's silhouette (its baked quads + camera-relative pose + light + animated glow
    // colour) so GlowOutlineRenderer can replay it into the mask and ring it at AFTER_WEATHER. GUI flat
    // icons, the first-person hand (separate hand-FOV projection), and BEWLR items (trident/shield) are
    // not wired in this milestone.
    private static void cg_captureGlowOutline(ItemStack stack, ItemDisplayContext ctx, boolean leftHand,
            PoseStack pose, int light, BakedModel model) {
        if (ctx == ItemDisplayContext.GUI
                || ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND) {
            return;
        }
        if (model == null || model.isCustomRenderer()) return;
        if (!CustomGlint.isGlowing(stack) && !CustomGlint.hasGlowColors(stack)) return;

        int color;
        int[] glowColors = CustomGlint.getGlowColors(stack);
        if (glowColors.length > 0) {
            color = CustomGlintRenderer.computeAnimatedGlowColor(glowColors);
        } else {
            CustomGlint.Data glint = CustomGlint.read(stack);
            color = glint != null ? CustomGlintRenderer.computeAnimatedColor(glint, 0) : 0xFFFFFFFF;
        }

        // render() pushed the pose, applied the item's display transform (handleCameraTransforms ->
        // applyTransform) + the (-0.5,-0.5,-0.5) centering, drew the quads, then popped — so pose.last()
        // at this RETURN is the OUTER pose, missing both. Reproduce that exact sequence on a copy so the
        // silhouette matches the item's real on-screen scale and position.
        PoseStack tp = new PoseStack();
        tp.last().pose().set(pose.last().pose());
        tp.last().normal().set(pose.last().normal());
        BakedModel rendered = model.applyTransform(ctx, tp, leftHand);
        tp.translate(-0.5F, -0.5F, -0.5F);
        if (rendered == null || rendered.isCustomRenderer()) return;

        // Trace the item's full real shape — every face, including the 1/16 extrusion rim — so the
        // outline wraps the visible 3D item instead of a single offset sprite plane. The isolated Sodium
        // flicker specks that used to leak past the edge are removed in the composite's morphological-
        // opening guard, not by dropping geometry here.
        List<BakedQuad> quads = cg_collectQuads(rendered, null);
        if (quads.isEmpty()) return;

        GlowOutlineRenderer.queueWorldItem(quads, tp.last().copy(), light, color);
    }

    /** Collects a model's item quads. If {@code onlyDir} is non-null, keeps only quads whose face is that
     *  direction (used to take a single flat sprite face); null keeps every quad. */
    private static List<BakedQuad> cg_collectQuads(BakedModel model, Direction onlyDir) {
        List<BakedQuad> out = new ArrayList<>();
        RandomSource random = RandomSource.create();
        for (Direction dir : Direction.values()) {
            random.setSeed(42L);
            for (BakedQuad q : model.getQuads(null, dir, random)) {
                if (onlyDir == null || q.getDirection() == onlyDir) out.add(q);
            }
        }
        random.setSeed(42L);
        for (BakedQuad q : model.getQuads(null, null, random)) {
            if (onlyDir == null || q.getDirection() == onlyDir) out.add(q);
        }
        return out;
    }

}
