package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * In-phase entity-body glow-outline capture — the vanilla-parity path (option A).
 *
 * <p>Vanilla's own glowing-entity outline tees a second {@code renderToBuffer} into a shared
 * {@code OutlineBufferSource} right after the body draws, while the model is still posed and
 * {@code setupAnim}'d (see {@code ModelFeatureRenderer.renderModel}, the {@code submit.outlineColor()}
 * block). That is why vanilla glow is nearly free for hundreds of entities: no second {@code setupAnim},
 * no per-entity {@code PoseStack}, no deferred re-walk — just one extra batched vertex emit.
 *
 * <p>We go one better than vanilla: rather than a SECOND {@code renderToBuffer} per glowing entity
 * (vanilla's cost, and what an earlier version of this mixin did), we redirect the body's single
 * {@code renderToBuffer} to fan its vertices into BOTH the normal buffer and our glow-mask buffer via a
 * {@link com.mojang.blaze3d.vertex.VertexMultiConsumer}. The silhouette is captured during the one model
 * walk the entity already does — a few extra vertex writes, no second traversal, no second setupAnim.
 * This is what makes glow scale like vanilla (the bottleneck was the per-entity second model walk).
 * {@code drainBodyOutlines} flushes the mask buffer and runs the single occluded composite.
 *
 * <p>Gated on {@code submit.model() == go.model()} so only the BODY model fans — overlay layers share
 * the render state but submit their own models/textures and must not be traced with the body texture.
 */
@Mixin(ModelFeatureRenderer.class)
public class ModelFeatureRendererMixin {

    @Redirect(method = "renderModel",
            at = @At(value = "INVOKE", ordinal = 0,
                    target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"),
            require = 0)
    private <S> void cg_fanBodyGlow(Model instance, PoseStack pose, VertexConsumer buffer,
            int light, int overlay, int color,
            SubmitNodeStorage.ModelSubmit<S> submit, RenderType renderType, VertexConsumer origBuffer,
            OutlineBufferSource outlineBufferSource, MultiBufferSource.BufferSource crumblingBufferSource) {
        VertexConsumer target = buffer;
        if (submit.state() instanceof EntityRenderState state) {
            EntityGlintRender.GlowOutline go = state.getRenderData(EntityGlintRender.GLOW_OUTLINE);
            if (go != null && instance == go.model()) {
                // Fan this single body walk into the glow mask too — no extra traversal.
                target = CustomGlintRenderer.fanBodyGlow(buffer, submit.pose(), go.color(), go.texture(),
                        state.boundingBoxWidth, state.boundingBoxHeight, go.seeThrough(), state);
            }
        }
        instance.renderToBuffer(pose, target, light, overlay, color);
    }
}
