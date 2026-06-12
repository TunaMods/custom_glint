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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone-only compat: LayerDragonArmor is the single convergence point for fire/ice/lightning
 * dragon armor — it composes a per-(dragonType + 4 ordinals) layered texture and renders the dragon
 * model once via RenderType.entityTranslucent(layeredTex). We hook RETURN of render(), capture the
 * layered ResourceLocation via @Redirect on entityTranslucent, then re-render MODEL with our
 * stencil-mask + glint RTs and draw an outline using the layered texture.
 *
 * Masking: the dragon body shares the same EntityModel as the armor layer (IaF reuses the parent
 * model with the layered armor texture), so an EQUAL_DEPTH glint passes on every face of the
 * dragon — not just the armor. The fix is a stencil pre-pass that writes bit 0x80 only where the
 * armor texture's alpha cutoff passes, and a glint pass that tests EQUAL 0x80 to constrain to
 * armor texels. Both passes use {@link CustomGlintRenderer}'s LayeringStateShard-based RTs
 * ({@link CustomGlintRenderer#forMountArmorStencilMask}, {@link CustomGlintRenderer#forMountArmorGlint}),
 * which set/restore stencil state inside setup/clear — making timing deterministic across
 * BufferSource flushes (the prior manual GL11.glStencil* approach interacted unreliably with
 * batched flushes).
 *
 * Source-of-glint resolution: HEAD &gt; CHEST &gt; LEGS &gt; FEET — first stack with a custom glint wins
 * and supplies both the animated glint and (if also glowing) the outline color. Mixed-glint
 * configurations across slots fall back to the highest-priority slot's glint.
 *
 * EntityModel&lt;?&gt; access: cannot @Shadow getParentEntityModel&lt;?&gt; because the mixin can't declare
 * an extends clause onto RenderLayer&lt;EntityDragonBase, ...&gt; without compile-time access to those
 * types. Resolve via reflection by return-type match on RenderLayer's no-arg method.
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

    // ── Apply mask + glint + outline at RETURN ───────────────────────────────

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

        // ── Stencil mask pass ───────────────────────────────────────────────
        // Renders the parent model with the armor texture through entity-cutout's alpha-discard
        // shader; stencil bit 0x80 is set only at opaque armor texels. The mask RT's
        // LayeringStateShard handles GL state in setup/clear, so the stencil ops are bound to this
        // specific draw inside the BufferSource flush. Flushing the mask immediately (endBatch
        // on this RT) commits the stencil before the glint draws build, guaranteeing the glint
        // sees the populated mask bit.
        RenderType maskType = CustomGlintRenderer.forMountArmorStencilMask(tex);
        model.renderToBuffer(pose, buffer.getBuffer(maskType), light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        if (buffer instanceof MultiBufferSource.BufferSource bs0) bs0.endBatch(maskType);

        // ── Glint pass (stencil EQUAL 0x80) ─────────────────────────────────
        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();
        List<VertexConsumer> list = new ArrayList<>();
        List<RenderType> glintTypes = new ArrayList<>();
        for (int li = 0; li < layers.length; li++) {
            int[] colors = layers[li].colors();
            if (layers[li].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    float aa = ((colors[i] >> 24) & 0xFF) / 255.0f;
                    buf[0] = ((colors[i] >> 16) & 0xFF) / 255.0f * aa;
                    buf[1] = ((colors[i] >>  8) & 0xFF) / 255.0f * aa;
                    buf[2] = ( colors[i]        & 0xFF) / 255.0f * aa;
                    buf[3] = 1.0f;
                    RenderType rt = CustomGlintRenderer.forMountArmorGlint(glint, li, buf, i);
                    if (rt != null) { list.add(buffer.getBuffer(rt)); glintTypes.add(rt); }
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, li);
                float aa = ((color >> 24) & 0xFF) / 255.0f;
                buf[0] = ((color >> 16) & 0xFF) / 255.0f * aa;
                buf[1] = ((color >>  8) & 0xFF) / 255.0f * aa;
                buf[2] = ( color        & 0xFF) / 255.0f * aa;
                buf[3] = 1.0f;
                RenderType rt = CustomGlintRenderer.forMountArmorGlint(glint, li, buf, 0);
                if (rt != null) { list.add(buffer.getBuffer(rt)); glintTypes.add(rt); }
            }
        }
        if (!list.isEmpty()) {
            VertexConsumer combined = list.size() == 1 ? list.get(0)
                    : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
            model.renderToBuffer(pose, combined, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        }
        // Flush only our glint RTs explicitly so the stencil test happens before doModelOutline's
        // own stencil writes (which use the lower bits but glStencilMask=0xFF, potentially clobbering
        // our bit 0x80 — by then we no longer need it).
        if (buffer instanceof MultiBufferSource.BufferSource bs2) {
            for (RenderType rt : glintTypes) bs2.endBatch(rt);
        }

        if (CustomGlint.isGlowing(active)) {
            // Body depth pre-fill so the outline test is occluded by the mob's full silhouette.
            // The dragon body texture has many transparent regions (between scales, wing
            // membranes) where the normal entityCutoutNoCull body render discarded depth —
            // without filling those, doModelOutline's LEQUAL test passes for back-side armor's
            // dilated mesh wherever body depth is missing, and the player sees the BACK armor
            // outline through the FRONT of the mob. Writing depth at every geometry fragment of
            // the parent model first fixes the occlusion regardless of texture alpha.
            RenderType depthFill = CustomGlintRenderer.forBodyDepthFill(tex);
            model.renderToBuffer(pose, buffer.getBuffer(depthFill), light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            if (buffer instanceof MultiBufferSource.BufferSource bs3) bs3.endBatch(depthFill);

            // slot=null routes through doModelOutline's AABB-centroid scale branch — non-humanoid
            // mount models need symmetric centroid dilation, not the chest-height humanoid pivot.
            // Pass the active stack (not just the Data) so the outline color resolution prefers
            // glowColors NBT when set via Glow Trim or the wand editor — the Data overload only
            // looks at glint layer 0, silently ignoring manual glow color choices.
            CustomGlintRenderer.doModelOutline(pose, buffer, light, model, tex, active, null);
        }
    }
}
