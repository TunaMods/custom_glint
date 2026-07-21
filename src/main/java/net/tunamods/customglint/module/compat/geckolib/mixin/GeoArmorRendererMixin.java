package net.tunamods.customglint.module.compat.geckolib.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
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
 * <p>Vanilla {@code HumanoidArmorLayer.renderArmorPiece} calls the item's {@code getHumanoidArmorModel}
 * (→ GeckoLib's {@code prepForRender}, which sets the current stack/entity) immediately before
 * {@code renderToBuffer} → {@code actuallyRender}. We capture the stack + wearer at {@code prepForRender}
 * (all-vanilla params) into a per-thread slot, then {@code @ModifyVariable} the {@code VertexConsumer}
 * that {@code actuallyRender} is about to draw into, wrapping it so the same mesh draw ALSO feeds our
 * glint render types (exactly how GeckoLib layers vanilla foil). Both hooks target GeckoLib-owned
 * methods (stable names in dev + production) and reference only vanilla types, so there is no compile
 * or runtime dep on GeckoLib and no re-render to guard.
 *
 * <p>{@code actuallyRender} is spelled out with its full descriptor to bind the real overload (the type
 * bound {@code T extends Item & GeoItem} erases the animatable param to {@link net.minecraft.world.item.Item},
 * GeckoLib's bound) rather than the {@code GeoAnimatable} bridge. GeckoLib 4.9's {@code actuallyRender}
 * takes a packed-int render colour (trailing {@code III}); the four-float tail from the 4.x line is gone.
 * {@code remap = false}: names and Minecraft-typed descriptors match dev + prod.
 */
@Pseudo
@Mixin(targets = "software.bernie.geckolib.renderer.GeoArmorRenderer", remap = false)
public class GeoArmorRendererMixin {

    // GeckoLib 4.9's InternalUtil (the vanilla HumanoidArmorLayer hook) drives the 10-arg prepForRender
    // overload; the bare 4-arg overload the 4.x line called still exists but is never invoked for worn
    // armor now. We bind the 10-arg one and capture only the leading four params (Mixin permits omitting
    // trailing args at HEAD).
    private static final String PREP_FOR_RENDER =
            "prepForRender(Lnet/minecraft/world/entity/Entity;"
            + "Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/world/entity/EquipmentSlot;"
            + "Lnet/minecraft/client/model/HumanoidModel;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;FFFFF)V";

    private static final String ACTUALLY_RENDER =
            "actuallyRender(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/world/item/Item;"
            + "Lsoftware/bernie/geckolib/cache/object/BakedGeoModel;"
            + "Lnet/minecraft/client/renderer/RenderType;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;"
            + "Lcom/mojang/blaze3d/vertex/VertexConsumer;ZFIII)V";

    // Mixin here rejects a trailing-arg-omitted handler on this overload, so the full 10-arg signature is
    // spelled out; only entity + stack are used.
    @Inject(method = PREP_FOR_RENDER, at = @At("HEAD"), require = 0, remap = false)
    private void cg_captureStack(Entity entity, ItemStack stack, EquipmentSlot slot, HumanoidModel baseModel,
            MultiBufferSource bufferSource, float red, float green, float blue, float partialTick, float packedLight,
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
