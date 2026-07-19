package net.tunamods.customglint.common.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Composites the deferred world glow outline at the TAIL of {@code renderLevel}.
 *
 * <p>Off shader pack, the world outline is drained at {@code RenderLevelStageEvent.AFTER_WEATHER}. Under an
 * Iris/Oculus pack the pack's own scene composite runs AFTER that stage, so a ring drawn there is overwritten.
 * That was the "no world outlines under a shader pack" case. The fix splits the drain: the silhouette mask
 * is accumulated at AFTER_WEATHER (where the world matrices are live), and the matrix-independent composite
 * blit is deferred to here, after the pack has finished compositing to the main target. {@code compositeWorld}
 * is a no-op unless an accumulation was deferred this frame, so this is free off-pack.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Inject(
        method = "renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
        at = @At("TAIL"), require = 0, remap = false
    )
    private void cg_compositeDeferredGlowOutline(CallbackInfo ci) {
        // Chromatic slick FIRST, then the glow ring on top. The chromatic composite is a fullscreen SCREEN
        // blend (src + dst - src*dst); composited after the ring it screen-brightens the ring's own texels
        // toward white wherever a slick overlaps them (a glowing elytra's own chromatic body abutting its
        // ring), so the ring was being washed out ("consumed by the chromatic behind it"). Blitting the slick
        // first, then alpha-blending the ring over it, keeps the ring crisp on top. Both no-op off-pack /
        // when nothing was queued, and each sets up + restores its own GL state, so the order is free to pick.
        GlowOutlineRenderer.compositeChromaticWorld();
        GlowOutlineRenderer.compositeWorld();
    }
}
