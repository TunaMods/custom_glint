package net.tunamods.customglint.module.compat.iceandfire.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.Minecraft;
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
import net.tunamods.customglint.module.compat.iceandfire.MountArmorCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.opengl.GL11;

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
 * Mask + glint + outline mechanics identical to LayerDragonArmorMixin: armor texture has
 * transparent regions and shares model+depth with the body (rendered earlier), so an EQUAL_DEPTH
 * glint without a stencil constraint would bleed onto bare hippogryph geometry. Pre-pass with
 * entityCutoutNoCull(tex) to mark stencil=1 on opaque armor texels, gate glint with stencil EQUAL 1.
 *
 * Armor ItemStack source: MountArmorCache. IaF's hippogryphInventory SimpleContainer doesn't
 * sync to clients (only the armor tier int does), so we sync the stack ourselves via
 * GlintMountArmorSyncPacket (server-side trigger: EntityHippogryphArmorSyncMixin on
 * refreshInventory + StartTracking listener in IceAndFireCompat).
 */
@Pseudo
@Mixin(targets = "com.github.alexthe666.iceandfire.client.render.entity.RenderHippogryph$LayerHippogriffSaddle", remap = false)
public class LayerHippogryphArmorMixin {

    private static final ResourceLocation CG_TEX_IRON =
            new ResourceLocation("iceandfire", "textures/models/hippogryph/armor_iron.png");
    private static final ResourceLocation CG_TEX_GOLD =
            new ResourceLocation("iceandfire", "textures/models/hippogryph/armor_gold.png");
    private static final ResourceLocation CG_TEX_DIAMOND =
            new ResourceLocation("iceandfire", "textures/models/hippogryph/armor_diamond.png");

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

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/github/alexthe666/iceandfire/entity/EntityHippogryph;FFFFFF)V",
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

        // ── Stencil mask pre-pass (see LayerDragonArmorMixin for full rationale) ─
        Minecraft.getInstance().getMainRenderTarget().enableStencil();
        if (buffer instanceof MultiBufferSource.BufferSource bs0) bs0.endBatch();
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilMask(0xFF);
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        GL11.glColorMask(false, false, false, false);
        GL11.glDepthMask(false);
        GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        RenderType maskType = RenderType.entityCutoutNoCull(tex);
        model.renderToBuffer(pose, buffer.getBuffer(maskType), light, OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f);
        if (buffer instanceof MultiBufferSource.BufferSource bs1) bs1.endBatch(maskType);
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);
        GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

        // ── Glint pass (stencil-gated) ───────────────────────────────────────
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
                    RenderType rt = CustomGlintRenderer.forHorseArmorGlint(glint, li, buf, i);
                    if (rt != null) list.add(buffer.getBuffer(rt));
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, li);
                float aa = ((color >> 24) & 0xFF) / 255.0f;
                buf[0] = ((color >> 16) & 0xFF) / 255.0f * aa;
                buf[1] = ((color >>  8) & 0xFF) / 255.0f * aa;
                buf[2] = ( color        & 0xFF) / 255.0f * aa;
                buf[3] = 1.0f;
                RenderType rt = CustomGlintRenderer.forHorseArmorGlint(glint, li, buf, 0);
                if (rt != null) list.add(buffer.getBuffer(rt));
            }
        }
        if (!list.isEmpty()) {
            VertexConsumer combined = list.size() == 1 ? list.get(0)
                    : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
            model.renderToBuffer(pose, combined, light, OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f);
        }
        if (buffer instanceof MultiBufferSource.BufferSource bs2) bs2.endBatch();
        GL11.glDisable(GL11.GL_STENCIL_TEST);

        if (CustomGlint.isGlowing(stack)) {
            CustomGlintRenderer.doModelOutline(pose, buffer, light, model, tex, glint, EquipmentSlot.HEAD);
        }
    }
}
