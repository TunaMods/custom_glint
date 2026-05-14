// MIT License — Copyright (c) 2026 Likely Tuna | TunaMods — see LICENSE.txt
package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Intercepts ElytraLayer.render at RETURN to draw custom glint on top of the elytra model.
 * Elytra uses armorCutoutNoCull (VIEW_OFFSET_Z_LAYERING), so forArmorGlint works correctly.
 * The vanilla render translates the PoseStack by (0, 0, 0.125) then pops before returning,
 * so the inject re-applies that translation around the glint renderToBuffer call.
 */
@Mixin(ElytraLayer.class)
public class ElytraLayerMixin {

    @Shadow(aliases = {"f_116935_"}) private ElytraModel<?> elytraModel;

    /** SRG target: injects at RETURN of render in obfuscated environments. */
    @Inject(method = "m_6494_", at = @At("RETURN"), require = 0)
    private void cg_elytraGlint_srg(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, LivingEntity entity, float limbSwing, float limbSwingAmount,
            float partialTick, float ageInTicks, float netHeadYaw, float headPitch,
            CallbackInfo ci) {
        applyElytraGlint(poseStack, buffer, packedLight, entity, this.elytraModel);
    }

    /** Named target: injects at RETURN of render in dev/deobf environments. */
    @Inject(
        method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void cg_elytraGlint_named(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, LivingEntity entity, float limbSwing, float limbSwingAmount,
            float partialTick, float ageInTicks, float netHeadYaw, float headPitch,
            CallbackInfo ci) {
        applyElytraGlint(poseStack, buffer, packedLight, entity, this.elytraModel);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static void applyElytraGlint(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, LivingEntity entity, ElytraModel<?> elytraModel) {
        ItemStack stack = entity.getItemBySlot(EquipmentSlot.CHEST);
        if (stack.isEmpty()) return;
        if (!stack.is(Items.ELYTRA)) return;
        CustomGlint.Data glint = CustomGlint.read(stack);
        if (glint == null) return;

        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlint.COLOR_BUF.get();

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
                    RenderType rt = CustomGlint.forArmorGlint(glint, layerIdx, buf, i);
                    if (rt != null) list.add(buffer.getBuffer(rt));
                }
            } else {
                int color = CustomGlint.computeAnimatedColor(glint, layerIdx);
                float a = ((color >> 24) & 0xFF) / 255.0f;
                buf[0] = ((color >> 16) & 0xFF) / 255.0f * a;
                buf[1] = ((color >>  8) & 0xFF) / 255.0f * a;
                buf[2] = ( color        & 0xFF) / 255.0f * a;
                buf[3] = 1.0f;
                RenderType rt = CustomGlint.forArmorGlint(glint, layerIdx, buf, 0);
                if (rt != null) list.add(buffer.getBuffer(rt));
            }
        }
        if (list.isEmpty()) return;
        VertexConsumer combined = list.size() == 1 ? list.get(0)
                : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
        // Vanilla's render pops the pose before returning, so re-apply the elytra's (0, 0, 0.125) offset.
        poseStack.pushPose();
        poseStack.translate(0.0f, 0.0f, 0.125f);
        elytraModel.renderToBuffer(poseStack, combined, packedLight, OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f);
        if (CustomGlint.isGlowing(stack))
            CustomGlint.doModelOutline(poseStack, buffer, packedLight, elytraModel, new ResourceLocation("minecraft", "textures/entity/elytra.png"), glint, null);
        poseStack.popPose();
    }

}
