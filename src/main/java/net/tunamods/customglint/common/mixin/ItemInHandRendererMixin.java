package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.tunamods.customglint.common.client.EntityGlintRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drains the first-person held-item glow outlines.
 *
 * <p>First-person hand items run through the same deferred {@code ItemFeatureRenderer.renderItem} path
 * as world items, so {@code ItemRendererMixin} queues their outline jobs into the shared
 * {@code ITEM_OUTLINES} queue. But the main {@code RenderLevelStageEvent.AfterOpaqueFeatures} drain runs
 * during the level framegraph, which finishes <em>before</em> {@code GameRenderer.renderItemInHand} —
 * so by the time the hand items queue, the level drain has already run and cleared its queue. Those jobs
 * would otherwise be dropped on the next frame's defensive reset, which is why the glow shows in 3rd
 * person / on dropped items but not on the held item in 1st person.
 *
 * <p>{@code renderHandsWithItems} submits the hand item nodes, then draws them
 * ({@code renderAllFeatures()}) and flushes ({@code endBatch()}) before returning — so at RETURN the hand
 * items are both queued and depth-committed to the main target. A second drain here composites their ring
 * (entity {@code BODY_OUTLINES} is already empty in 1st person, so this only paints the hand items).
 * Vanilla clears the depth buffer before the hand render (GameRenderer), so the silhouette occlusion test
 * naturally reads "visible" — correct, the held item always draws on top.
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    @Inject(method = "renderHandsWithItems", at = @At("RETURN"), require = 0)
    private void cg_drainHandItemOutlines(float frameInterp, PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector, LocalPlayer player, int lightCoords, CallbackInfo ci) {
        // Full-screen (no scissor): the hand item's pose is in view space, not camera-relative world
        // space, so the world projection used to size per-group scissors wouldn't line up. Only the held
        // item is queued here (one group), so a full-screen composite costs next to nothing.
        EntityGlintRender.drainBodyOutlines(false);
    }
}
