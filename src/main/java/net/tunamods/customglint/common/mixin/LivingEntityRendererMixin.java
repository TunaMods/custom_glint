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
 * {@link ModifyVariable} at HEAD on the MultiBufferSource arg — wraps it with a
 * GlintWrappingBufferSource so every {@code entity_*} RenderType the renderer or any
 * RenderLayer (StrayClothingLayer, EyesLayer, VillagerProfessionLayer, …) requests gets
 * a glint pass automatically. No-op when the entity has no glint data.
 */
@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererMixin {

    // ── HEAD: wrap buffer source so every entity_* RT picks up the glint fan-out ──────

    @ModifyVariable(
        method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("HEAD"), argsOnly = true, require = 0, remap = false
    )
    private MultiBufferSource cg_wrapBuf_named(MultiBufferSource original,
                                               LivingEntity entity, float yaw, float partialTicks,
                                               PoseStack pose, MultiBufferSource buffer, int light) {
        return EntityGlintRender.wrapForEntity(entity, original);
    }
}
