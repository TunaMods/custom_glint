package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

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

    /** HEAD logic: capture the stack being rendered so the getFoilBuffer intercepts below can read its
     *  glint Data. The matching RETURN inject clears it. (The glow-outline capture happens at RETURN in
     *  cg_captureGlowOutline, not here.) */
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
        // During our record-only glow-outline capture re-render, route all foil requests to the
        // bare base buffer. Otherwise vanilla's getFoilBuffer returns a VertexMultiConsumer of
        // (glint, base) — and because the capturing buffer source redirects every RenderType to the
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
            // Procedural chromatic: one shader-driven draw (the palette + seed ride the RenderType), no
            // per-colour fan-out and no texture sampling.
            if (CustomGlint.isChromatic(layers[layerIdx])) {
                RenderType crt = CustomGlintRenderer.forChromaticGlint(glint, layerIdx, isItem);
                if (crt != null) list.add(buffer.getBuffer(crt));
                continue;
            }
            // An undyed (empty-palette) non-chromatic layer renders white so the design stays visible without
            // any dye being stored. The animated branch already returns white for empty; default here for the
            // simultaneous fan-out so it draws one white pass instead of nothing.
            int[] colors = layers[layerIdx].colors().length == 0 ? CustomGlintRenderer.WHITE_COLOR : layers[layerIdx].colors();
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
        // De-dupe delegates by identity. Some buffer sources — notably the GUI immediate source under Sodium,
        // hit by the multi-layer Glint Table preview — hand back the SAME builder for more than one of our
        // RenderTypes, and VertexMultiConsumer rejects duplicate delegates ("Duplicate delegates" crash).
        // One builder can't be multiplexed against itself anyway, so collapsing duplicates is correct; the
        // world path returns distinct fixed buffers, so this is a no-op there.
        List<VertexConsumer> distinct = new ArrayList<>(list.size());
        for (VertexConsumer vc : list) {
            boolean dup = false;
            for (VertexConsumer seen : distinct) if (seen == vc) { dup = true; break; }
            if (!dup) distinct.add(vc);
        }
        return distinct.size() == 1 ? distinct.get(0)
                : VertexMultiConsumer.create(distinct.toArray(new VertexConsumer[0]));
    }

    // ── Glow-outline capture ─────────────────────────────────────────────────
    // For glowing items, snapshot the item's silhouette (its baked quads + camera-relative pose + light +
    // animated glow colour) so GlowOutlineRenderer can replay it into the mask and ring it. World items
    // (third-person held, dropped, frames, other players) queue for the AFTER_WEATHER drain; the
    // first-person held item queues for the hand-pass drain (drainHeldFp). GUI / inventory / HUD icons are
    // captured in GUI screen space and drained immediately (drainGui) while the GUI ortho matrices are live.
    private static void cg_captureGlowOutline(ItemStack stack, ItemDisplayContext ctx, boolean leftHand,
            PoseStack pose, int light, BakedModel model) {
        // Guard against re-entry from our own special-item re-render (renderStatic below runs render()
        // again, whose RETURN re-enters this method).
        if (CustomGlintRenderer.IN_OUTLINE.get()) return;
        boolean gui = ctx == ItemDisplayContext.GUI;
        boolean firstPerson = ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
        if (model == null) return;
        if (!CustomGlint.hasGlowEffect(stack)) return;

        int color = CustomGlintRenderer.resolveGlowColor(stack);
        // GUI-only icon anchor (slot centre + on-screen size + texture resolution) for the drain's ring
        // sizing + slot clamp; null for world / first-person (they scissor by silhouette bounds).
        float[] guiAnchor = gui ? cg_guiAnchor(pose, model) : null;

        // Special / 3D BEWLR items (trident, shield, any isCustomRenderer item) have no baked quads.
        // Re-render the whole item through renderStatic into a record-only buffer (IN_OUTLINE guards
        // recursion + suppresses the glint fan-out), capturing its animated, already-transformed
        // geometry — the proven approach from the pre-purge doItemOutline/doBewlrOutline path.
        if (model.isCustomRenderer()) {
            cg_captureSpecialOutline(stack, ctx, leftHand, pose, light, color, guiAnchor);
            return;
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

        if (gui) {
            // Capture in GUI screen space; the ring is drained at GuiGraphics.flush() RETURN (GuiGraphicsMixin)
            // so it composites AFTER the icon + its slot background are flushed to the main target, while
            // RenderSystem still holds the GUI ortho projection / modelview the icon was drawn under. The
            // OUTER pose translation is the icon's slot centre (GuiGraphics translated to it before the
            // display transform), and its x-axis scale is the icon's on-screen size (16 GUI px in a slot,
            // 5x that in the wand preview). Pass both so the drain sizes + clamps the ring to the real icon.
            GlowOutlineRenderer.queueGuiItem(quads, tp.last().copy(), light, color, guiAnchor);
        } else if (firstPerson) {
            GlowOutlineRenderer.queueHeldFpItem(quads, tp.last().copy(), light, color);
        } else {
            GlowOutlineRenderer.queueWorldItem(quads, tp.last().copy(), light, color);
        }
    }

    /** Capture a special / 3D BEWLR item's silhouette by re-rendering it through renderStatic into a
     *  record-only buffer. {@code pose} is the OUTER pose at render() RETURN; renderStatic re-applies the
     *  display transform, so the recorded positions are camera-relative and already include the item's
     *  animation (it lives in the pose the model draws under). IN_OUTLINE prevents recursion and makes
     *  applyGlint route to the bare (recording) buffer instead of fanning out glint layers.
     *
     *  <p>The capture buckets vertices by the texture each RenderType draws through, so the silhouette traces
     *  the item's REAL shape via that texture's alpha (a trident traces the trident, not its square model
     *  hull; an EK/IaF custom item traces its sprite, not the no-texture model parts). Textureless RTs fall
     *  back to a white fill. queueGroups routes each bucket to the world / FP / GUI drain by {@code ctx}. */
    private static void cg_captureSpecialOutline(ItemStack stack, ItemDisplayContext ctx, boolean leftHand,
            PoseStack pose, int light, int color, float[] guiAnchor) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        PoseStack tp = new PoseStack();
        tp.last().pose().set(pose.last().pose());
        tp.last().normal().set(pose.last().normal());
        GlowOutlineRenderer.CapturingBufferSource cap = new GlowOutlineRenderer.CapturingBufferSource(null);
        CustomGlintRenderer.IN_OUTLINE.set(true);
        try {
            mc.getItemRenderer().renderStatic(mc.player, stack, ctx, leftHand, tp, cap, mc.level,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        } finally {
            CustomGlintRenderer.IN_OUTLINE.set(false);
        }
        cap.queueGroups(color, ctx, guiAnchor);
    }

    /** Icon anchor for the GUI glow drain: {@code [x,y,z]} = the OUTER pose translation (the slot/preview
     *  centre, before the model's display transform), {@code [3]} = the icon's nominal half-size in GUI px
     *  (½ the pose x-axis scale; 8 for a 16-px slot, 40 for the 5x wand preview), {@code [4]} = the item
     *  texture's resolution in px (16 / 32 / 64…), read from the model's particle sprite. The drain sizes the
     *  ring per TEXTURE pixel, so a 32x32 or 64x64 item doesn't get a ring twice/four times too thick. */
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
