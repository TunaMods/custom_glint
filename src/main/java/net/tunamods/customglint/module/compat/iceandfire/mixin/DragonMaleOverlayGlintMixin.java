package net.tunamods.customglint.module.compat.iceandfire.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Standalone-only compat for Ice &amp; Fire Community Edition's male-dragon overlay.
 *
 * <p>{@code DragonMaleOverlayFeatureRenderer} redraws the ENTIRE dragon model a second time through
 * {@code RenderType.entityTranslucent(maleOverlay)} for male, non-skeletal dragons. The glint wrapper installed
 * on {@code LivingEntityRenderer.render} fans the entity glint onto every {@code entity_*} RT, so it stamps the
 * glint onto this translucent redraw too. Under a shader pack the translucent glint has no cutout (the in-phase
 * program is hijacked), so it paints the model's full quads as stacked planes over the wings. The opaque base
 * body already carries the carved glint (via the OPAQUE_DECAL cutout variant), so drop the fan on the overlay
 * redraw. Off-pack the overlay's EQUAL-depth glint carves against its own draw, so leave that path alone.
 */
@Pseudo
@Mixin(targets = "com.iafenvoy.iceandfire.render.entity.feature.DragonMaleOverlayFeatureRenderer", remap = false)
public class DragonMaleOverlayGlintMixin {

    @Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/iafenvoy/iceandfire/entity/DragonBaseEntity;FFFFFF)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"),
            require = 0)
    private VertexConsumer cg_unwrapMaleOverlay(MultiBufferSource src, RenderType rt) {
        if (CustomGlintRenderer.isShaderPackActive()) src = EntityGlintRender.unwrap(src);
        return src.getBuffer(rt);
    }
}
