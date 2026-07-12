package net.tunamods.customglint.module.compat.epicfight.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.module.compat.epicfight.client.EpicFightArmorGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Standalone-only Epic Fight compat for the worn-armor glint + glow. Epic Fight renders the patched model's
 * armor through its own {@code WearableItemLayer} (a skinned {@code SkinnedMesh} on the animated skeleton)
 * instead of vanilla {@code HumanoidArmorLayer}, so our core {@code HumanoidArmorLayerMixin} never fires for
 * the player/mob — the armor glint shows on a (vanilla-rendered) armor stand but vanishes on the wearer.
 *
 * <p>Every hook targets a vanilla type, so no Epic Fight class is referenced (soft-compat rule) and no
 * {@code @Coerce} is needed — {@code @Coerce Object} is NOT honored for an {@code @Inject} that captures
 * args whose real types are on the classpath, which is why an earlier attempt to grab the
 * {@code SkinnedMesh}/{@code Armature}/pose-array args of {@code renderArmor} failed to apply. Instead, this
 * mirrors {@code GeoArmorRendererMixin}:
 * <ul>
 *   <li>{@code getArmorModel} HEAD (all-vanilla params) — record the piece's stack/wearer/slot and reset the
 *       once-per-piece guard. Fires once per piece, before its material layers draw.</li>
 *   <li>{@code renderArmor} {@code @ModifyVariable} on the vanilla {@code MultiBufferSource} arg — wrap it so
 *       {@code SkinnedMesh.draw}'s {@code getBuffer} call tees the armor mesh into our glint RenderTypes and a
 *       glow silhouette recorder.</li>
 *   <li>{@code renderArmor} RETURN — queue the recorded silhouette.</li>
 * </ul>
 *
 * <p>{@code remap = false}: the target class name and the vanilla-typed descriptors match dev + production.
 * {@code require = 0} keeps a signature drift in a future Epic Fight build a silent no-op. The {@code method}
 * selectors spell out the full descriptors (Epic Fight types included) purely as match strings — the handler
 * bodies stay vanilla-only.
 */
@Pseudo
@Mixin(targets = "yesman.epicfight.client.renderer.patched.layer.WearableItemLayer", remap = false)
public class WearableItemLayerMixin {

    private static final String RENDER_ARMOR =
            "renderArmor(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I"
            + "Lyesman/epicfight/api/client/model/SkinnedMesh;Lyesman/epicfight/api/model/Armature;FFF"
            + "Lnet/minecraft/resources/ResourceLocation;[Lyesman/epicfight/api/utils/math/OpenMatrix4f;)V";

    @Inject(
        method = "getArmorModel(Lnet/minecraft/client/renderer/entity/layers/HumanoidArmorLayer;"
                + "Lnet/minecraft/client/model/HumanoidModel;Lnet/minecraft/client/model/Model;"
                + "Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ArmorItem;"
                + "Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/EquipmentSlot;)"
                + "Lyesman/epicfight/api/client/model/SkinnedMesh;",
        at = @At("HEAD"), require = 0, remap = false
    )
    private void cg_ef_beginArmorPiece(HumanoidArmorLayer<?, ?, ?> layer, HumanoidModel<?> vanillaModel,
            Model model, LivingEntity entity, ArmorItem armorItem, ItemStack stack, EquipmentSlot slot,
            CallbackInfoReturnable<Object> cir) {
        EpicFightArmorGlint.beginPiece(entity, stack, slot);
    }

    @ModifyVariable(method = RENDER_ARMOR, at = @At("HEAD"), argsOnly = true, ordinal = 0,
            require = 0, remap = false)
    private MultiBufferSource cg_ef_wrapArmorBuffers(MultiBufferSource buffer) {
        return EpicFightArmorGlint.wrapBuffers(buffer);
    }

    @Inject(method = RENDER_ARMOR, at = @At("RETURN"), require = 0, remap = false)
    private void cg_ef_flushArmorGlow(CallbackInfo ci) {
        EpicFightArmorGlint.flushGlow();
    }
}
