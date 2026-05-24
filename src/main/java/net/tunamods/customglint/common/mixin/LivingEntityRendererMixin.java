package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.tunamods.customglint.common.client.EntityGlintRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two hooks on LivingEntityRenderer.render:
 *
 *  1. {@link ModifyVariable} at HEAD on the MultiBufferSource arg — wraps it with a
 *     GlintWrappingBufferSource so every {@code entity_*} RenderType the renderer or any
 *     RenderLayer (StrayClothingLayer, EyesLayer, VillagerProfessionLayer, …) requests gets
 *     a glint pass automatically. No-op when the entity has no glint data.
 *
 *  2. {@link Inject} before the outer popPose — calls EntityGlintRender.renderOutline with
 *     the pose stack still in entity-local space (Y-flipped from upstream scale(-1,-1,1)).
 *     RenderLivingEvent.Post would be too late: that fires after popPose, when pose is back
 *     in world/camera space → model would draw at world origin, mirrored/upside-down.
 *
 * Dual SRG/named, require=0 on every hook — same pattern as the armor-layer mixins.
 */
@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererMixin {

    // ── HEAD: wrap buffer source so every entity_* RT picks up the glint fan-out ──────

    @ModifyVariable(method = "m_7392_", at = @At("HEAD"), argsOnly = true, require = 0)
    private MultiBufferSource cg_wrapBuf_srg(MultiBufferSource original,
                                             LivingEntity entity, float yaw, float partialTicks,
                                             PoseStack pose, MultiBufferSource buffer, int light) {
        return EntityGlintRender.wrapForEntity(entity, original);
    }

    @ModifyVariable(
        method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("HEAD"), argsOnly = true, require = 0, remap = false
    )
    private MultiBufferSource cg_wrapBuf_named(MultiBufferSource original,
                                               LivingEntity entity, float yaw, float partialTicks,
                                               PoseStack pose, MultiBufferSource buffer, int light) {
        return EntityGlintRender.wrapForEntity(entity, original);
    }

    // ── popPose-before: draw outline in entity-local pose space ─────────────────────────

    @Inject(
        method = "m_7392_",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V"),
        require = 0
    )
    private void cg_outline_srg(LivingEntity entity, float yaw, float partialTicks,
                                PoseStack pose, MultiBufferSource buffer, int light, CallbackInfo ci) {
        EntityGlintRender.renderOutline((LivingEntityRenderer<?, ?>)(Object)this, entity, pose, buffer, light);
    }

    @Inject(
        method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V"),
        require = 0, remap = false
    )
    private void cg_outline_named(LivingEntity entity, float yaw, float partialTicks,
                                  PoseStack pose, MultiBufferSource buffer, int light, CallbackInfo ci) {
        EntityGlintRender.renderOutline((LivingEntityRenderer<?, ?>)(Object)this, entity, pose, buffer, light);
    }
}
