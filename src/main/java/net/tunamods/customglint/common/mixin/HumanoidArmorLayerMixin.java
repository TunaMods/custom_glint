package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.renderer.RenderType;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.EntityModel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.ForgeHooksClient;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Intercepts renderArmorPiece at RETURN to draw custom glint and (if glowing) stencil outline on vanilla + modded armor. Dual SRG/named targets, require=0 on both. */
@Mixin(HumanoidArmorLayer.class)
public class HumanoidArmorLayerMixin {

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
        if (stack.isEmpty() || !(stack.getItem() instanceof ArmorItem)) return;
        CustomGlint.Data glint = CustomGlint.read(stack);
        boolean glowing = CustomGlint.isGlowing(stack);
        // Bail only if there is nothing to render — no glint AND no glow.
        if (glint == null && !glowing) return;

        Model rendererModel = ForgeHooksClient.getArmorModel(entity, stack, slot, model);
        if (glint != null) {
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
                VertexConsumer combined = list.size() == 1 ? list.get(0) : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
                rendererModel.renderToBuffer(poseStack, combined, packedLight, OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f);
            }
        }
        if (glowing) {
            ResourceLocation armorTex = CustomGlint.SOLID;
            if (stack.getItem() instanceof ArmorItem ai) {
                String defaultPath = String.format("textures/models/armor/%s_layer_%d.png",
                        ai.getMaterial().getName(), slot == EquipmentSlot.LEGS ? 2 : 1);
                String resolved = ForgeHooksClient.getArmorTexture(entity, stack, defaultPath, slot, null);
                try { armorTex = new ResourceLocation(resolved); }
                catch (Exception ignored) { armorTex = CustomGlint.SOLID; }
            }
            EntityModel<?> outlineModel = rendererModel instanceof EntityModel<?> em ? em : model;
            CustomGlintRenderer.doModelOutline(poseStack, buffer, packedLight, outlineModel, armorTex, stack, slot);
        }
    }

}
