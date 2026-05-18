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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone-only compat: LayerDragonArmor is the single convergence point for fire/ice/lightning
 * dragon armor — it composes a per-(dragonType + 4 ordinals) layered texture and renders the dragon
 * model once via RenderType.entityTranslucent(layeredTex). We hook RETURN of render(), capture the
 * layered ResourceLocation via @Redirect on entityTranslucent, then re-render MODEL with our glint
 * render types and draw an outline using the layered texture.
 *
 * Masking: entityTranslucent's fragment shader does `if (color.a < 0.1) discard;`, so depth is
 * written only at opaque armor texels. forHorseArmorGlint (EQUAL_DEPTH + NO_LAYERING, no polygon
 * offset) therefore masks the glint to the actual armor silhouette across all four slots —
 * identical depth-equality mechanism we already rely on for horse armor.
 *
 * Source-of-glint resolution: HEAD > CHEST > LEGS > FEET — first stack with a custom glint wins
 * and supplies both the animated glint and (if also glowing) the outline color. Mixed-glint
 * configurations across slots fall back to the highest-priority slot's glint.
 *
 * EntityModel<?> access: cannot @Shadow getParentEntityModel<?> because the mixin can't declare an extends clause
 * onto RenderLayer<EntityDragonBase, ...> without compile-time access to those types. Resolve via
 * reflection by return-type match on RenderLayer's no-arg method (works in SRG and named runtimes).
 */
@Pseudo
@Mixin(targets = "com.github.alexthe666.iceandfire.client.render.entity.layer.LayerDragonArmor", remap = false)
public class LayerDragonArmorMixin {

    private static final ThreadLocal<ResourceLocation> CG_TEX = new ThreadLocal<>();
    private static volatile Method CG_GET_PARENT_MODEL;
    private static final EquipmentSlot[] CG_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

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

    // ── Capture layered ResourceLocation passed to entityTranslucent ─────────
    // Dual SRG/named pair because class-level remap=false leaves vanilla method descriptors as-written.

    @Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/github/alexthe666/iceandfire/entity/EntityDragonBase;FFFFFF)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/RenderType;m_110458_(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"),
            require = 0)
    private RenderType cg_capTex_srg(ResourceLocation loc) {
        CG_TEX.set(loc);
        return RenderType.entityTranslucent(loc);
    }

    @Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/github/alexthe666/iceandfire/entity/EntityDragonBase;FFFFFF)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/RenderType;entityTranslucent(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"),
            require = 0)
    private RenderType cg_capTex_named(ResourceLocation loc) {
        CG_TEX.set(loc);
        return RenderType.entityTranslucent(loc);
    }

    // ── Apply glint + outline at RETURN ──────────────────────────────────────

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/github/alexthe666/iceandfire/entity/EntityDragonBase;FFFFFF)V",
            at = @At("RETURN"), require = 0)
    private void cg_apply(PoseStack pose, MultiBufferSource buffer, int light,
            @Coerce LivingEntity entity, float a, float b, float c, float d, float e, float f,
            CallbackInfo ci) {
        ResourceLocation tex = CG_TEX.get();
        CG_TEX.remove();
        // No tex captured ⇒ IaF early-exited (no armor / dragon type unsupported) ⇒ nothing to glint.
        if (tex == null) return;
        if (CustomGlintRenderer.IN_OUTLINE.get()) return;

        ItemStack active = null;
        CustomGlint.Data glint = null;
        for (EquipmentSlot s : CG_SLOTS) {
            ItemStack stack = entity.getItemBySlot(s);
            CustomGlint.Data dat = CustomGlint.read(stack);
            if (dat != null) { active = stack; glint = dat; break; }
        }
        if (active == null) return;

        EntityModel<?> model = cg_getParentModel();
        if (model == null) return;

        // ── Stencil mask pre-pass ────────────────────────────────────────────
        // The dragon body geometry is rendered BEFORE this armor layer using a non-cutout base
        // texture, so depth is already written for the entire dragon model — armor and body share
        // identical geometry and depth values. Our EQUAL_DEPTH glint would therefore pass on the
        // bare body too, painting glint over the whole dragon. Pre-pass with the armor texture
        // under entityCutoutNoCull (hard alpha-discard) to write stencil=1 only at opaque armor
        // texels, then constrain the glint draw with stencil EQUAL 1.
        //
        // Critical ordering: build the glint VertexConsumer list AFTER the mask-pass endBatch.
        // BufferSource.endBatch(null) resets all started builders; if we obtained glint buffers
        // before flushing, those cached BufferBuilder references would be unstarted by the time
        // we tried to write through them ⇒ "BufferBuilder not started" crash inside the model.
        Minecraft.getInstance().getMainRenderTarget().enableStencil();
        if (buffer instanceof MultiBufferSource.BufferSource bs0) bs0.endBatch();
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilMask(0xFF);
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        GL11.glColorMask(false, false, false, false);
        GL11.glDepthMask(false);
        GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
        // KEEP on dpfail (not REPLACE): the armor model has front- AND back-facing geometry that
        // shares depth with the dragon body. Front faces pass LEQUAL → write stencil. Back faces
        // sit behind body depth → would fail LEQUAL. If we REPLACE on dpfail too, those back
        // texels also get stencil=1 and the glint becomes visible through the body. With KEEP on
        // dpfail, only the depth-passing armor surface marks the stencil.
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        RenderType maskType = RenderType.entityCutoutNoCull(tex);
        model.renderToBuffer(pose, buffer.getBuffer(maskType), light, OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f);
        if (buffer instanceof MultiBufferSource.BufferSource bs1) bs1.endBatch(maskType);
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);
        GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

        // ── Glint pass (stencil-gated) ───────────────────────────────────────
        // Now safe to start glint builders — nothing will flush them until our endBatch below.
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
        // Flush glint render types while stencil is still active. doModelOutline below clears
        // stencil and disables it before its own pass — we must not let our glint vertices
        // linger in batched buffers until then or they'd flush unmasked.
        if (buffer instanceof MultiBufferSource.BufferSource bs2) bs2.endBatch();
        GL11.glDisable(GL11.GL_STENCIL_TEST);

        if (CustomGlint.isGlowing(active)) {
            CustomGlintRenderer.doModelOutline(pose, buffer, light, model, tex, glint, EquipmentSlot.HEAD);
        }
    }
}
