package net.tunamods.customglint.module.compat.mekanism.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.tunamods.customglint.module.compat.mekanism.client.MekanismArmorGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Wraps {@code ItemRenderer.getFoilBufferDirect} at RETURN so a Mekanism special armor's mesh also feeds
 * our glint render types. The wrap only fires while {@link MekanismArmorGlint#isArmed()} — i.e. inside an
 * {@code ICustomArmor.render}, armed by {@code MekanismArmorGlintMixin} — so vanilla and every other
 * caller of this method are untouched.
 *
 * Our core {@code ItemRendererMixin} also hooks this method at HEAD for held-item glint; during Mekanism
 * armor its {@code CURRENT_ITEM_STACK} is unset so it returns without cancelling, letting the body run to
 * RETURN where this wrap applies. Dual SRG/named targets, require=0 on both.
 */
@Mixin(ItemRenderer.class)
public class FoilBufferMixin {

    /** SRG target: getFoilBufferDirect in obfuscated environments. */
    @Inject(method = "m_115222_", at = @At("RETURN"), require = 0, cancellable = true)
    private static void cg_mekaArmorGlint_srg(MultiBufferSource buffer, RenderType renderType,
            boolean noEntity, boolean withGlint, CallbackInfoReturnable<VertexConsumer> cir) {
        if (!MekanismArmorGlint.isArmed()) return;
        cir.setReturnValue(MekanismArmorGlint.wrap(cir.getReturnValue(), renderType));
    }

    /** Named target: getFoilBufferDirect in dev/deobf environments. */
    @Inject(
        method = "getFoilBufferDirect(Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/renderer/RenderType;ZZ)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
        at = @At("RETURN"), require = 0, remap = false, cancellable = true
    )
    private static void cg_mekaArmorGlint_named(MultiBufferSource buffer, RenderType renderType,
            boolean noEntity, boolean withGlint, CallbackInfoReturnable<VertexConsumer> cir) {
        if (!MekanismArmorGlint.isArmed()) return;
        cir.setReturnValue(MekanismArmorGlint.wrap(cir.getReturnValue(), renderType));
    }
}
