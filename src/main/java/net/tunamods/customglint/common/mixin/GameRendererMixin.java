package net.tunamods.customglint.common.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Backstop drain for the first-person held-item glow outline. {@link ItemInHandRendererMixin} drains at
 * the RETURN of {@code ItemInHandRenderer.renderHandsWithItems}, which is the ideal point in vanilla. But
 * FP-replacing mods (First-Person Model, Punchy, …) inject at that method's HEAD and {@code ci.cancel()} to
 * substitute their own hand render. A HEAD cancel skips every RETURN injector, so our drain there never
 * fires and the queued silhouette (still captured at {@code ItemRenderer.render}) is never rung.
 *
 * <p>{@code GameRenderer.renderItemInHand} is the caller of {@code renderHandsWithItems} and is never
 * cancelled. It sets the hand-FOV projection at its HEAD and leaves it live until the world pass restores it,
 * so at its RETURN the queued hand silhouettes replay onto the drawn pixels. Draining twice is safe:
 * whichever runs first clears the queue, so in vanilla off-pack this is a no-op ({@code renderHandsWithItems}
 * already drained), and under a cancel-happy FP mod or an active shader pack this is the drain that runs.
 * The flag is armed here too so a THIRD_PERSON-context hand item is routed to the FP queue even if the
 * {@code ItemInHandRenderer} HEAD is somehow skipped.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(
        method = "renderItemInHand(Lnet/minecraft/client/Camera;FLorg/joml/Matrix4f;)V",
        at = @At("HEAD"), require = 0, remap = false
    )
    private void cg_beginFpHand(Camera camera, float partialTick, Matrix4f projectionMatrix, CallbackInfo ci) {
        GlowOutlineRenderer.setFpHandPass(true);
    }

    @Inject(
        method = "renderItemInHand(Lnet/minecraft/client/Camera;FLorg/joml/Matrix4f;)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void cg_drainHeldFp(Camera camera, float partialTick, Matrix4f projectionMatrix, CallbackInfo ci) {
        // Chromatic FIRST (it reads the shared HELD_FP_MV snapshot that drainHeldFp then clears), then the ring.
        GlowOutlineRenderer.drainChromaticFp();
        GlowOutlineRenderer.drainHeldFp();
        GlowOutlineRenderer.setFpHandPass(false);
    }
}
