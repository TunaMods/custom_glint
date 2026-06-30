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
 * <p>The capture is an IN-PHASE TEE: a {@link Redirect} on the surface model's {@code renderToBuffer} routes
 * the single real draw through a recording consumer when the entity glows, tracing each surface against its
 * own texture under the entity's shared CAT_ENTITY id (so wool/saddle/clothing merge into the figure's one
 * ring). No second model render; the real surface still draws unchanged, and a non-glowing entity pays one
 * extra method call.
 *
 * <p>Dual SRG/named @Redirect, both {@code remap=false} so exactly one resolves per environment — two
 * redirects on the same instruction would conflict if both resolved.
 */
@Mixin(RenderLayer.class)
public class RenderLayerMixin {

    @Redirect(
        method = "m_117376_",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/model/EntityModel;m_7695_(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V"),
        require = 0, remap = false
    )
    private static void cg_teeSurfaceOutline_srg(EntityModel drawModel, PoseStack pose, VertexConsumer vc,
            int light, int overlay, float r, float g, float b, float a,
            EntityModel model, ResourceLocation texture, PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, LivingEntity entity, float red, float green, float blue) {
        EntityGlintRender.OutlineSpec spec = EntityGlintRender.surfaceOutlineSpec(entity, texture);
        EntityGlintRender.teeOutline((Model) drawModel, pose, vc, light, overlay, r, g, b, a, spec);
    }

    @Redirect(
        method = "renderColoredCutoutModel(Lnet/minecraft/client/model/EntityModel;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFF)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V"),
        require = 0, remap = false
    )
    private static void cg_teeSurfaceOutline_named(EntityModel drawModel, PoseStack pose, VertexConsumer vc,
            int light, int overlay, float r, float g, float b, float a,
            EntityModel model, ResourceLocation texture, PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, LivingEntity entity, float red, float green, float blue) {
        EntityGlintRender.OutlineSpec spec = EntityGlintRender.surfaceOutlineSpec(entity, texture);
        EntityGlintRender.teeOutline((Model) drawModel, pose, vc, light, overlay, r, g, b, a, spec);
    }
}
