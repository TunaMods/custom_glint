package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.tunamods.customglint.common.client.EntityGlintRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds per-layer outline passes to the two shared static helpers in {@link RenderLayer}:
 *
 *  • {@code coloredCutoutModelCopyLayerRender} — used by StrayClothingLayer, DrownedOuterLayer,
 *    VillagerProfessionLayer (overlays that re-render a parented model with a different texture).
 *  • {@code renderColoredCutoutModel} — used by SaddleLayer, MushroomCowMushroomLayer, etc.
 *
 * The base body outline (from LivingEntityRendererMixin's popPose hook) already handles the
 * underlying entity silhouette. This mixin fires AFTER each overlay layer renders, so each
 * overlay (clothing, saddle, mushroom, etc.) gets its own outline pass with the layer's own
 * model + texture. Each call reserves a unique stencil slot inside doModelOutline so the base
 * and overlays don't cross-contaminate.
 *
 * Uses named descriptors with the default {@code remap=true} so MixinGradle's refmap remaps
 * to SRG at compile time for production while dev resolves directly. No dual SRG pair here —
 * the SRG IDs for these two static helpers collided with unrelated non-static methods on a
 * previous attempt and produced a {@code 'static' modifier of handler method does not match
 * target} error during mixin apply.
 */
@Mixin(RenderLayer.class)
public class RenderLayerMixin {

    @Inject(
        method = "coloredCutoutModelCopyLayerRender(Lnet/minecraft/client/model/EntityModel;Lnet/minecraft/client/model/EntityModel;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFFFFF)V",
        at = @At("RETURN"), require = 0
    )
    private static void cg_copyLayerOutline(EntityModel<?> baseModel, EntityModel<?> outerModel,
                                            ResourceLocation texture, PoseStack pose,
                                            MultiBufferSource buffer, int packedLight,
                                            LivingEntity entity,
                                            float p7, float p8, float p9, float p10,
                                            float p11, float p12,
                                            float r, float g, float b,
                                            CallbackInfo ci) {
        EntityGlintRender.queueLayerOutline(entity,outerModel, texture, pose, packedLight);
    }

    @Inject(
        method = "renderColoredCutoutModel(Lnet/minecraft/client/model/EntityModel;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFF)V",
        at = @At("RETURN"), require = 0
    )
    private static void cg_singleLayerOutline(EntityModel<?> model, ResourceLocation texture,
                                              PoseStack pose, MultiBufferSource buffer,
                                              int packedLight, LivingEntity entity,
                                              float r, float g, float b,
                                              CallbackInfo ci) {
        EntityGlintRender.queueLayerOutline(entity,model, texture, pose, packedLight);
    }
}
