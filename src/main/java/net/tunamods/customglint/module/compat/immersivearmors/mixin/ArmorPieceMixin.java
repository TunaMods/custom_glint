package net.tunamods.customglint.module.compat.immersivearmors.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.module.compat.immersivearmors.client.ImmersiveArmorsGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Standalone-only Immersive Armors compat. IA cancels the vanilla {@code HumanoidArmorLayer} draw for its
 * {@code ExtendedArmorItem}s and renders each slot as a list of {@code Piece}s. The two piece types that
 * draw the actual armor geometry — {@code LayerPiece} (the layered body/leggings shells) and
 * {@code ModelPiece} (deco models) — share one erased {@code render} descriptor, so a single mixin hits
 * both. At {@code render} RETURN the piece's model still holds the pose it was just drawn under, so we
 * re-render it with our glint render types and (when glowing) trace its silhouette into the glow mask.
 *
 * Hooking {@code render} rather than the shared {@code renderParts} gives us the wearer entity (needed to
 * key the glow ring) and fires exactly once per piece, avoiding the dyeable colored+white double draw that
 * happens inside {@code renderParts}.
 *
 * {@code remap = false}: the target is IA-owned and its {@code render} signature references only vanilla
 * types, stable in dev + production. Reflection into the piece's model/texture lives in the glue class so
 * there is no compile or runtime dep on Immersive Armors.
 */
@Pseudo
@Mixin(targets = {
        "immersive_armors.client.render.entity.piece.LayerPiece",
        "immersive_armors.client.render.entity.piece.ModelPiece"
}, remap = false)
public class ArmorPieceMixin {

    private static final String RENDER =
            "render(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;"
            + "I"
            + "Lnet/minecraft/world/entity/LivingEntity;"
            + "Lnet/minecraft/world/item/ItemStack;"
            + "F"
            + "Lnet/minecraft/world/entity/EquipmentSlot;"
            + "Lnet/minecraft/client/model/HumanoidModel;)V";

    @Inject(method = RENDER, at = @At("RETURN"), require = 0, remap = false)
    private void cg_pieceGlint(PoseStack pose, MultiBufferSource buffer, int light, LivingEntity entity,
            ItemStack stack, float partialTick, EquipmentSlot slot, HumanoidModel parentModel, CallbackInfo ci) {
        ImmersiveArmorsGlint.render(this, pose, buffer, light, entity, stack);
    }
}
