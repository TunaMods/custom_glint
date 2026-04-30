// MIT License — Copyright (c) 2026 Likely Tuna | TunaMods — see LICENSE.txt
package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Intercepts getFoilBuffer/getFoilBufferDirect to inject custom per-item glint. Dual SRG/named targets, require=0 on all. */
@Mixin(ItemRenderer.class)
public class ItemRendererMixin {

    private static final ThreadLocal<ItemStack> CURRENT_STACK = new ThreadLocal<>();
    private static final ThreadLocal<float[]> COLOR_BUF = ThreadLocal.withInitial(() -> new float[4]);

    // ── Stack capture (HEAD) ──────────────────────────────────────────────────

    /** SRG target: captures the stack before render begins (obfuscated environments). */
    @Inject(method = "m_115143_", at = @At("HEAD"), require = 0)
    private void cg_captureStack_srg(ItemStack pItemStack, ItemDisplayContext pDisplayContext,
            boolean pLeftHand, PoseStack pPoseStack, MultiBufferSource pBuffer,
            int pCombinedLight, int pCombinedOverlay, BakedModel pModel, CallbackInfo ci) {
        CURRENT_STACK.set(pItemStack);
    }

    /** Named target: captures the stack before render begins (dev/deobf environments). */
    @Inject(
        method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
        at = @At("HEAD"), require = 0, remap = false
    )
    private void cg_captureStack_named(ItemStack pItemStack, ItemDisplayContext pDisplayContext,
            boolean pLeftHand, PoseStack pPoseStack, MultiBufferSource pBuffer,
            int pCombinedLight, int pCombinedOverlay, BakedModel pModel, CallbackInfo ci) {
        CURRENT_STACK.set(pItemStack);
    }

    // ── Stack clear (RETURN) ─────────────────────────────────────────────────

    /** SRG target: clears the captured stack after render completes. */
    @Inject(method = "m_115143_", at = @At("RETURN"), require = 0)
    private void cg_clearStack_srg(CallbackInfo ci) {
        CURRENT_STACK.remove();
    }

    /** Named target: clears the captured stack after render completes. */
    @Inject(
        method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void cg_clearStack_named(CallbackInfo ci) {
        CURRENT_STACK.remove();
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

    /** Returns a VertexMultiConsumer combining the custom glint layer(s) + base renderType, or null if no glint. */
    private static VertexConsumer applyGlint(MultiBufferSource buffer, RenderType renderType, boolean isItem) {
        ItemStack stack = CURRENT_STACK.get();
        if (stack == null) return null;
        CustomGlint.Data glint = CustomGlint.read(stack);
        if (glint == null) return null;

        int[] colors = glint.colors();
        float[] buf = COLOR_BUF.get();

        if (glint.simultaneous()) {
            VertexConsumer[] consumers = new VertexConsumer[colors.length + 1];
            for (int i = 0; i < colors.length; i++) {
                float a = ((colors[i] >> 24) & 0xFF) / 255.0f;
                buf[0] = ((colors[i] >> 16) & 0xFF) / 255.0f * a;
                buf[1] = ((colors[i] >>  8) & 0xFF) / 255.0f * a;
                buf[2] = ( colors[i]        & 0xFF) / 255.0f * a;
                buf[3] = 1.0f;
                consumers[i] = buffer.getBuffer(CustomGlint.forGlint(glint, buf, isItem, i));
            }
            consumers[colors.length] = buffer.getBuffer(renderType);
            return VertexMultiConsumer.create(consumers);
        } else {
            int color = CustomGlint.computeAnimatedColor(glint);
            float a = ((color >> 24) & 0xFF) / 255.0f;
            buf[0] = ((color >> 16) & 0xFF) / 255.0f * a;
            buf[1] = ((color >>  8) & 0xFF) / 255.0f * a;
            buf[2] = ( color        & 0xFF) / 255.0f * a;
            buf[3] = 1.0f;
            return VertexMultiConsumer.create(buffer.getBuffer(CustomGlint.forGlint(glint, buf, isItem, 0)), buffer.getBuffer(renderType));
        }
    }

}
