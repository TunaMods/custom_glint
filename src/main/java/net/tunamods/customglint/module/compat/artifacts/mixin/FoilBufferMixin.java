package net.tunamods.customglint.module.compat.artifacts.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.tunamods.customglint.module.compat.artifacts.client.ArtifactGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Wraps {@code ItemRenderer.getFoilBuffer} at RETURN so a worn artifact's mesh also feeds our glint
 * render types. The wrap only fires while {@link ArtifactGlint#isArmed()} (inside an
 * {@code ArtifactRenderer.render}, armed by {@code ArtifactGlintMixin}), so vanilla and every other
 * caller of this method are untouched.
 *
 * <p>Our core {@code ItemRendererMixin} also hooks this method at HEAD for held-item glint; during artifact
 * render its {@code CURRENT_ITEM_STACK} is unset so it returns without cancelling, letting the body run to
 * RETURN where this wrap applies. Named descriptor only ({@code remap = false}); {@code require = 0} so a
 * missing target in some environment no-ops.
 */
@Mixin(ItemRenderer.class)
public class FoilBufferMixin {

    @Inject(
        method = "getFoilBuffer(Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/renderer/RenderType;ZZ)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
        at = @At("RETURN"), require = 0, remap = false, cancellable = true
    )
    private static void cg_artifactGlint(MultiBufferSource buffer, RenderType renderType,
            boolean isItem, boolean hasFoil, CallbackInfoReturnable<VertexConsumer> cir) {
        if (!ArtifactGlint.isArmed()) return;
        cir.setReturnValue(ArtifactGlint.wrap(cir.getReturnValue(), renderType));
    }
}
