package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import org.joml.Matrix4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
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

/** Intercepts render() to capture item stack + trigger glowing outline; intercepts getFoilBuffer/getFoilBufferDirect to inject custom per-item glint. Dual SRG/named targets, require=0 on all. */
@Mixin(ItemRenderer.class)
public class ItemRendererMixin {

    // ── Stack capture (HEAD) ──────────────────────────────────────────────────

    /** SRG target: captures the stack before render begins (obfuscated environments). */
    @Inject(method = "m_115143_", at = @At("HEAD"), require = 0)
    private void cg_captureStack_srg(ItemStack pItemStack, ItemDisplayContext pDisplayContext,
            boolean pLeftHand, PoseStack pPoseStack, MultiBufferSource pBuffer,
            int pCombinedLight, int pCombinedOverlay, BakedModel pModel, CallbackInfo ci) {
        cg_onRenderHead(pItemStack, pDisplayContext, pLeftHand, pPoseStack, pBuffer, pCombinedLight, pCombinedOverlay, pModel);
    }

    /** Named target: captures the stack before render begins (dev/deobf environments). */
    @Inject(
        method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
        at = @At("HEAD"), require = 0, remap = false
    )
    private void cg_captureStack_named(ItemStack pItemStack, ItemDisplayContext pDisplayContext,
            boolean pLeftHand, PoseStack pPoseStack, MultiBufferSource pBuffer,
            int pCombinedLight, int pCombinedOverlay, BakedModel pModel, CallbackInfo ci) {
        cg_onRenderHead(pItemStack, pDisplayContext, pLeftHand, pPoseStack, pBuffer, pCombinedLight, pCombinedOverlay, pModel);
    }

    /** HEAD logic shared between SRG and named injects: capture the stack so the body's
     *  getFoilBuffer (for glint) can resolve the glint data for this item. */
    private static void cg_onRenderHead(ItemStack stack, ItemDisplayContext ctx, boolean lh,
            PoseStack pose, MultiBufferSource buffer, int light, int overlay, BakedModel model) {
        CustomGlintRenderer.CURRENT_ITEM_STACK.set(stack);
    }

    // ── Stack clear (RETURN) ─────────────────────────────────────────────────

    /** SRG target: captures the glow-outline silhouette, then clears the captured stack. */
    @Inject(method = "m_115143_", at = @At("RETURN"), require = 0)
    private void cg_clearStack_srg(ItemStack pItemStack, ItemDisplayContext pDisplayContext,
            boolean pLeftHand, PoseStack pPoseStack, MultiBufferSource pBuffer,
            int pCombinedLight, int pCombinedOverlay, BakedModel pModel, CallbackInfo ci) {
        cg_captureGlowOutline(pItemStack, pDisplayContext, pLeftHand, pPoseStack, pCombinedLight, pModel);
        CustomGlintRenderer.CURRENT_ITEM_STACK.remove();
    }

    /** Named target: captures the glow-outline silhouette, then clears the captured stack. */
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

    // ── Glow-outline capture ─────────────────────────────────────────────────
    // For a glowing item, snapshot its silhouette (baked quads + camera-relative pose + captured modelview
    // + light + animated glow colour) so GlowOutlineRenderer can replay it into the mask and ring it.
    // World items (third-person held, dropped, item frames, other players) drain at AFTER_WEATHER; the
    // first-person held item drains at the hand pass. GUI icons and special / 3D BEWLR items are deferred.
    private static void cg_captureGlowOutline(ItemStack stack, ItemDisplayContext ctx, boolean leftHand,
            PoseStack pose, int light, BakedModel model) {
        // Guard against re-entry from our own special-item re-render (renderStatic re-runs render(),
        // whose RETURN re-enters here).
        if (CustomGlintRenderer.IN_OUTLINE.get()) return;
        if (model == null) return;
        if (!CustomGlint.isGlowing(stack)) return;
        boolean gui = ctx == ItemDisplayContext.GUI;
        boolean firstPerson = ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;

        int color = CustomGlintRenderer.resolveGlowColor(stack);
        // GUI-only icon anchor (slot centre + on-screen size + texture resolution) for the drain's ring
        // sizing + slot clamp; null for world / first-person (they scissor by silhouette bounds).
        float[] guiAnchor = gui ? cg_guiAnchor(pose, model) : null;

        // Special / 3D BEWLR items (trident, shield, any isCustomRenderer item, incl. modded) have no
        // baked quads. Re-render the whole item through renderStatic into a record-only buffer (guarded by
        // IN_OUTLINE), capturing its animated, already-transformed geometry bucketed by texture — generic,
        // no per-item knowledge. Works in the GUI too (inventory/hotbar trident, shield, modded 3D items).
        if (model.isCustomRenderer()) {
            cg_captureSpecialOutline(stack, ctx, leftHand, pose, color, guiAnchor);
            return;
        }

        // render() pushed the pose, applied the item's display transform (handleCameraTransforms ->
        // applyTransform) + the (-0.5,-0.5,-0.5) centering, drew the quads, then popped — so pose.last()
        // at this RETURN is the OUTER pose, missing both. Reproduce that sequence on a copy so the
        // silhouette matches the item's real on-screen scale and position.
        PoseStack tp = new PoseStack();
        tp.last().pose().set(pose.last().pose());
        tp.last().normal().set(pose.last().normal());
        BakedModel rendered = model.applyTransform(ctx, tp, leftHand);
        tp.translate(-0.5F, -0.5F, -0.5F);
        if (rendered == null || rendered.isCustomRenderer()) return;

        List<BakedQuad> quads = cg_collectQuads(rendered);
        if (quads.isEmpty()) return;
        // Snapshot the live modelview the item is drawn under (camera transform) so the deferred world/FP
        // replay reproduces the exact transform instead of the differing drain-time one. The GUI drain is
        // immediate (matrices still live) so it ignores this.
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        // tp is a fresh PoseStack created for this capture and never popped/reused, so its top Pose is a
        // stable object safe to hand off (1.20.1 has no PoseStack.Pose#copy()).
        if (gui) GlowOutlineRenderer.queueGuiItem(quads, tp.last(), light, color, guiAnchor);
        else if (firstPerson) GlowOutlineRenderer.queueHeldFpItem(quads, tp.last(), modelView, light, color);
        else GlowOutlineRenderer.queueWorldItem(quads, tp.last(), modelView, light, color);
    }

    /** Capture a special / 3D BEWLR item's silhouette by re-rendering it through renderStatic into a
     *  record-only buffer. {@code pose} is the OUTER pose at render() RETURN; renderStatic re-applies the
     *  display transform, so the recorded positions are camera-relative and already include the item's
     *  animation (riptide spin, block tilt — it lives in the pose the model draws under). IN_OUTLINE
     *  prevents recursion and makes applyGlint route to the bare recording buffer. The capture buckets
     *  vertices by the texture each RenderType draws through, so the silhouette traces the real item shape
     *  via that texture's alpha (a trident traces the trident, not its square model hull). */
    private static void cg_captureSpecialOutline(ItemStack stack, ItemDisplayContext ctx, boolean leftHand,
            PoseStack pose, int color, float[] guiAnchor) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        PoseStack tp = new PoseStack();
        tp.last().pose().set(pose.last().pose());
        tp.last().normal().set(pose.last().normal());
        GlowOutlineRenderer.CapturingBufferSource cap = new GlowOutlineRenderer.CapturingBufferSource();
        CustomGlintRenderer.IN_OUTLINE.set(true);
        try {
            mc.getItemRenderer().renderStatic(mc.player, stack, ctx, leftHand, tp, cap, mc.level,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        } finally {
            CustomGlintRenderer.IN_OUTLINE.set(false);
        }
        cap.queueGroups(color, modelView, ctx, guiAnchor);
    }

    /** Icon anchor for the GUI glow drain: {@code [x,y,z]} = the OUTER pose translation (the slot/preview
     *  centre, before the model's display transform), {@code [3]} = the icon's nominal half-size in GUI px
     *  (½ the pose x-axis scale; 8 for a 16-px slot, 40 for the 5x wand preview), {@code [4]} = the item
     *  texture's resolution in px (16 / 32 / 64…), read from the model's particle sprite. The drain sizes the
     *  ring per TEXTURE pixel, so a 32x32 / 64x64 item doesn't get a ring twice / four times too thick. */
    private static float[] cg_guiAnchor(PoseStack pose, BakedModel model) {
        Matrix4f m = pose.last().pose();
        float sx = (float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02());
        float texRes = 16.0f;
        try {
            TextureAtlasSprite sp = model.getParticleIcon();
            if (sp != null && sp.contents() != null && sp.contents().width() > 0) texRes = sp.contents().width();
        } catch (Throwable ignored) {
            // Some modded models throw from the no-data getParticleIcon(); fall back to 16.
        }
        return new float[]{ m.m30(), m.m31(), m.m32(), sx * 0.5f, texRes };
    }

    /** Every baked quad of {@code model} (all directions + the general bucket), so the silhouette traces
     *  the item's full real shape including the 1/16 extrusion rim. */
    private static List<BakedQuad> cg_collectQuads(BakedModel model) {
        List<BakedQuad> out = new ArrayList<>();
        RandomSource random = RandomSource.create();
        for (Direction dir : Direction.values()) {
            random.setSeed(42L);
            out.addAll(model.getQuads(null, dir, random));
        }
        random.setSeed(42L);
        out.addAll(model.getQuads(null, null, random));
        return out;
    }

    // ── getFoilBuffer intercepts ─────────────────────────────────────────────
    // getFoilBuffer = batched rendering (world items, item frames). @Inject stacks; isCancelled() yields.

    /** SRG target: intercepts getFoilBuffer in obfuscated environments. */
    @Inject(method = "m_115211_", at = @At("HEAD"), cancellable = true, require = 0)
    private static void cg_onFoilBuffer_srg(MultiBufferSource buffer, RenderType renderType,
            boolean isItem, boolean hasFoil, CallbackInfoReturnable<VertexConsumer> cir) {
        if (cir.isCancelled()) return;
        VertexConsumer consumer = applyGlint(buffer, renderType, isItem);
        if (consumer != null) cir.setReturnValue(consumer);
    }

    /** Named target: intercepts getFoilBuffer in dev/deobf environments. */
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

    /** SRG target: intercepts getFoilBufferDirect in obfuscated environments. */
    @Inject(method = "m_115222_", at = @At("HEAD"), cancellable = true, require = 0)
    private static void cg_onFoilBufferDirect_srg(MultiBufferSource buffer, RenderType renderType,
            boolean noEntity, boolean withGlint, CallbackInfoReturnable<VertexConsumer> cir) {
        if (cir.isCancelled()) return;
        VertexConsumer consumer = applyGlint(buffer, renderType, noEntity);
        if (consumer != null) cir.setReturnValue(consumer);
    }

    /** Named target: intercepts getFoilBufferDirect in dev/deobf environments. */
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
        // During the glow-outline special-item re-render, route foil requests to the bare recording
        // buffer so the silhouette captures the item's geometry, not fanned-out glint layers.
        if (CustomGlintRenderer.IN_OUTLINE.get()) return buffer.getBuffer(renderType);
        ItemStack stack = CustomGlintRenderer.CURRENT_ITEM_STACK.get();
        if (stack == null) return null;
        CustomGlint.Data glint = CustomGlint.read(stack);
        if (glint == null) return null;

        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();

        List<VertexConsumer> list = new ArrayList<>();
        for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
            // Procedural chromatic: one shader-driven draw (the palette + seed ride the RenderType), no
            // per-colour fan-out and no texture sampling.
            if (CustomGlint.isChromatic(layers[layerIdx])) {
                RenderType crt = CustomGlintRenderer.forChromaticGlint(glint, layerIdx, isItem);
                if (crt != null) list.add(buffer.getBuffer(crt));
                continue;
            }
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

}
