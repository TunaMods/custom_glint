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
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Standalone-only compat for Ice &amp; Fire Community Edition dragon (barding) armor.
 *
 * <p><b>CE difference from original IaF.</b> The original {@code LayerDragonArmor} composed ONE
 * layered texture for the whole dragon and rendered the model once via
 * {@code RenderType.entityTranslucent(layeredTex)}. CE's {@code DragonArmorFeatureRenderer} instead
 * loops the equipment slots and renders the dragon model once PER PART (head / neck / body / tail),
 * each with its own {@code textures/entity/dragon_armor/armor_<part>_<material>.png} via
 * {@code entityCutoutNoCull}. The old single mask+glint pass at RETURN masked the whole model with
 * only the LAST captured texture (the tail), which the model's UVs sample broadly → the glint and
 * outline bled over the entire dragon.
 *
 * <p><b>Fix.</b> We {@link Redirect} each part's {@code EntityModel.renderToBuffer} call, draw the
 * base part unchanged, then run the stencil-mask + glint and the outline against THAT part's
 * texture ({@code CG_TEX}, side-channelled from the {@code entityCutoutNoCull} redirect that fires
 * immediately before each part draw). Masking is identical to the horse/mount path: the body shares
 * the dragon model, so an EQUAL-depth glint would cover every face — the mask writes stencil bit
 * {@code 0x80} only at the armor texture's opaque texels and the glint tests EQUAL {@code 0x80}.
 *
 * <p>Source-of-glint resolution: HEAD &gt; CHEST &gt; LEGS &gt; FEET — first stack with a custom glint
 * wins, resolved once at HEAD and reused for every part (the per-part texture mask is what keeps
 * each part's glint to its own armor).
 */
@Pseudo
@Mixin(targets = "com.iafenvoy.iceandfire.render.entity.feature.DragonArmorFeatureRenderer", remap = false)
public class LayerDragonArmorMixin {

    private static final ThreadLocal<ResourceLocation> CG_TEX = new ThreadLocal<>();
    private static final ThreadLocal<CustomGlint.Data> CG_GLINT = new ThreadLocal<>();
    private static final ThreadLocal<ItemStack> CG_ACTIVE = new ThreadLocal<>();
    private static final EquipmentSlot[] CG_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    // ── Capture each part's armor texture passed to entityCutoutNoCull, just before its draw. ──
    // Dual SRG/named pair; the SRG name targets the original's entityTranslucent and no-ops on CE.

    @Redirect(method = "m_117386_",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/RenderType;m_110458_(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"),
            require = 0)
    private RenderType cg_capTex_srg(ResourceLocation loc) {
        CG_TEX.set(loc);
        return RenderType.entityCutoutNoCull(loc);
    }

    @Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/iafenvoy/iceandfire/entity/DragonBaseEntity;FFFFFF)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/RenderType;entityCutoutNoCull(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"),
            require = 0)
    private RenderType cg_capTex_named(ResourceLocation loc) {
        CG_TEX.set(loc);
        return RenderType.entityCutoutNoCull(loc);
    }

    // ── Resolve the active glint once at HEAD. ──

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/iafenvoy/iceandfire/entity/DragonBaseEntity;FFFFFF)V",
            at = @At("HEAD"), require = 0)
    private void cg_head(PoseStack pose, MultiBufferSource buffer, int light,
            @Coerce LivingEntity entity, float a, float b, float c, float d, float e, float f, CallbackInfo ci) {
        CG_GLINT.remove();
        CG_ACTIVE.remove();
        if (CustomGlintRenderer.IN_OUTLINE.get()) return;
        for (EquipmentSlot s : CG_SLOTS) {
            ItemStack stack = entity.getItemBySlot(s);
            CustomGlint.Data dat = CustomGlint.read(stack);
            if (dat != null) { CG_ACTIVE.set(stack); CG_GLINT.set(dat); break; }
        }
    }

    // ── Per-part: draw the base armor part, then mask + glint + outline against THAT part's texture. ──

    @Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/iafenvoy/iceandfire/entity/DragonBaseEntity;FFFFFF)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"),
            require = 0)
    private void cg_drawPart(EntityModel<?> model, PoseStack pose, VertexConsumer consumer, int light, int overlay, int color,
            @Local(argsOnly = true) MultiBufferSource buffer) {
        // Unwrap once: used both to draw the base part and to flush our stencil RTs through the real
        // BufferSource.
        MultiBufferSource flush = EntityGlintRender.unwrap(buffer);
        ResourceLocation tex = CG_TEX.get();   // this part's armor texture (set by the redirect above)

        // Draw the base armor part through the UNWRAPPED buffer, and with armorCutoutNoCull instead
        // of CE's entityCutoutNoCull. Two reasons, both tied to the dragon being glinted:
        //   (1) The wrapper auto-glints entity_* RTs, so the entityCutoutNoCull consumer CE handed us
        //       already carried the dragon's BODY glint — drawing the armor through it stamped the
        //       body glint onto the armor.
        //   (2) The dragon armor reuses the dragon's OWN model/geometry, so armor and body sit at the
        //       SAME depth; the body glint (EQUAL depth) then draws over the armor wherever the body
        //       texture is opaque under it (head/chest, not tail). armorCutoutNoCull's polygon offset
        //       nudges the armor in front of the body so the body glint is depth-occluded there.
        // The matching glint below uses forArmorGlint (EQUAL + the same offset, masked by the armor
        // texture's own alpha cutout) instead of the stencil mask. Non-glinted dragons: flush ==
        // buffer and the offset is sub-pixel, so this is visually identical to before.
        VertexConsumer base = (tex != null) ? flush.getBuffer(RenderType.armorCutoutNoCull(tex)) : consumer;
        model.renderToBuffer(pose, base, light, overlay, color);

        if (CustomGlintRenderer.IN_OUTLINE.get()) return;
        if (tex == null) return;
        ItemStack active = CG_ACTIVE.get();
        if (active == null) return;
        CustomGlint.Data glint = CG_GLINT.get();

        if (glint != null) {
            // forArmorGlint masks by EQUAL depth against the armorCutoutNoCull base drawn above:
            // the base alpha-discards to opaque armor texels (so depth exists only there) at the
            // armor's offset depth, so the glint lands only on this part's armor AND sits in front of
            // the dragon's body glint. No stencil pass needed — the per-part armor texture cutout is
            // the mask. Routed through the unwrapped `flush` so the wrapper can't re-fan it.
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
                    int c = CustomGlintRenderer.computeAnimatedColor(glint, li);
                    float aa = ((c >> 24) & 0xFF) / 255.0f;
                    buf[0] = ((c >> 16) & 0xFF) / 255.0f * aa;
                    buf[1] = ((c >>  8) & 0xFF) / 255.0f * aa;
                    buf[2] = ( c        & 0xFF) / 255.0f * aa;
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
        }

        if (CustomGlint.isGlowing(active)) {
            // Body depth pre-fill so the outline's LEQUAL test is occluded by the full silhouette
            // (dragon textures have many transparent gaps that would otherwise leak the back-side
            // outline through the front). Then trace this part's armor outline via its texture.
            RenderType depthFill = CustomGlintRenderer.forBodyDepthFill(tex);
            model.renderToBuffer(pose, flush.getBuffer(depthFill), light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            if (flush instanceof MultiBufferSource.BufferSource bs3) bs3.endBatch(depthFill);

            CustomGlintRenderer.doModelOutline(pose, buffer, light, model, tex, active, null);
        }
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/iafenvoy/iceandfire/entity/DragonBaseEntity;FFFFFF)V",
            at = @At("RETURN"), require = 0)
    private void cg_clear(PoseStack pose, MultiBufferSource buffer, int light,
            @Coerce LivingEntity entity, float a, float b, float c, float d, float e, float f, CallbackInfo ci) {
        CG_TEX.remove();
        CG_GLINT.remove();
        CG_ACTIVE.remove();
    }
}
