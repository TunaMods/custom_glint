// MIT License — Copyright (c) 2026 Likely Tuna | TunaMods — see LICENSE.txt
package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Intercepts renderArmorPiece at RETURN to draw custom glint on top of vanilla armor rendering. Dual SRG/named targets, require=0 on both. */
@Mixin(HumanoidArmorLayer.class)
public class HumanoidArmorLayerMixin {

    private static final ThreadLocal<float[]> COLOR_BUF = ThreadLocal.withInitial(() -> new float[4]);

    /** SRG target: injects at RETURN of renderArmorPiece in obfuscated environments. */
    @Inject(method = "m_117118_", at = @At("RETURN"), require = 0)
    private void cg_armorGlint_srg(PoseStack pPoseStack, MultiBufferSource pBuffer,
            LivingEntity pLivingEntity, EquipmentSlot pSlot, int pPackedLight,
            HumanoidModel pModel, CallbackInfo ci) {
        applyArmorGlint(pPoseStack, pBuffer, pLivingEntity, pSlot, pPackedLight, pModel);
    }

    /** Named target: injects at RETURN of renderArmorPiece in dev/deobf environments. */
    @Inject(
        method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/model/HumanoidModel;)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void cg_armorGlint_named(PoseStack pPoseStack, MultiBufferSource pBuffer,
            LivingEntity pLivingEntity, EquipmentSlot pSlot, int pPackedLight,
            HumanoidModel pModel, CallbackInfo ci) {
        applyArmorGlint(pPoseStack, pBuffer, pLivingEntity, pSlot, pPackedLight, pModel);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static void applyArmorGlint(PoseStack poseStack, MultiBufferSource buffer,
            LivingEntity entity, EquipmentSlot slot, int packedLight, HumanoidModel model) {
        ItemStack stack = entity.getItemBySlot(slot);
        if (stack.isEmpty()) return;
        CustomGlint.Data glint = CustomGlint.read(stack);
        if (glint == null) return;

        int[] colors = glint.colors();
        float[] buf = COLOR_BUF.get();
        VertexConsumer combined;

        if (glint.simultaneous()) {
            VertexConsumer[] consumers = new VertexConsumer[colors.length];
            for (int i = 0; i < colors.length; i++) {
                float a = ((colors[i] >> 24) & 0xFF) / 255.0f;
                buf[0] = ((colors[i] >> 16) & 0xFF) / 255.0f * a;
                buf[1] = ((colors[i] >>  8) & 0xFF) / 255.0f * a;
                buf[2] = ( colors[i]        & 0xFF) / 255.0f * a;
                buf[3] = 1.0f;
                consumers[i] = buffer.getBuffer(CustomGlint.forArmorGlint(glint, buf, i));
            }
            combined = colors.length == 1 ? consumers[0] : VertexMultiConsumer.create(consumers);
        } else {
            int color = CustomGlint.computeAnimatedColor(glint);
            float a = ((color >> 24) & 0xFF) / 255.0f;
            buf[0] = ((color >> 16) & 0xFF) / 255.0f * a;
            buf[1] = ((color >>  8) & 0xFF) / 255.0f * a;
            buf[2] = ( color        & 0xFF) / 255.0f * a;
            buf[3] = 1.0f;
            combined = buffer.getBuffer(CustomGlint.forArmorGlint(glint, buf, 0));
        }
        model.renderToBuffer(poseStack, combined, packedLight, OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f);
    }

}
