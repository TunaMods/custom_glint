package net.tunamods.customglint.module.compat.epicfight.mixin;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.tunamods.customglint.module.compat.epicfight.client.EpicFightEntityGlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Standalone-only Epic Fight compat. Epic Fight replaces vanilla entity rendering for any patched
 * entity: {@code RenderEngine$Events.renderLivingEvent} handles {@code RenderLivingEvent.Pre},
 * renders its own skinned mesh via {@code PatchedLivingEntityRenderer.render}, then cancels the
 * event — so vanilla {@code LivingEntityRenderer.render} (and our core {@code LivingEntityRendererMixin}
 * that captures the glow silhouette) never runs. Result: no glow outline on Epic Fight mobs/players.
 *
 * This re-installs the capture on Epic Fight's render path. {@code PatchedLivingEntityRenderer.render}
 * is the single method (no subclass overrides it) that draws the body mesh and every patched layer
 * through the {@code MultiBufferSource} arg. At HEAD we wrap that arg so each triangle-mode
 * {@code entity_*} draw is teed into a record-only buffer; at RETURN the accumulated silhouettes are
 * queued as one ring. See {@link EpicFightEntityGlow} for the capture / queue details.
 *
 * The handler captures only the leading {@code entity} argument (Mixin permits omitting trailing
 * context parameters), so this class carries zero reference to Epic Fight's own types — same
 * soft-compat spirit as our other {@code @Pseudo} standalone mixins. {@code remap = false} everywhere:
 * the target method and its Minecraft-typed descriptor are identical in dev and production (class names
 * are stable; the mod's own method name isn't remapped).
 */
@Pseudo
@Mixin(targets = "yesman.epicfight.client.renderer.patched.entity.PatchedLivingEntityRenderer", remap = false)
public class PatchedLivingEntityRendererMixin {

    private static final String RENDER =
            "render(Lnet/minecraft/world/entity/LivingEntity;"
            + "Lyesman/epicfight/world/capabilities/entitypatch/LivingEntityPatch;"
            + "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;IF)V";

    @ModifyVariable(method = RENDER, at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0, remap = false)
    private MultiBufferSource cg_ef_wrapBuffer(MultiBufferSource original, LivingEntity entity) {
        return EpicFightEntityGlow.wrap(entity, original);
    }

    @Inject(method = RENDER, at = @At("RETURN"), require = 0, remap = false)
    private void cg_ef_flushOutline(CallbackInfo ci) {
        EpicFightEntityGlow.flush();
    }
}
