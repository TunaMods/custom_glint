package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shader-pack (Oculus/Iris) glow-outline drain.
 *
 * <p>Without a pack the world ring is composited at {@code RenderLevelStageEvent.AFTER_WEATHER}. Under a
 * pack that doesn't work: Iris reroutes those Forge stages into its gbuffer passes, then runs its own
 * scene composite ({@code finalizeLevelRendering}) at the RETURN of {@code renderLevel}, overwriting the
 * main target, so a ring drawn at AFTER_WEATHER is erased and nothing shows.
 *
 * <p>This mixin re-drains the world ring at {@code renderLevel} RETURN, replaying the projection snapshot
 * taken at AFTER_WEATHER ({@link GlowOutlineRenderer#snapshotWorldProjection()}). The elevated mixin
 * priority (1500 &gt; Iris's default 1000) makes this injection apply later, so its RETURN callback runs
 * AFTER Iris's finalize and the ring composites over the finished frame. No-op without a pack (the
 * AFTER_WEATHER drain already ran and cleared the queue).
 *
 * <p>The HEAD/RETURN pair also brackets the world pass with {@code CustomGlintRenderer.setRenderingWorld}.
 * The compat glint paths (GeckoLib, Mekanism, Artifacts) read that flag to skip the inventory player
 * preview, where their entity-space glint would stretch into a projected ray under the GUI ortho.
 *
 * <p>Dual SRG/named, require=0 on both: same pattern as the other core mixins.
 *
 * <p>If the ring still doesn't show in-game under a pack, the first lever is this priority (whether the
 * callback lands before/after Iris's finalize at the shared RETURN); the second is the scene-depth
 * occlusion in {@code glow_silhouette.fsh} (main depth may differ post-finalize).
 */
@Mixin(value = LevelRenderer.class, priority = 1500)
public class LevelRendererMixin {

    @Inject(method = "m_109599_", at = @At("HEAD"), require = 0)
    private void cg_markWorldRenderStart_srg(PoseStack pose, float partialTick, long finishNano,
            boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture light,
            Matrix4f projection, CallbackInfo ci) {
        CustomGlintRenderer.setRenderingWorld(true);
    }

    @Inject(method = "m_109599_", at = @At("RETURN"), require = 0)
    private void cg_drainGlowUnderShaders_srg(PoseStack pose, float partialTick, long finishNano,
            boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture light,
            Matrix4f projection, CallbackInfo ci) {
        CustomGlintRenderer.setRenderingWorld(false);
        if (CustomGlintRenderer.isShaderPackActive()) {
            GlowOutlineRenderer.drainWorldShaderPack();
        }
    }

    @Inject(
        method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
        at = @At("HEAD"), require = 0, remap = false)
    private void cg_markWorldRenderStart_named(PoseStack pose, float partialTick, long finishNano,
            boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture light,
            Matrix4f projection, CallbackInfo ci) {
        CustomGlintRenderer.setRenderingWorld(true);
    }

    @Inject(
        method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
        at = @At("RETURN"), require = 0, remap = false)
    private void cg_drainGlowUnderShaders_named(PoseStack pose, float partialTick, long finishNano,
            boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture light,
            Matrix4f projection, CallbackInfo ci) {
        CustomGlintRenderer.setRenderingWorld(false);
        if (CustomGlintRenderer.isShaderPackActive()) {
            GlowOutlineRenderer.drainWorldShaderPack();
        }
    }
}
