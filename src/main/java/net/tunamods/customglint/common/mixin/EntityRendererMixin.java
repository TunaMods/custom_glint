package net.tunamods.customglint.common.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.tunamods.customglint.common.client.EntityGlintRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Consumes vanilla's entity outline when our glow outline is present on the same entity, so the two never
 * stack into a doubled ring.
 *
 * <p>{@code EntityRenderer.extractRenderState} sets {@code state.outlineColor} from
 * {@code Minecraft.shouldEntityAppearGlowing} (the glowing potion effect, spectator/team glow). When the
 * entity also carries our glow (per-instance glowing flag or glow colours), we zero that at RETURN so only
 * our coloured outline draws. Tightly gated on our glow, so vanilla glow on a non-glinted entity is left
 * alone. (Edge case: if our glow is suppressed by the outline distance/entity caps, the entity loses the
 * vanilla outline too at range, acceptable; ours is the intended look.)
 */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {

    @Inject(method = "extractRenderState", at = @At("RETURN"), require = 0)
    private void cg_consumeVanillaOutline(Entity entity, EntityRenderState state, float partialTicks,
            CallbackInfo ci) {
        if (state.outlineColor != 0 && entity instanceof LivingEntity le && EntityGlintRender.hasGlow(le)) {
            state.outlineColor = 0;
        }
    }
}
