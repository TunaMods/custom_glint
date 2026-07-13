package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Arms the first-person hand-pass flag and drains the FP held-item glow outline. {@code renderHandsWithItems}
 * is where the hand items are actually drawn - in vanilla it runs inside {@code GameRenderer.renderItemInHand},
 * but under a shader pack (Oculus/Iris) it is called from the gbuffer pass instead, BEFORE
 * {@code renderItemInHand}. So the flag must be armed here (not only at {@code renderItemInHand} HEAD) or the
 * item is captured with the flag unset, misrouted to the world queue, and drawn by the world path (scene-depth
 * occlusion on → ring hides behind the item; world projection/timing → offset on sprint).
 *
 * <p>Priority 1500 (&gt; Punchy/FPM's default 1000) so our HEAD runs before theirs - they inject at this same
 * HEAD and {@code ci.cancel()} to substitute their own hand render, and a cancel skips every later HEAD
 * callback. Running first guarantees the flag is set for the capture that happens inside their render.
 *
 * <p>Off-pack the ring drains here at RETURN (hand-FOV matrices still live). Under a pack this RETURN can land
 * mid-gbuffer (Iris would composite over the ring), so the drain is deferred to {@code renderItemInHand}
 * RETURN ({@link GameRendererMixin}), which runs after Iris's scene composite. Dual SRG/named, require=0.
 */
@Mixin(value = ItemInHandRenderer.class, priority = 1500)
public class ItemInHandRendererMixin {

    /** SRG target: arm the FP hand-pass flag (obfuscated environments). */
    @Inject(method = "m_109314_", at = @At("HEAD"), require = 0)
    private void cg_beginFpHand_srg(float partialTicks, PoseStack poseStack,
            MultiBufferSource.BufferSource buffer, LocalPlayer player, int combinedLight, CallbackInfo ci) {
        GlowOutlineRenderer.setFpHandPass(true);
    }

    /** Named target: arm the FP hand-pass flag (dev/deobf environments). */
    @Inject(
        method = "renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V",
        at = @At("HEAD"), require = 0, remap = false
    )
    private void cg_beginFpHand_named(float partialTicks, PoseStack poseStack,
            MultiBufferSource.BufferSource buffer, LocalPlayer player, int combinedLight, CallbackInfo ci) {
        GlowOutlineRenderer.setFpHandPass(true);
    }

    /** SRG target: drains the FP held-item outline in obfuscated environments. */
    @Inject(method = "m_109314_", at = @At("RETURN"), require = 0)
    private void cg_drainHeldFp_srg(float partialTicks, PoseStack poseStack,
            MultiBufferSource.BufferSource buffer, LocalPlayer player, int combinedLight, CallbackInfo ci) {
        cg_endFpHand();
    }

    /** Named target: drains the FP held-item outline in dev/deobf environments. */
    @Inject(
        method = "renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void cg_drainHeldFp_named(float partialTicks, PoseStack poseStack,
            MultiBufferSource.BufferSource buffer, LocalPlayer player, int combinedLight, CallbackInfo ci) {
        cg_endFpHand();
    }

    /** Off-pack: drain here (hand-FOV matrices live) and clear the flag. Under a pack: leave the queue for the
     *  post-composite drain at {@code renderItemInHand} RETURN; only clear the flag. */
    private static void cg_endFpHand() {
        if (!CustomGlintRenderer.isShaderPackActive()) GlowOutlineRenderer.drainHeldFp();
        GlowOutlineRenderer.setFpHandPass(false);
    }
}
