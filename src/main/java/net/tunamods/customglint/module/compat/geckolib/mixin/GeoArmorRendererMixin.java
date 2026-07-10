package net.tunamods.customglint.module.compat.geckolib.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.module.compat.geckolib.client.GeckoArmorGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Standalone-only compat for any mod that renders worn armor through GeckoLib's
 * {@code GeoArmorRenderer} (Iron's Spells 'n Spellbooks, Ars Nouveau, …). GeckoLib armor never
 * flows through the vanilla {@code HumanoidArmorLayer} geometry path our core
 * {@code HumanoidArmorLayerMixin} hooks: {@code GeoArmorRenderer.renderToBuffer} builds its own foil
 * buffer and passes it down to {@code actuallyRender}, which draws the Geo bone mesh into it. So the
 * worn armor gets no custom glint.
 *
 * Vanilla {@code HumanoidArmorLayer.renderArmorPiece} calls the item's {@code getHumanoidArmorModel}
 * (→ GeckoLib's {@code prepForRender}, which sets the current stack/entity) immediately before
 * {@code renderToBuffer} → {@code actuallyRender}. We capture the stack + wearer at {@code prepForRender}
 * (all-vanilla params) into a per-thread slot, then {@code @ModifyVariable} the {@code VertexConsumer}
 * that {@code actuallyRender} is about to draw into, wrapping it so the same mesh draw ALSO feeds our
 * glint render types (exactly how GeckoLib layers vanilla foil). Both hooks target GeckoLib-owned
 * methods (stable names in dev + production) and reference only vanilla types, so there is no compile
 * or runtime dep on GeckoLib and no re-render to guard.
 *
 * {@code actuallyRender} is spelled out with its full descriptor to bind the real overload (animatable
 * erases to {@link ItemStack}'s item type {@code Item}, GeckoLib's type bound) rather than the
 * synthetic bridge. {@code remap = false}: names and Minecraft-typed descriptors match dev + prod.
 */
@Pseudo
@Mixin(targets = "software.bernie.geckolib.renderer.GeoArmorRenderer", remap = false)
public class GeoArmorRendererMixin {

    private static final String PREP_FOR_RENDER =
            "prepForRender(Lnet/minecraft/world/entity/Entity;"
            + "Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/world/entity/EquipmentSlot;"
            + "Lnet/minecraft/client/model/HumanoidModel;)V";

    private static final String ACTUALLY_RENDER =
            "actuallyRender(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/world/item/Item;"
            + "Lsoftware/bernie/geckolib/cache/object/BakedGeoModel;"
            + "Lnet/minecraft/client/renderer/RenderType;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;"
            + "Lcom/mojang/blaze3d/vertex/VertexConsumer;ZFIIFFFF)V";

    @Inject(method = PREP_FOR_RENDER, at = @At("HEAD"), require = 0, remap = false)
    private void cg_captureStack(Entity entity, ItemStack stack, EquipmentSlot slot, HumanoidModel baseModel,
            CallbackInfo ci) {
        GeckoArmorGlint.stash(entity, stack);
    }

    @ModifyVariable(method = ACTUALLY_RENDER, at = @At("HEAD"), argsOnly = true, ordinal = 0,
            require = 0, remap = false)
    private RenderType cg_stashArmorTexture(RenderType renderType) {
        return GeckoArmorGlint.stashTexture(renderType);
    }

    @ModifyVariable(method = ACTUALLY_RENDER, at = @At("HEAD"), argsOnly = true, ordinal = 0,
            require = 0, remap = false)
    private VertexConsumer cg_wrapGlintBuffer(VertexConsumer buffer) {
        return GeckoArmorGlint.wrapGlint(buffer);
    }

    @Inject(method = ACTUALLY_RENDER, at = @At("RETURN"), require = 0, remap = false)
    private void cg_flushArmorGlow(CallbackInfo ci) {
        GeckoArmorGlint.flush();
    }
}
