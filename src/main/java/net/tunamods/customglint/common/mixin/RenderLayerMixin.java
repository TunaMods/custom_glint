package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.Identifier;
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
 * Targets the static RenderLayer helpers by their Mojang names — NeoForge runs official
 * mappings, so a single named descriptor binds in both dev and production.
 */
@Mixin(RenderLayer.class)
public class RenderLayerMixin {

    @Inject(
        method = "coloredCutoutModelCopyLayerRender(Lnet/minecraft/client/model/EntityModel;Lnet/minecraft/client/model/EntityModel;Lnet/minecraft/resources/Identifier;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFFI)V",
        at = @At("RETURN"), require = 0
    )
    private static void cg_copyLayerOutline(EntityModel<?> baseModel, EntityModel<?> outerModel,
                                            Identifier texture, PoseStack pose,
                                            MultiBufferSource buffer, int packedLight,
                                            LivingEntity entity,
                                            float limbSwing, float limbSwingAmount, float ageInTicks,
                                            float netHeadYaw, float headPitch, float partialTick,
                                            int color,
                                            CallbackInfo ci) {
        EntityGlintRender.queueLayerOutline(entity, outerModel, texture, pose, packedLight);
    }

    @Inject(
        method = "renderColoredCutoutModel(Lnet/minecraft/client/model/EntityModel;Lnet/minecraft/resources/Identifier;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;I)V",
        at = @At("RETURN"), require = 0
    )
    private static void cg_singleLayerOutline(EntityModel<?> model, Identifier texture,
                                              PoseStack pose, MultiBufferSource buffer,
                                              int packedLight, LivingEntity entity,
                                              int color,
                                              CallbackInfo ci) {
        EntityGlintRender.queueLayerOutline(entity, model, texture, pose, packedLight);
    }
}
