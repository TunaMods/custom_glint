package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.renderer.RenderType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.DyeableLeatherItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.ForgeHooksClient;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Intercepts renderArmorPiece at RETURN to draw custom glint and (if glowing) stencil outline on vanilla + modded armor. Dual SRG/named targets, require=0 on both. */
@Mixin(HumanoidArmorLayer.class)
public class HumanoidArmorLayerMixin {

    /** SRG target: injects at RETURN of renderArmorPiece in obfuscated environments. */
    @Inject(method = "m_117118_", at = @At("RETURN"), require = 0)
    private void cg_armorGlint_srg(PoseStack pPoseStack, MultiBufferSource pBuffer,
            LivingEntity pLivingEntity, EquipmentSlot pSlot, int pPackedLight,
            HumanoidModel pModel, CallbackInfo ci) {
        applyArmorGlint((HumanoidArmorLayer<?, ?, ?>) (Object) this,
                pPoseStack, pBuffer, pLivingEntity, pSlot, pPackedLight, pModel);
    }

    /** Named target: injects at RETURN of renderArmorPiece in dev/deobf environments. */
    @Inject(
        method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/model/HumanoidModel;)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void cg_armorGlint_named(PoseStack pPoseStack, MultiBufferSource pBuffer,
            LivingEntity pLivingEntity, EquipmentSlot pSlot, int pPackedLight,
            HumanoidModel pModel, CallbackInfo ci) {
        applyArmorGlint((HumanoidArmorLayer<?, ?, ?>) (Object) this,
                pPoseStack, pBuffer, pLivingEntity, pSlot, pPackedLight, pModel);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static void applyArmorGlint(HumanoidArmorLayer<?, ?, ?> layer,
            PoseStack poseStack, MultiBufferSource buffer,
            LivingEntity entity, EquipmentSlot slot, int packedLight, HumanoidModel model) {
        ItemStack stack = entity.getItemBySlot(slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof ArmorItem)) return;
        CustomGlint.Data glint = CustomGlint.read(stack);
        boolean glowing = CustomGlint.isGlowing(stack);
        // Bail only if there is nothing to render — no glint AND no glow.
        if (glint == null && !glowing) return;

        Model rendererModel = ForgeHooksClient.getArmorModel(entity, stack, slot, model);
        if (glint != null) {
            CustomGlint.Layer[] layers = glint.layers();
            float[] buf = CustomGlintRenderer.COLOR_BUF.get();

            List<VertexConsumer> list = new ArrayList<>();
            for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
                int[] colors = layers[layerIdx].colors();
                if (layers[layerIdx].simultaneous()) {
                    for (int i = 0; i < colors.length; i++) {
                        float a = ((colors[i] >> 24) & 0xFF) / 255.0f;
                        buf[0] = ((colors[i] >> 16) & 0xFF) / 255.0f * a;
                        buf[1] = ((colors[i] >>  8) & 0xFF) / 255.0f * a;
                        buf[2] = ( colors[i]        & 0xFF) / 255.0f * a;
                        buf[3] = 1.0f;
                        RenderType rt = CustomGlintRenderer.forArmorGlint(glint, layerIdx, buf, i);
                        if (rt != null) list.add(buffer.getBuffer(rt));
                    }
                } else {
                    int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                    float a = ((color >> 24) & 0xFF) / 255.0f;
                    buf[0] = ((color >> 16) & 0xFF) / 255.0f * a;
                    buf[1] = ((color >>  8) & 0xFF) / 255.0f * a;
                    buf[2] = ( color        & 0xFF) / 255.0f * a;
                    buf[3] = 1.0f;
                    RenderType rt = CustomGlintRenderer.forArmorGlint(glint, layerIdx, buf, 0);
                    if (rt != null) list.add(buffer.getBuffer(rt));
                }
            }
            if (!list.isEmpty()) {
                VertexConsumer combined = list.size() == 1 ? list.get(0) : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
                rendererModel.renderToBuffer(poseStack, combined, packedLight, OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f);
            }
        }
        if (glowing) {
            // Use Forge's HumanoidArmorLayer#getArmorResource: it splits the colon out of
            // materials whose getName() returns "namespace:path" (e.g. EK's
            // "magistuarmory:wingedhussarchestplate") and produces a valid
            // "{namespace}:textures/models/armor/{name}_layer_X.png". Building the path
            // ourselves and calling `new ResourceLocation` directly throws on the embedded
            // colon and falls back to SOLID — alpha-discard then misses, and stencil-write
            // stamps the full cuboid (visible as a giant-rectangle outline on EK
            // WingedHussar wings, where the bounding plane dwarfs the visible feather).
            ResourceLocation armorTex = CustomGlint.SOLID;
            if (stack.getItem() instanceof ArmorItem) {
                try {
                    armorTex = layer.getArmorResource(entity, stack, slot, null);
                } catch (Exception ignored) { armorTex = CustomGlint.SOLID; }
            }
            EntityModel<?> outlineModel = rendererModel instanceof EntityModel<?> em ? em : model;

            // EK halfarmor chestplate: arm cuboids in EK's model sample opaque pixels in the
            // halfarmor chest texture even though no arm armor is visually intended, so the
            // stencil outline pass forms a ring around the entire arm. EK compat installs
            // chestArmorHidesArmsInOutline keyed on texture path; when true, hide arm parts
            // for the outline call only. Other EK chests have legitimate sleeve coverage and
            // fall through unchanged.
            HumanoidModel<?> armHideModel = null;
            boolean savedRightArm = false, savedLeftArm = false;
            if (slot == EquipmentSlot.CHEST
                    && outlineModel instanceof HumanoidModel<?> hm
                    && CustomGlintRenderer.chestArmorHidesArmsInOutline.test(armorTex)) {
                armHideModel = hm;
                savedRightArm = hm.rightArm.visible;
                savedLeftArm  = hm.leftArm.visible;
                hm.rightArm.visible = false;
                hm.leftArm.visible  = false;
            }

            // Parts that should be excluded from the outline entirely (EK WingedHussar wings:
            // flat 0×32×14 planes too far from the chest pivot — the 1.04× dilation produces
            // a ghost feather offset from the original, and the per-part pixel-translate
            // approach can't honor depth between overlapping wings either. Compat installs the
            // hook to nominate parts; mixin hides them for doModelOutline and restores after,
            // so the broken dilation never draws on them and they simply have no outline).
            ModelPart[] hiddenParts = null;
            boolean[] savedHiddenVisible = null;
            if (slot == EquipmentSlot.CHEST && outlineModel instanceof HumanoidModel<?> hmx) {
                hiddenParts = CustomGlintRenderer.armorExtraOutlineParts.apply(hmx, armorTex);
                if (hiddenParts != null && hiddenParts.length > 0) {
                    savedHiddenVisible = new boolean[hiddenParts.length];
                    for (int i = 0; i < hiddenParts.length; i++) {
                        savedHiddenVisible[i] = hiddenParts[i].visible;
                        hiddenParts[i].visible = false;
                    }
                }
            }

            // For dyeable armor (DyeableLeatherItem) the visible coverage is split across base
            // + overlay textures — base is tinted, overlay is untinted detail. Some EK Knight
            // chestplates (e.g. crusader) put the chest in base but the arm sleeves in overlay.
            // If we only outline the base texture, arms get no outline. Derive the overlay path
            // via Forge's getArmorResource(type="overlay") and run a second outline pass when
            // it exists. Non-dyeable armors skip this entirely. Overlay path resolution can
            // throw on weird material names → guarded the same way as the base path.
            ResourceLocation overlayTex = null;
            if (stack.getItem() instanceof DyeableLeatherItem) {
                try {
                    ResourceLocation candidate = layer.getArmorResource(entity, stack, slot, "overlay");
                    if (candidate != null
                            && Minecraft.getInstance().getResourceManager()
                                    .getResource(candidate).isPresent()) {
                        overlayTex = candidate;
                    }
                } catch (Exception ignored) {}
            }

            try {
                CustomGlintRenderer.doModelOutline(poseStack, buffer, packedLight, outlineModel, armorTex, stack, slot);
                if (overlayTex != null) {
                    CustomGlintRenderer.doModelOutline(poseStack, buffer, packedLight, outlineModel, overlayTex, stack, slot);
                }
            } finally {
                if (armHideModel != null) {
                    armHideModel.rightArm.visible = savedRightArm;
                    armHideModel.leftArm.visible  = savedLeftArm;
                }
                if (hiddenParts != null && savedHiddenVisible != null) {
                    for (int i = 0; i < hiddenParts.length; i++) {
                        hiddenParts[i].visible = savedHiddenVisible[i];
                    }
                }
            }
        }
    }

}
