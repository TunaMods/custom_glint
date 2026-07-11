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
 * Standalone-only Epic Fight compat for the entity glint. Epic Fight replaces vanilla entity rendering for
 * any patched entity: it renders its own skinned mesh via {@code PatchedLivingEntityRenderer.render} from
 * {@code RenderLivingEvent.Pre}, then cancels the event so vanilla {@code LivingEntityRenderer.render} never
 * finishes. But that event fires from INSIDE vanilla render, after our core {@code LivingEntityRendererMixin}
 * HEAD has already wrapped the buffer with the QUADS-mode glint fan-out — so Epic Fight draws its mesh
 * through that wrapper. Its mesh is a TRIANGLES-mode {@code entity_*} RenderType, and a QUADS glint RT fed a
 * triangle stream reassembles the primitives wrong: the glint shatters into facets.
 *
 * <p>At HEAD we strip the core QUADS wrapper off the buffer arg and re-install a TRIANGLES-mode glint
 * wrapper (and, when the entity glows, tee the mesh into the glow silhouette) via
 * {@link EpicFightEntityGlow#wrap}; at RETURN {@link EpicFightEntityGlow#flush} queues the captured ring.
 *
 * <p>The handler captures only the leading {@code entity} argument (Mixin permits omitting trailing context
 * parameters), so this class carries zero reference to Epic Fight's own types — same soft-compat spirit as
 * the other {@code @Pseudo} standalone mixins. {@code remap = false}: the target method and its
 * Minecraft-typed descriptor are identical in dev and production (class names are stable; the mod's own
 * method name isn't remapped).
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
