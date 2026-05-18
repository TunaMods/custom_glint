package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.HorseModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.HorseArmorLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.item.HorseArmorItem;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/** Intercepts HorseArmorLayer.render at RETURN to draw custom glint and (if glowing) stencil outline on horse armor. Dual SRG/named targets, require=0 on both. */
@Mixin(HorseArmorLayer.class)
public class HorseArmorLayerMixin {

    @Shadow(aliases = {"f_117017_"}) private HorseModel<Horse> model;

    /** SRG target: injects at RETURN of render in obfuscated environments. */
    @Inject(method = "m_6494_", at = @At("RETURN"), require = 0)
    private void cg_horseArmorGlint_srg(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, Horse entity, float limbSwing, float limbSwingAmount,
            float partialTick, float ageInTicks, float netHeadYaw, float headPitch,
            CallbackInfo ci) {
        applyHorseArmorGlint(poseStack, buffer, packedLight, entity, this.model);
    }

    /** Named target: injects at RETURN of render in dev/deobf environments. */
    @Inject(
        method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/animal/horse/Horse;FFFFFF)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void cg_horseArmorGlint_named(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, Horse entity, float limbSwing, float limbSwingAmount,
            float partialTick, float ageInTicks, float netHeadYaw, float headPitch,
            CallbackInfo ci) {
        applyHorseArmorGlint(poseStack, buffer, packedLight, entity, this.model);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static void applyHorseArmorGlint(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, Horse entity, HorseModel<Horse> model) {
        ItemStack stack = entity.getArmor();
        if (stack.isEmpty()) return;
        CustomGlint.Data glint = CustomGlint.read(stack);
        boolean glowing = CustomGlint.isGlowing(stack);
        if (glint == null && !glowing) return;

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
                        RenderType rt = CustomGlintRenderer.forHorseArmorGlint(glint, layerIdx, buf, i);
                        if (rt != null) list.add(buffer.getBuffer(rt));
                    }
                } else {
                    int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                    float a = ((color >> 24) & 0xFF) / 255.0f;
                    buf[0] = ((color >> 16) & 0xFF) / 255.0f * a;
                    buf[1] = ((color >>  8) & 0xFF) / 255.0f * a;
                    buf[2] = ( color        & 0xFF) / 255.0f * a;
                    buf[3] = 1.0f;
                    RenderType rt = CustomGlintRenderer.forHorseArmorGlint(glint, layerIdx, buf, 0);
                    if (rt != null) list.add(buffer.getBuffer(rt));
                }
            }
            if (!list.isEmpty()) {
                VertexConsumer combined = list.size() == 1 ? list.get(0)
                        : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
                model.renderToBuffer(poseStack, combined, packedLight, OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f);
            }
        }
        if (glowing && stack.getItem() instanceof HorseArmorItem ha)
            CustomGlintRenderer.doModelOutline(poseStack, buffer, packedLight, model, ha.getTexture(), stack, null);
    }

}
