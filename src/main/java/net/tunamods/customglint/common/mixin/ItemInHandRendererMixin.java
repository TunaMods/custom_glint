package net.tunamods.customglint.common.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drains the first-person held-item glow outline. {@code renderHandsWithItems} draws both hands' items
 * and flushes them ({@code buffer.endBatch()}) before returning, all while the projection is the hand-FOV
 * projection and the modelview stack holds the camera rotation. At the RETURN those matrices are still
 * live (they are popped immediately after in {@code GameRenderer.renderItemInHand}), so this is the one
 * point where the captured camera-relative hand poses replay onto exactly the pixels just drawn.
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    @Inject(
        method = "renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void cg_drainHeldFpOutline(float partialTicks, PoseStack poseStack,
            MultiBufferSource.BufferSource buffer, LocalPlayer player, int combinedLight, CallbackInfo ci) {
        GlowOutlineRenderer.drainHeldFp();
    }
}
