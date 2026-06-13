package net.tunamods.customglint.module.compat.iceandfire.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone-only compat for Ice &amp; Fire Community Edition (and any iafenvoy mod using uranus's
 * armor system). IaF player armor (copper, deathworm, dragonsteel/dragonscale, sea-serpent, troll, …)
 * no longer renders through vanilla HumanoidArmorLayer — uranus's ArmorFeatureRendererMixin injects
 * at HEAD of {@code renderArmorPiece}, dispatches to
 * {@code com.iafenvoy.uranus.client.render.armor.IArmorRendererBase#render} (a default interface
 * method), then CANCELS the vanilla path. Because uranus cancels at HEAD, {@link HumanoidArmorLayerMixin}'s
 * RETURN inject never fires for these items, so IaF armor got no glint/glow.
 *
 * IaF's concrete renderers (BasicArmorRenderer / ScaleArmorRenderer) only implement
 * {@code getHumanoidArmorModel}; neither overrides {@code render}, so the interface default is the
 * single render path and the right injection target. We @Inject at its RETURN — by then uranus has
 * already drawn the base armor through {@code armorCutoutNoCull}, so we just overlay glint + outline,
 * reproducing HumanoidArmorLayerMixin's logic.
 *
 * This mixin MUST be declared as an {@code interface}: the target is an interface, and a normal class
 * mixin fails to apply with "{@code @Mixin target type mismatch: …IArmorRendererBase is an interface …
 * SubType$Standard}" (verified in the launch log). Interface mixins can't hold mutable static fields,
 * so the reflective armor-texture lookup is cached in the nested {@link Tex} holder.
 *
 * The model matters: {@code render}'s argument {@code defaultModel} (LVT slot 7) is the all-visible
 * base humanoid, while the local {@code armorModel} (LVT slot 8, from {@code getHumanoidArmorModel})
 * is the one uranus configured with per-slot {@code ModelPart.visible}. Grabbing the arg lit the whole
 * body from a single piece; we capture the local via {@code @Local(ordinal = 1)} (the second
 * HumanoidModel-typed local) instead.
 */
@Pseudo
@Mixin(targets = "com.iafenvoy.uranus.client.render.armor.IArmorRendererBase", remap = false)
public interface UranusArmorRendererMixin {

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/model/HumanoidModel;)V",
            at = @At("RETURN"), require = 0)
    private void cg_drawArmor(PoseStack pose, MultiBufferSource buffer, LivingEntity entity,
            EquipmentSlot slot, int light, ItemStack stack, HumanoidModel defaultModel, CallbackInfo ci,
            @Local(ordinal = 1) HumanoidModel armorModel) {
        if (CustomGlintRenderer.IN_OUTLINE.get()) return;
        CustomGlint.Data glint = CustomGlint.read(stack);
        boolean glowing = CustomGlint.isGlowing(stack);
        if (glint == null && !glowing) return;

        if (glint != null) {
            CustomGlint.Layer[] layers = glint.layers();
            float[] buf = CustomGlintRenderer.COLOR_BUF.get();
            List<VertexConsumer> list = new ArrayList<>();
            for (int li = 0; li < layers.length; li++) {
                int[] colors = layers[li].colors();
                if (layers[li].simultaneous()) {
                    for (int i = 0; i < colors.length; i++) {
                        float a = ((colors[i] >> 24) & 0xFF) / 255.0f;
                        buf[0] = ((colors[i] >> 16) & 0xFF) / 255.0f * a;
                        buf[1] = ((colors[i] >>  8) & 0xFF) / 255.0f * a;
                        buf[2] = ( colors[i]        & 0xFF) / 255.0f * a;
                        buf[3] = 1.0f;
                        RenderType rt = CustomGlintRenderer.forArmorGlint(glint, li, buf, i);
                        if (rt != null) list.add(buffer.getBuffer(rt));
                    }
                } else {
                    int c = CustomGlintRenderer.computeAnimatedColor(glint, li);
                    float a = ((c >> 24) & 0xFF) / 255.0f;
                    buf[0] = ((c >> 16) & 0xFF) / 255.0f * a;
                    buf[1] = ((c >>  8) & 0xFF) / 255.0f * a;
                    buf[2] = ( c        & 0xFF) / 255.0f * a;
                    buf[3] = 1.0f;
                    RenderType rt = CustomGlintRenderer.forArmorGlint(glint, li, buf, 0);
                    if (rt != null) list.add(buffer.getBuffer(rt));
                }
            }
            if (!list.isEmpty()) {
                VertexConsumer combined = list.size() == 1 ? list.get(0)
                        : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
                armorModel.renderToBuffer(pose, combined, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            }
        }

        if (glowing) {
            ResourceLocation tex = Tex.get(this, stack, entity, slot);
            if (tex != null) {
                CustomGlintRenderer.doModelOutline(pose, buffer, light, (EntityModel<?>) armorModel, tex, stack, slot);
            }
        }
    }

    /** Holder for the reflective {@code getArmorTexture} lookup — interface mixins can't have mutable static fields. */
    final class Tex {
        private static final String IFACE = "com.iafenvoy.uranus.client.render.armor.IArmorRendererBase";
        private static volatile Method GET_TEX;

        private Tex() {}

        static ResourceLocation get(Object self, ItemStack stack, LivingEntity entity, EquipmentSlot slot) {
            try {
                Method m = GET_TEX;
                if (m == null) {
                    m = Class.forName(IFACE).getMethod("getArmorTexture",
                            ItemStack.class, Entity.class, EquipmentSlot.class);
                    m.setAccessible(true);
                    GET_TEX = m;
                }
                return (ResourceLocation) m.invoke(self, stack, entity, slot);
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
