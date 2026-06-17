package net.tunamods.customglint.common.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.tunamods.customglint.common.client.EntityGlintRender;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the first-person hand projection matrix so the glow drain can scissor the held-item composite.
 *
 * <p>The first-person hand renders with its own projection ({@code GameRenderer.hudProjection}, set up
 * from {@code cameraState.hudFov} immediately before {@code renderItemInHand}). That projection is uploaded
 * to a GPU buffer — {@code RenderSystem.setProjectionMatrix} takes a {@code GpuBufferSlice}, not a
 * {@code Matrix4f} — so it can't be read back at the {@code ItemInHandRendererMixin} drain. We grab the
 * {@code Matrix4f} here at {@code renderItemInHand} HEAD (after {@code hudProjection} is configured) and
 * hand it to {@link EntityGlintRender}. The drain combines it with the live modelview — the same
 * {@code ProjMat * ModelViewMat} the glow-mask shader applies — to project the held item's silhouette AABB
 * to a tight composite scissor instead of running the held-item composite full-screen.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Shadow @Final private Projection hudProjection;

    @Inject(method = "renderItemInHand", at = @At("HEAD"), require = 0)
    private void cg_captureHandProjection(CameraRenderState cameraState, float deltaPartialTick,
            Matrix4fc modelViewMatrix, CallbackInfo ci) {
        EntityGlintRender.setFirstPersonProjection(hudProjection.getMatrix(new Matrix4f()));
    }
}
