package net.tunamods.customglint.common.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Arms the first-person hand-pass flag and drains the first-person held-item glow outline.
 * {@code renderHandsWithItems} draws both hands' items and flushes them ({@code buffer.endBatch()}) before
 * returning, all while the projection is the hand-FOV projection and the modelview stack holds the camera
 * rotation. At the RETURN those matrices are still live (they are popped immediately after in
 * {@code GameRenderer.renderItemInHand}), so this is the one point where the captured camera-relative hand
 * poses replay onto exactly the pixels just drawn.
 *
 * <p>Priority 1500 (&gt; Punchy / First-Person Model's default 1000) so our HEAD runs before theirs. They
 * inject at this same HEAD and {@code ci.cancel()} to substitute their own hand render, and a HEAD cancel
 * skips every later HEAD callback. Running first guarantees the flag is set for the capture that happens
 * inside their render (they draw the held item with a THIRD_PERSON display context, which without the flag
 * would misroute to the world queue).
 *
 * <p>Off-pack the ring drains here at RETURN. Under a shader pack this RETURN can land mid-gbuffer (Iris
 * would composite over the ring), and under a cancel-happy FP mod it never fires at all; both cases fall
 * through to the backstop drain at {@code GameRenderer.renderItemInHand} RETURN ({@link GameRendererMixin}).
 */
@Mixin(value = ItemInHandRenderer.class, priority = 1500)
public class ItemInHandRendererMixin {

    @Inject(
        method = "renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V",
        at = @At("HEAD"), require = 0, remap = false
    )
    private void cg_beginFpHand(float partialTicks, PoseStack poseStack,
            MultiBufferSource.BufferSource buffer, LocalPlayer player, int combinedLight, CallbackInfo ci) {
        GlowOutlineRenderer.setFpHandPass(true);
    }

    @Inject(
        method = "renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void cg_drainHeldFpOutline(float partialTicks, PoseStack poseStack,
            MultiBufferSource.BufferSource buffer, LocalPlayer player, int combinedLight, CallbackInfo ci) {
        // Off-pack: drain here (hand-FOV matrices live) and clear the flag. Under a pack: leave the queue for
        // the post-composite backstop drain at renderItemInHand RETURN; only clear the flag.
        if (!CustomGlintRenderer.isShaderPackActive()) GlowOutlineRenderer.drainHeldFp();
        GlowOutlineRenderer.setFpHandPass(false);
    }
}
