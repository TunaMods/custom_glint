package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.HorseModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.HorseArmorLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.item.AnimalArmorItem;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/** Intercepts HorseArmorLayer.render: a {@link Redirect} tees the vanilla base-barding draw to capture the
 *  glow silhouette in-phase (no re-render), and the RETURN {@link Inject} draws the custom glint
 *  (stencil-masked to the layered armor shape). */
@Mixin(HorseArmorLayer.class)
public class HorseArmorLayerMixin {

    @Shadow private HorseModel<Horse> model;

    // ── in-phase glow tee on the vanilla base-barding draw ────────────────────

    @Redirect(
        method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/animal/horse/Horse;FFFFFF)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/model/HorseModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"),
        require = 0, remap = false
    )
    private void cg_teeHorseArmorOutline_named(HorseModel drawModel, PoseStack pose, VertexConsumer vc,
            int light, int overlay, int color, PoseStack pose2, MultiBufferSource buffer, int packedLight,
            Horse entity, float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
            float netHeadYaw, float headPitch) {
        EntityGlintRender.OutlineSpec spec = null;
        ItemStack stack = entity.getBodyArmorItem();
        if (!entity.isInvisible() && !stack.isEmpty() && CustomGlint.hasGlowEffect(stack)
                && stack.getItem() instanceof AnimalArmorItem aa) {
            spec = new EntityGlintRender.OutlineSpec(entity, aa.getTexture(),
                    CustomGlintRenderer.resolveGlowColor(stack), GlowOutlineRenderer.CAT_ARMOR, 0);
        }
        EntityGlintRender.teeOutline5(drawModel, pose, vc, light, overlay, color, spec);
    }

    /** Injects at RETURN of render. */
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
        ItemStack stack = entity.getBodyArmorItem();
        if (stack.isEmpty()) return;
        CustomGlint.Data glint = CustomGlint.read(stack);
        if (glint == null) return; // glow outline is captured by the in-phase tee redirect above
        if (!(stack.getItem() instanceof AnimalArmorItem aa)
                || aa.getBodyType() != AnimalArmorItem.BodyType.EQUESTRIAN) return;
        ResourceLocation tex = aa.getTexture();

        // When the horse itself carries per-entity glint, LivingEntityRendererMixin wraps the buffer
        // in EntityGlintRender's GlintWrappingBufferSource, which is NOT a MultiBufferSource.BufferSource.
        // Flush through the unwrapped real source so the endBatch calls below actually fire (the IaF mount
        // mixins do the same). Otherwise every flush is skipped, the glint tests EQUAL against unflushed
        // barding depth, and all glint is discarded ("glow shows, glint doesn't") on a glinted horse.
        MultiBufferSource flush = EntityGlintRender.unwrap(buffer);
        // Flush vanilla's base barding now so its depth is in the buffer before the glint's
        // EQUAL_DEPTH_TEST pass. HorseArmorLayer.render() only *buffers* the base armor into
        // entityCutoutNoCull(tex); it won't draw until the entity's global endBatch, which is
        // AFTER our explicit glint endBatch below. Without this flush the glint tests EQUAL
        // against absent armor depth and discards every fragment: the "glow shows, glint
        // doesn't" symptom. Humanoid armor sidesteps this by never endBatching early (its
        // glint flushes after the base at global endBatch). Glow is independent (own stencil).
        if (flush instanceof MultiBufferSource.BufferSource bsBase)
            bsBase.endBatch(RenderType.entityCutoutNoCull(tex));

        // ── Stencil mask pass ───────────────────────────────────────────
        // forHorseArmorGlint draws on every face of the armor model regardless of armor
        // texture alpha (the glint shader samples a glint design, not the armor texture).
        // For vanilla iron/gold/diamond barding this is fine. The texture is full-coverage
        // opaque so visible glint == armor coverage. But Epic Knights barding (chainmail,
        // mail variants, etc.) has transparent gaps where the horse body shows through, and
        // the unmasked glint bleeds across those gaps onto the body silhouette. Stencil-mask
        // bit 0x80 via the armor texture's alpha cutoff, then constrain the glint test to
        // that bit. Same pattern as IaF mount armor.
        RenderType maskType = CustomGlintRenderer.forMountArmorStencilMask(tex);
        model.renderToBuffer(poseStack, buffer.getBuffer(maskType), packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        if (flush instanceof MultiBufferSource.BufferSource bs0) bs0.endBatch(maskType);

        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();

        List<VertexConsumer> list = new ArrayList<>();
        List<RenderType> glintTypes = new ArrayList<>();
        for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
            int[] colors = layers[layerIdx].colors().length == 0 ? CustomGlintRenderer.WHITE_COLOR : layers[layerIdx].colors();
            if (layers[layerIdx].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    float a = ((colors[i] >> 24) & 0xFF) / 255.0f;
                    buf[0] = ((colors[i] >> 16) & 0xFF) / 255.0f * a;
                    buf[1] = ((colors[i] >>  8) & 0xFF) / 255.0f * a;
                    buf[2] = ( colors[i]        & 0xFF) / 255.0f * a;
                    buf[3] = 1.0f;
                    RenderType rt = CustomGlintRenderer.forMountArmorGlint(glint, layerIdx, buf, i);
                    if (rt != null) { list.add(buffer.getBuffer(rt)); glintTypes.add(rt); }
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                float a = ((color >> 24) & 0xFF) / 255.0f;
                buf[0] = ((color >> 16) & 0xFF) / 255.0f * a;
                buf[1] = ((color >>  8) & 0xFF) / 255.0f * a;
                buf[2] = ( color        & 0xFF) / 255.0f * a;
                buf[3] = 1.0f;
                RenderType rt = CustomGlintRenderer.forMountArmorGlint(glint, layerIdx, buf, 0);
                if (rt != null) { list.add(buffer.getBuffer(rt)); glintTypes.add(rt); }
            }
        }
        if (!list.isEmpty()) {
            VertexConsumer combined = list.size() == 1 ? list.get(0)
                    : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
            model.renderToBuffer(poseStack, combined, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        }
        if (flush instanceof MultiBufferSource.BufferSource bs2) {
            for (RenderType rt : glintTypes) bs2.endBatch(rt);
        }
        // Glow outline is captured by the in-phase tee redirect above (no re-render here).
    }

}
