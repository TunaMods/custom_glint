package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.SlimeOuterLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.tunamods.customglint.common.client.EntityGlintRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Folds the slime's translucent OUTER shell into its glow outline. The inner body draws through
 * {@code LivingEntityRenderer.render} and is captured by the core body tee, but the outer shell is a
 * {@link SlimeOuterLayer} that renders its own (larger) model directly - it does NOT route through
 * {@code RenderLayer.renderColoredCutoutModel}, so {@code RenderLayerMixin}'s surface tee misses it and
 * only the inner cube gets a ring.
 *
 * At RETURN of the layer's {@code render} (the outer model's {@code setupAnim} state and the pose still
 * match the draw - the layer renders directly on the passed pose with no push/pop), re-render the outer
 * model into the glow mask under the wearer's shared {@code CAT_ENTITY} id, so the outer shell's silhouette
 * merges with the inner body into ONE ring following the larger outer boundary. Glow-only; the inner glint
 * already fans onto the shell through the wrapped buffer during the real draw.
 *
 * Dual SRG/named {@code @Inject}, {@code require=0} - same pattern as {@code ElytraLayerMixin} (identical
 * {@code RenderLayer.render} override + synthetic bridge shape). {@code SlimeOuterLayer} is only ever
 * instantiated for slimes, so the outer texture is the fixed vanilla slime texture (mirrors ElytraLayer's
 * hardcoded elytra texture).
 */
@Mixin(SlimeOuterLayer.class)
public class SlimeOuterLayerMixin {

    @Shadow(aliases = {"f_117455_"}) private EntityModel<?> model;

    /** Vanilla slime texture for the glow-outline trace - hoisted so it isn't reallocated per slime per frame. */
    private static final ResourceLocation CG_SLIME_TEX = new ResourceLocation("minecraft", "textures/entity/slime/slime.png");

    /** SRG target: injects at RETURN of render in obfuscated environments. */
    @Inject(method = "m_6494_", at = @At("RETURN"), require = 0)
    private void cg_slimeOuterGlow_srg(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, LivingEntity entity, float limbSwing, float limbSwingAmount,
            float partialTick, float ageInTicks, float netHeadYaw, float headPitch,
            CallbackInfo ci) {
        cg_captureOuterShell(poseStack, packedLight, entity);
    }

    /** Named target: injects at RETURN of render in dev/deobf environments. */
    @Inject(
        method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void cg_slimeOuterGlow_named(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, LivingEntity entity, float limbSwing, float limbSwingAmount,
            float partialTick, float ageInTicks, float netHeadYaw, float headPitch,
            CallbackInfo ci) {
        cg_captureOuterShell(poseStack, packedLight, entity);
    }

    private void cg_captureOuterShell(PoseStack poseStack, int packedLight, LivingEntity entity) {
        EntityGlintRender.OutlineSpec spec = EntityGlintRender.surfaceOutlineSpec(entity, CG_SLIME_TEX);
        if (spec == null) return; // not glowing / invisible
        EntityGlintRender.captureModelSilhouette(spec.identity, (Model) (Object) this.model, spec.tex,
                poseStack, packedLight, spec.color, spec.category);
    }
}
