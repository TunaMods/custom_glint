package net.tunamods.customglint.module.compat.iceandfire.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import net.tunamods.customglint.module.compat.iceandfire.MountArmorCache;
import net.tunamods.customglint.module.compat.iceandfire.client.IceAndFireArmorGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Standalone-only compat: hippocampus armor (vanilla HorseArmorItem variants) is rendered by
 * IaF's LayerHippocampusSaddle, not vanilla HorseArmorLayer, so HorseArmorLayerMixin never
 * fires for it. Same shape as {@link LayerHippogryphArmorMixin}: pick one of three solid textures
 * by entity.getArmor() (1/2/3 = iron/gold/diamond), source the actual ItemStack from the
 * client-synced cache.
 *
 * Glint + outline mechanics identical to the dragon/hippogryph variants (depth-offset
 * armorCutoutNoCull, no stencil mask). See {@link LayerDragonArmorMixin} for the rationale.
 *
 * Armor ItemStack source: {@link MountArmorCache} (synced by EntityHippocampusArmorSyncMixin +
 * StartTracking listener; IaF's SimpleContainer doesn't sync to clients on its own).
 */
@Pseudo
@Mixin(targets = "com.github.alexthe666.iceandfire.client.render.entity.RenderHippocampus$LayerHippocampusSaddle", remap = false)
public class LayerHippocampusArmorMixin {

    private static final ResourceLocation CG_TEX_IRON =
            new ResourceLocation("iceandfire", "textures/models/hippocampus/armor_iron.png");
    private static final ResourceLocation CG_TEX_GOLD =
            new ResourceLocation("iceandfire", "textures/models/hippocampus/armor_gold.png");
    private static final ResourceLocation CG_TEX_DIAMOND =
            new ResourceLocation("iceandfire", "textures/models/hippocampus/armor_diamond.png");

    /** Same accessory-hull problem as the hippogryph: this layer redraws the parent model once per accessory
     *  with a mostly-transparent texture, and the wrapper fanned the body glint onto each pass, filling the
     *  straps in as solid shapes. See {@link LayerHippogryphArmorMixin#cg_unwrapAccessories} for the why. */
    @ModifyVariable(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/github/alexthe666/iceandfire/entity/EntityHippocampus;FFFFFF)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private MultiBufferSource cg_unwrapAccessories(MultiBufferSource buffer) {
        return EntityGlintRender.unwrap(buffer);
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/github/alexthe666/iceandfire/entity/EntityHippocampus;FFFFFF)V",
            at = @At("RETURN"), require = 0)
    private void cg_apply(PoseStack pose, MultiBufferSource buffer, int light,
            @Coerce LivingEntity entity, float a, float b, float c, float d, float e, float f,
            CallbackInfo ci) {
        int armor = IceAndFireArmorGlint.armorTier(entity);
        if (armor == 0) return;

        ResourceLocation tex;
        switch (armor) {
            case 1: tex = CG_TEX_IRON; break;
            case 2: tex = CG_TEX_GOLD; break;
            case 3: tex = CG_TEX_DIAMOND; break;
            default: return;
        }

        ItemStack stack = MountArmorCache.get(entity.getId());
        CustomGlint.Data glint = CustomGlint.readCached(stack);
        boolean glowing = CustomGlint.isGlowing(stack);
        if (glint == null && !glowing) return;

        EntityModel<?> model = IceAndFireArmorGlint.parentModel(this);
        if (model == null) return;

        // Draw the base armor through the UNWRAPPED buffer with armorCutoutNoCull, then glint via
        // forArmorGlint, the same fix LayerDragonArmorMixin uses. Hippocampus armor reuses the body
        // model at the SAME depth, so EntityGlintRender's wrapper fanned the mount's body glint onto
        // the armor (entity glint over armor) and the EQUAL-depth body glint drew over it, leaving the
        // bare silhouette showing through. armorCutoutNoCull's polygon offset nudges the armor in front
        // of the body so the body glint is depth-occluded there; forArmorGlint (EQUAL + the matching
        // offset, masked by the armor texture's own alpha cutout) lands only on the armor's opaque
        // texels. Routed through the unwrapped buffer so the wrapper can't re-fan the body glint.
        MultiBufferSource flush = EntityGlintRender.unwrap(buffer);
        if (glint != null) {
            model.renderToBuffer(pose, flush.getBuffer(RenderType.armorCutoutNoCull(tex)),
                    light, OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f);
            IceAndFireArmorGlint.drawArmorGlint(glint, model, pose, flush, light);
        }

        // Glow outline: trace the parent model against the armor texture into the glow mask. Keyed on
        // the mount entity (CAT_ARMOR), so the armor ring fuses with the mount's body/entity ring into
        // ONE connected ring (matches HumanoidArmorLayerMixin's wearer-keyed armor outline).
        if (glowing) {
            EntityGlintRender.captureModelSilhouette(entity, model, tex, pose, light,
                    CustomGlintRenderer.resolveGlowColor(stack), GlowOutlineRenderer.CAT_ARMOR);
        }
    }
}
