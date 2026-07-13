package net.tunamods.customglint.module.compat.elytraslot.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone-only ElytraSlot compat. ElytraSlot adds a dedicated Curios slot (id {@code elytra},
 * or sometimes {@code back} when its compatibility provider routes through Curios' back slot)
 * and renders the equipped elytra via its own {@code ElytraSlotLayer}. Vanilla's
 * {@code ElytraLayer} (which our {@code ElytraLayerMixin} hooks) only checks the chestplate
 * slot, so the glint never fires for Curios-slot elytras.
 *
 * Inject at TAIL of {@code ElytraSlotLayer.lambda$render$0} (the per-elytra render body invoked
 * from {@code Optional.ifPresent}). The lambda has just executed: {@code pushPose} →
 * {@code translate(0, 0, 0.125)} → {@code copyPropertiesTo} → {@code setupAnim} →
 * {@code renderToBuffer} → {@code popPose}. At TAIL the pose is restored, so we re-apply the same
 * (0, 0, 0.125) offset before drawing our glint / outline. The {@code elytraModel} field still
 * holds the setupAnim state from the prior call (model rotations aren't pose state), so there's no
 * need to re-run setupAnim.
 *
 * {@code ElytraRenderResult} is referenced only via {@code @Coerce Object} + reflection so this
 * compat class has zero compile/runtime dep on ElytraSlot. Same soft-compat pattern as our other
 * standalone-only mixins.
 */
@Pseudo
@Mixin(targets = "com.illusivesoulworks.elytraslot.client.ElytraSlotLayer", remap = false)
public class ElytraSlotLayerMixin {

    @Shadow private ElytraModel<?> elytraModel;

    private static volatile Method CG_STACK_M;
    private static volatile Method CG_TEXTURE_M;

    @Inject(method = "lambda$render$0", at = @At("TAIL"), require = 0, remap = false)
    private void cg_elytraSlotGlint(LivingEntity entity, PoseStack poseStack,
            float limbSwing, float limbSwingAmount, float partialTick,
            float ageInTicks, float netHeadYaw,
            MultiBufferSource buffer, int packedLight,
            @Coerce Object result, CallbackInfo ci) {
        if (result == null) return;
        ItemStack stack;
        ResourceLocation tex;
        try {
            Method sm = CG_STACK_M;
            if (sm == null) { sm = result.getClass().getMethod("stack"); CG_STACK_M = sm; }
            stack = (ItemStack) sm.invoke(result);
            Method tm = CG_TEXTURE_M;
            if (tm == null) { tm = result.getClass().getMethod("texture"); CG_TEXTURE_M = tm; }
            tex = (ResourceLocation) tm.invoke(result);
        } catch (Throwable t) {
            return;
        }
        if (stack == null || stack.isEmpty()) return;

        CustomGlint.Data glint = CustomGlint.read(stack);
        boolean glowing = CustomGlint.hasGlowEffect(stack);
        if (glint == null && !glowing) return;

        VertexConsumer combined = null;
        if (glint != null) {
            CustomGlint.Layer[] layers = glint.layers();
            float[] buf = CustomGlintRenderer.COLOR_BUF.get();
            List<VertexConsumer> list = new ArrayList<>();
            for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
                int[] colors = layers[layerIdx].colors().length == 0 ? CustomGlintRenderer.WHITE_COLOR : layers[layerIdx].colors();
                if (layers[layerIdx].simultaneous()) {
                    for (int i = 0; i < colors.length; i++) {
                        float a = ((colors[i] >> 24) & 0xFF) / 255.0f;
                        buf[0] = ((colors[i] >> 16) & 0xFF) / 255.0f * a;
                        buf[1] = ((colors[i] >>  8) & 0xFF) / 255.0f * a;
                        buf[2] = ( colors[i]        & 0xFF) / 255.0f * a;
                        buf[3] = 1.0f;
                        RenderType rt = CustomGlintRenderer.forArmorGlint(glint, layerIdx, buf, i);
                        if (rt != null) list.add(buffer.getBuffer(rt));
                    }
                } else {
                    int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                    float a = ((color >> 24) & 0xFF) / 255.0f;
                    buf[0] = ((color >> 16) & 0xFF) / 255.0f * a;
                    buf[1] = ((color >>  8) & 0xFF) / 255.0f * a;
                    buf[2] = ( color        & 0xFF) / 255.0f * a;
                    buf[3] = 1.0f;
                    RenderType rt = CustomGlintRenderer.forArmorGlint(glint, layerIdx, buf, 0);
                    if (rt != null) list.add(buffer.getBuffer(rt));
                }
            }
            if (!list.isEmpty()) {
                combined = list.size() == 1 ? list.get(0)
                        : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
            }
        }
        // Lambda popped the pose before returning, so re-apply ElytraSlot's (0, 0, 0.125) offset for both
        // the glint draw and the glow-outline capture. elytraModel's setupAnim from the prior render call is
        // preserved (model state, not pose), so the re-rendered silhouette matches the drawn wings.
        poseStack.pushPose();
        poseStack.translate(0.0f, 0.0f, 0.125f);
        if (combined != null) {
            elytraModel.renderToBuffer(poseStack, combined, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        }
        // Vanilla's ElytraLayer captures the glow outline via an in-phase tee; ElytraSlot's own layer never
        // does, so a Curios-slot elytra had no ring. Capture it here (record-only re-render), keyed by the
        // elytra STACK like the vanilla path so it keeps its own CAT_ARMOR ring.
        if (glowing && tex != null) {
            EntityGlintRender.captureModelSilhouette(entity, stack, elytraModel, tex, poseStack, packedLight,
                    CustomGlintRenderer.resolveGlowColor(stack), GlowOutlineRenderer.CAT_ARMOR, 1);
        }
        poseStack.popPose();
    }
}
