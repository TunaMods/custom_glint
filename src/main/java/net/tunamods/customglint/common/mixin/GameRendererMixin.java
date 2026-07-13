package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Backstop drain for the first-person held-item glow outline. {@link ItemInHandRendererMixin} drains at
 * the RETURN of {@code ItemInHandRenderer.renderHandsWithItems}, which is the ideal point in vanilla. But
 * FP-replacing mods (First-Person Model, Punchy, …) inject at that method's HEAD and {@code ci.cancel()} to
 * substitute their own hand render - a HEAD cancel skips every RETURN injector, so our drain never fires and
 * the queued silhouette (still captured at {@code ItemRenderer.render}) is never rung.
 *
 * {@code GameRenderer.renderItemInHand} is the caller of {@code renderHandsWithItems} and is never cancelled.
 * At its RETURN the hand-FOV projection is still live (it is set at the method's HEAD and only restored later
 * by the world pass), so the queued hand silhouettes replay onto the drawn pixels exactly as at the vanilla
 * drain point. Draining twice is safe: whichever runs first clears the queue, so in vanilla this is a no-op
 * (renderHandsWithItems already drained), and under a cancel-happy mod this is the drain that actually runs.
 * Dual SRG/named, require=0 on both.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    /** SRG target: mark the start of the first-person hand pass (obfuscated environments). */
    @Inject(method = "m_109120_", at = @At("HEAD"), require = 0)
    private void cg_beginFpHand_srg(PoseStack poseStack, Camera camera, float partialTicks, CallbackInfo ci) {
        GlowOutlineRenderer.setFpHandPass(true);
    }

    /** Named target: mark the start of the first-person hand pass (dev/deobf environments). */
    @Inject(
        method = "renderItemInHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/Camera;F)V",
        at = @At("HEAD"), require = 0, remap = false
    )
    private void cg_beginFpHand_named(PoseStack poseStack, Camera camera, float partialTicks, CallbackInfo ci) {
        GlowOutlineRenderer.setFpHandPass(true);
    }

    /** SRG target: drain the FP held outline, then end the hand pass (obfuscated environments). */
    @Inject(method = "m_109120_", at = @At("RETURN"), require = 0)
    private void cg_drainHeldFp_srg(PoseStack poseStack, Camera camera, float partialTicks, CallbackInfo ci) {
        GlowOutlineRenderer.drainHeldFp();
        GlowOutlineRenderer.setFpHandPass(false);
    }

    /** Named target: drain the FP held outline, then end the hand pass (dev/deobf environments). */
    @Inject(
        method = "renderItemInHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/Camera;F)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void cg_drainHeldFp_named(PoseStack poseStack, Camera camera, float partialTicks, CallbackInfo ci) {
        GlowOutlineRenderer.drainHeldFp();
        GlowOutlineRenderer.setFpHandPass(false);
    }
}
