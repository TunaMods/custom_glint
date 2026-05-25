package net.tunamods.customglint.module.compat.epicknights.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.compat.epicknights.EpicKnightsGlintRT;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Standalone-only compat: Epic Knights renders armor attachments (plumes, surcoats, crowns)
 * via {@code ArmorDecorationLayer} which calls {@code ItemRenderer.getArmorFoilBuffer} directly
 * — so {@code HumanoidArmorLayerMixin} never fires for them. The base armor already has glint
 * applied by the core mixin; this fills in each decoration piece.
 *
 * Captures the ItemStack on the slot in {@code renderPiece} via ThreadLocal so the per-decoration
 * {@code renderDecoration} call can look up its glint data without referencing EK's internal
 * {@code ArmorDecorationItem.DecorationInfo} type (not on our compile classpath).
 *
 * Uses {@link EpicKnightsGlintRT#forDecorationGlint} (LEQUAL depth) rather than
 * {@code CustomGlint.forArmorGlint} (EQUAL): EK's decoration pass writes depth that doesn't
 * exactly match the EQUAL test of the vanilla-style armor glint, leaving the glint invisible.
 * LEQUAL is safe here because decoration meshes are dense (no transparent cutouts to bleed
 * through, unlike vanilla armor layer 1/2 textures).
 */
@Pseudo
@Mixin(targets = "com.magistuarmory.client.render.entity.layer.ArmorDecorationLayer", remap = false)
public class ArmorDecorationLayerMixin {

    private static final ThreadLocal<ItemStack> CG_STACK = new ThreadLocal<>();

    /**
     * Dyeable decorations (e.g. ceremonial helm's default big_plume) trigger TWO renderDecoration
     * calls per iteration: base texture (FFFZ) + overlay texture (IIZ → delegates to FFFZ). Both
     * fire our RETURN inject. The second call's stencil pre-pass clears the buffer and writes
     * against the overlay texture, which is mostly transparent — clobbering the glint we just
     * drew on the base. Skip when the same parts array fires twice back-to-back.
     */
    private static final ThreadLocal<ModelPart[]> CG_LAST_PARTS = new ThreadLocal<>();

    @Inject(method = "renderPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;I)V",
            at = @At("HEAD"), require = 0)
    private void cg_capStack(PoseStack pose, MultiBufferSource buf, LivingEntity entity,
            EquipmentSlot slot, int light, CallbackInfo ci) {
        CG_STACK.set(entity.getItemBySlot(slot));
        CG_LAST_PARTS.remove();
    }

    @Inject(method = "renderPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;I)V",
            at = @At("RETURN"), require = 0)
    private void cg_clearStack(PoseStack pose, MultiBufferSource buf, LivingEntity entity,
            EquipmentSlot slot, int light, CallbackInfo ci) {
        CG_STACK.remove();
        CG_LAST_PARTS.remove();
    }

    @Inject(method = "renderDecoration(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IIFFFZ[Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/resources/ResourceLocation;)V",
            at = @At("RETURN"), require = 0)
    private void cg_applyGlint(PoseStack pose, MultiBufferSource buffer, int light, int overlay,
            float r, float g, float b, boolean hasFoil, ModelPart[] parts, ResourceLocation texture,
            CallbackInfo ci) {
        if (CG_LAST_PARTS.get() == parts) return;
        CG_LAST_PARTS.set(parts);
        ItemStack stack = CG_STACK.get();
        if (stack == null || stack.isEmpty()) return;
        CustomGlint.Data glint = CustomGlint.read(stack);
        if (glint == null) return;
        EpicKnightsGlintRT.applyDecorationGlint(pose, buffer, light, overlay, parts, texture, glint,
                CustomGlint.isGlowing(stack), stack);
    }
}
