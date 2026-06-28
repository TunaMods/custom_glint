package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.tunamods.customglint.common.client.EntityGlintRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Folds entity-surface render layers into the body glow outline. {@code renderColoredCutoutModel} is the
 * single chokepoint every cutout-overlay layer draws through — directly (SheepFurLayer wool, SaddleLayer,
 * MushroomCowMushroomLayer, …) and via {@code coloredCutoutModelCopyLayerRender} (StrayClothingLayer,
 * DrownedOuterLayer, VillagerProfessionLayer, …).
 *
 * <p>The capture is an IN-PHASE TEE: a {@link Redirect} on the surface model's {@code renderToBuffer}
 * routes the single real draw through a recording consumer when the entity glows, tracing each surface
 * against its own texture under the entity's shared CAT_ENTITY id (so wool/saddle/clothing merge into the
 * figure's one ring — the 1.21.1 equivalent of 26.1.2's {@code fanLayerGlow}). No second model render. The
 * real surface still draws unchanged; a non-glowing entity pays one extra method call.
 */
@Mixin(RenderLayer.class)
public class RenderLayerMixin {

    @Redirect(
        method = "renderColoredCutoutModel(Lnet/minecraft/client/model/EntityModel;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;I)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"),
        require = 0, remap = false
    )
    private static void cg_teeSurfaceOutline_named(EntityModel drawModel, PoseStack pose, VertexConsumer vc,
            int light, int overlay, int color,
            EntityModel<?> model, ResourceLocation texture, PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, LivingEntity entity, int tintColor) {
        EntityGlintRender.OutlineSpec spec = EntityGlintRender.surfaceOutlineSpec(entity, texture);
        EntityGlintRender.teeOutline5((Model) drawModel, pose, vc, light, overlay, color, spec);
    }
}
