package net.tunamods.customglint.module.compat.iceandfire.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone-only compat: hippocampus armor (vanilla HorseArmorItem variants) is rendered by
 * IaF's LayerHippocampusSaddle, not vanilla HorseArmorLayer - so HorseArmorLayerMixin never
 * fires for it. Same shape as {@link LayerHippogryphArmorMixin}: pick one of three solid textures
 * by entity.getArmor() (1/2/3 = iron/gold/diamond), source the actual ItemStack from the
 * client-synced cache.
 *
 * Glint + outline mechanics identical to the dragon/hippogryph variants (depth-offset
 * armorCutoutNoCull, no stencil mask). See {@link LayerDragonArmorMixin} for the rationale.
 *
 * Armor ItemStack source: {@link MountArmorCache} (synced by EntityHippocampusArmorSyncMixin +
 * StartTracking listener - IaF's SimpleContainer doesn't sync to clients on its own).
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

    private static volatile Method CG_GET_PARENT_MODEL;
    private static volatile Method CG_GET_ARMOR;

    private EntityModel<?> cg_getParentModel() {
        try {
            Method m = CG_GET_PARENT_MODEL;
            if (m == null) {
                Class<?> cls = Class.forName("net.minecraft.client.renderer.entity.layers.RenderLayer");
                for (Method mm : cls.getDeclaredMethods()) {
                    if (mm.getParameterCount() == 0
                            && mm.getReturnType().getName().equals("net.minecraft.client.model.EntityModel")) {
                        mm.setAccessible(true);
                        m = mm;
                        break;
                    }
                }
                CG_GET_PARENT_MODEL = m;
            }
            return m == null ? null : (EntityModel<?>) m.invoke(this);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int cg_getArmor(Object entity) {
        try {
            Method m = CG_GET_ARMOR;
            if (m == null) {
                m = entity.getClass().getMethod("getArmor");
                CG_GET_ARMOR = m;
            }
            return (int) m.invoke(entity);
        } catch (Throwable t) {
            return 0;
        }
    }

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
        int armor = cg_getArmor(entity);
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

        EntityModel<?> model = cg_getParentModel();
        if (model == null) return;

        // Draw the base armor through the UNWRAPPED buffer with armorCutoutNoCull, then glint via
        // forArmorGlint - the same fix LayerDragonArmorMixin uses. Hippocampus armor reuses the body
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

            CustomGlint.Layer[] layers = glint.layers();
            float[] buf = CustomGlintRenderer.COLOR_BUF.get();
            List<VertexConsumer> list = new ArrayList<>();
            for (int li = 0; li < layers.length; li++) {
                int[] colors = layers[li].colors();
                if (layers[li].simultaneous()) {
                    for (int i = 0; i < colors.length; i++) {
                        float aa = ((colors[i] >> 24) & 0xFF) / 255.0f;
                        buf[0] = ((colors[i] >> 16) & 0xFF) / 255.0f * aa;
                        buf[1] = ((colors[i] >>  8) & 0xFF) / 255.0f * aa;
                        buf[2] = ( colors[i]        & 0xFF) / 255.0f * aa;
                        buf[3] = 1.0f;
                        RenderType rt = CustomGlintRenderer.forArmorGlint(glint, li, buf, i);
                        if (rt != null) list.add(flush.getBuffer(rt));
                    }
                } else {
                    int color = CustomGlintRenderer.computeAnimatedColor(glint, li);
                    float aa = ((color >> 24) & 0xFF) / 255.0f;
                    buf[0] = ((color >> 16) & 0xFF) / 255.0f * aa;
                    buf[1] = ((color >>  8) & 0xFF) / 255.0f * aa;
                    buf[2] = ( color        & 0xFF) / 255.0f * aa;
                    buf[3] = 1.0f;
                    RenderType rt = CustomGlintRenderer.forArmorGlint(glint, li, buf, 0);
                    if (rt != null) list.add(flush.getBuffer(rt));
                }
            }
            if (!list.isEmpty()) {
                VertexConsumer combined = list.size() == 1 ? list.get(0)
                        : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
                model.renderToBuffer(pose, combined, light, OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f);
            }
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
