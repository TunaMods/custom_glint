package net.tunamods.customglint.module.compat.iceandfire.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.module.compat.iceandfire.MountArmorCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone-only compat: LayerHippogriffSaddle renders armor / saddle / bridle / chest in one pass.
 * Only the armor branch (entity.getArmor() != 0) is interesting for glint — it picks one of three
 * solid textures (iron/gold/diamond) and draws the parent hippogryph model with that texture.
 *
 * Unlike dragon armor (one layered texture composed at runtime, captured via @Redirect on
 * entityTranslucent), hippogryph armor uses three pre-built RenderTypes built in the layer's
 * ctor. We resolve the texture directly from getArmor() rather than redirecting — the mapping is
 * stable and the texture paths are part of IaF's published assets.
 *
 * Mask + glint mechanics identical to {@link LayerDragonArmorMixin}: the armor texture's opaque
 * regions stamp stencil bit 0x80, and the glint RT only renders where bit 0x80 is set. See that
 * mixin for the full rationale (the parent body model shares depth with the armor mesh, so an
 * EQUAL_DEPTH glint without a stencil mask would paint glint onto the bare hippogryph too).
 *
 * Armor ItemStack source: {@link MountArmorCache}. IaF's hippogryphInventory SimpleContainer
 * doesn't sync to clients (only the armor tier int does), so we sync the stack ourselves via
 * GlintMountArmorSyncPacket (server trigger: EntityHippogryphArmorSyncMixin on refreshInventory
 * + StartTracking listener in IceAndFireCompat).
 */
@Pseudo
@Mixin(targets = "com.iafenvoy.iceandfire.render.entity.HippogryphEntityRenderer$LayerHippogriffSaddle", remap = false)
public class LayerHippogryphArmorMixin {

    // CE armor textures live under textures/entity/hippogryph/, NOT textures/models/. A wrong path
    // resolves to the missing-texture placeholder, which is fully opaque — the stencil mask then
    // stamps the entire model and the glint covers the whole hippogryph instead of just the armor.
    // Confirmed via unzip -l on iceandfire-2.0-beta.17.jar.
    private static final ResourceLocation CG_TEX_IRON =
            ResourceLocation.fromNamespaceAndPath("iceandfire", "textures/entity/hippogryph/armor_iron.png");
    private static final ResourceLocation CG_TEX_GOLD =
            ResourceLocation.fromNamespaceAndPath("iceandfire", "textures/entity/hippogryph/armor_gold.png");
    private static final ResourceLocation CG_TEX_DIAMOND =
            ResourceLocation.fromNamespaceAndPath("iceandfire", "textures/entity/hippogryph/armor_diamond.png");

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
                m = entity.getClass().getMethod("getArmorValue");
                CG_GET_ARMOR = m;
            }
            return (int) m.invoke(entity);
        } catch (Throwable t) {
            return 0;
        }
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/iafenvoy/iceandfire/entity/HippogryphEntity;FFFFFF)V",
            at = @At("RETURN"), require = 0)
    private void cg_apply(PoseStack pose, MultiBufferSource buffer, int light,
            @Coerce LivingEntity entity, float a, float b, float c, float d, float e, float f,
            CallbackInfo ci) {
        int armor = cg_getArmor(entity);
        if (armor == 0) return;
        if (CustomGlintRenderer.IN_OUTLINE.get()) return;

        ResourceLocation tex;
        switch (armor) {
            case 1: tex = CG_TEX_IRON; break;
            case 2: tex = CG_TEX_GOLD; break;
            case 3: tex = CG_TEX_DIAMOND; break;
            default: return;
        }

        ItemStack stack = MountArmorCache.get(entity.getId());
        CustomGlint.Data glint = CustomGlint.read(stack);
        if (glint == null) return;

        EntityModel<?> model = cg_getParentModel();
        if (model == null) return;

        // Draw the base armor through the UNWRAPPED buffer with armorCutoutNoCull, then glint via
        // forArmorGlint — the same fix LayerDragonArmorMixin / LayerHippocampusArmorMixin use.
        // Hippogryph armor reuses the body model at the SAME depth, so EntityGlintRender's wrapper
        // fanned the mount's body glint onto the armor (entity glint over armor glint) and the
        // EQUAL-depth body glint drew over the armor, leaving the bare body silhouette showing
        // through. armorCutoutNoCull's polygon offset nudges the armor in front of the body so the
        // body glint is depth-occluded there, and forArmorGlint (EQUAL + the matching offset, masked
        // by the armor texture's own alpha cutout) lands only on the armor's opaque texels. Routed
        // through the unwrapped buffer so the wrapper can't re-fan the body glint onto the armor.
        MultiBufferSource flush = EntityGlintRender.unwrap(buffer);
        model.renderToBuffer(pose, flush.getBuffer(RenderType.armorCutoutNoCull(tex)),
                light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);

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
            model.renderToBuffer(pose, combined, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        }

        if (CustomGlint.isGlowing(stack)) {
            // doModelOutline (slot==null) stamps the FULL body silhouette into its stencil slot, so
            // the back-side armor ring is suppressed across transparent body regions (feathers,
            // gaps) — no depth pre-fill, so nothing occludes the world or the mount's own far-side
            // glint behind the gaps. See LayerDragonArmorMixin for the full rationale.
            // Stack overload (not Data) so glowColors NBT drives the outline color when set.
            CustomGlintRenderer.doModelOutline(pose, buffer, light, model, tex, stack, null);
        }
    }
}
