package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.layers.SlimeOuterLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.tunamods.customglint.common.client.EntityGlintRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Folds the slime's translucent OUTER shell into the glow outline. {@code SlimeOuterLayer} renders the outer
 * model with a direct {@code model.renderToBuffer(...)} — NOT through {@code renderColoredCutoutModel} — so
 * {@code RenderLayerMixin}'s surface tee never sees it. The body tee (LivingEntityRendererMixin) does ring the
 * INNER slime model, but that sits inside the larger translucent shell, so the ring hides in the jelly and the
 * slime reads as un-outlined. Tee the OUTER draw too (shared CAT_ENTITY id → merges with the body into one
 * ring), so the outline traces the visible outer edge.
 */
@Mixin(SlimeOuterLayer.class)
public class SlimeOuterLayerMixin {

    @Redirect(
        method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V"),
        require = 0, remap = false
    )
    private void cg_teeSlimeOuter(EntityModel<?> drawModel, PoseStack pose, VertexConsumer vc, int light, int overlay,
            PoseStack poseStack, MultiBufferSource buffer, int packedLight, LivingEntity entity,
            float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw,
            float headPitch) {
        EntityGlintRender.OutlineSpec spec = EntityGlintRender.surfaceOutlineSpec(entity, cg_textureFor(entity));
        EntityGlintRender.teeOutline4((Model) drawModel, pose, vc, light, overlay, spec);
    }

    /** The slime's body texture (same UVs as the outer shell), via the entity renderer — avoids shadowing
     *  {@code RenderLayer.getTextureLocation} (a superclass method, which @Shadow can't resolve here). Null on
     *  any failure → {@code surfaceOutlineSpec} returns null → the outer just draws, no ring. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResourceLocation cg_textureFor(LivingEntity entity) {
        try {
            EntityRenderer r = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
            return r == null ? null : r.getTextureLocation(entity);
        } catch (Throwable t) {
            return null;
        }
    }
}
