package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drains the first-person held-item glow outline. {@code renderHandsWithItems} draws both hands' items
 * and flushes them ({@code buffer.endBatch()}) before returning, all while the projection is the hand-FOV
 * projection and the modelview holds the camera transform. At the RETURN those matrices are still live
 * (they are popped immediately after in {@code GameRenderer.renderItemInHand}), so this is the point where
 * the captured hand poses replay onto exactly the pixels just drawn. Dual SRG/named, require=0 on both.
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    /** SRG target: drains the FP held-item outline in obfuscated environments. */
    @Inject(method = "m_109314_", at = @At("RETURN"), require = 0)
    private void cg_drainHeldFp_srg(float partialTicks, PoseStack poseStack,
            MultiBufferSource.BufferSource buffer, LocalPlayer player, int combinedLight, CallbackInfo ci) {
        GlowOutlineRenderer.drainHeldFp();
    }

    /** Named target: drains the FP held-item outline in dev/deobf environments. */
    @Inject(
        method = "renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void cg_drainHeldFp_named(float partialTicks, PoseStack poseStack,
            MultiBufferSource.BufferSource buffer, LocalPlayer player, int combinedLight, CallbackInfo ci) {
        GlowOutlineRenderer.drainHeldFp();
    }
}
