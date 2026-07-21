package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.layers.MushroomCowMushroomLayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.MushroomCow;
import net.minecraft.world.level.block.state.BlockState;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Folds a mooshroom's mushrooms into the glow outline. The mushrooms are BLOCK models drawn via
 * {@code BlockRenderDispatcher.renderSingleBlock}, not an EntityModel through {@code renderColoredCutoutModel},
 * so no generic surface tee reaches them. Stash the entity at render HEAD, then on each mushroom's
 * {@code renderSingleBlock} draw the real block and, when the entity glows, re-render it into a record-only
 * capturing buffer and queue the silhouette under the entity's CAT_ENTITY id (merges with the cow's body ring).
 */
@Mixin(MushroomCowMushroomLayer.class)
public class MushroomCowMushroomLayerMixin {

    private static final ThreadLocal<LivingEntity> CG_ENTITY = new ThreadLocal<>();

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/animal/MushroomCow;FFFFFF)V",
            at = @At("HEAD"), require = 0, remap = false)
    private void cg_capEntity(PoseStack pose, MultiBufferSource buf, int light, MushroomCow entity,
            float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw,
            float headPitch, CallbackInfo ci) {
        CG_ENTITY.set(entity);
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/animal/MushroomCow;FFFFFF)V",
            at = @At("RETURN"), require = 0, remap = false)
    private void cg_clearEntity(PoseStack pose, MultiBufferSource buf, int light, MushroomCow entity,
            float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw,
            float headPitch, CallbackInfo ci) {
        CG_ENTITY.remove();
    }

    @Redirect(method = "renderMushroomBlock(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IZLnet/minecraft/world/level/block/state/BlockState;ILnet/minecraft/client/resources/model/BakedModel;)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderSingleBlock(Lnet/minecraft/world/level/block/state/BlockState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V"),
            require = 0, remap = false)
    private void cg_teeMushroom(BlockRenderDispatcher dispatcher, BlockState state, PoseStack pose,
            MultiBufferSource buffer, int light, int overlay) {
        dispatcher.renderSingleBlock(state, pose, buffer, light, overlay); // real draw, untouched
        LivingEntity entity = CG_ENTITY.get();
        if (entity == null || entity.isInvisible()) return;
        EntityGlintRender.Resolution r = EntityGlintRender.instanceResolver.resolve(entity);
        if (r == null || !(r.glowing || r.glowColors.length > 0)) return;
        // Record-only re-render (a second block draw is fine here: niche entity, and it keeps the real draw
        // on Sodium's fast path). The mushroom block traces against the block atlas, full-fill = its shape.
        GlowOutlineRenderer.CapturingBufferSource cap = new GlowOutlineRenderer.CapturingBufferSource(null);
        dispatcher.renderSingleBlock(state, pose, cap, light, overlay);
        cap.queueEntitySurface(EntityGlintRender.outlineColorFor(r), entity);
    }
}
