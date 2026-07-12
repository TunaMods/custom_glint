package net.tunamods.customglint.module.compat.sophisticatedbackpacks.mixin;

import net.tunamods.customglint.common.client.GlintCarrier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Sophisticated Backpacks compat: makes a glinted backpack show its Custom Glint, both as a held/inventory/
 * dropped ITEM and worn on an entity's back.
 *
 * <p>SB's {@code BackpackItemModel$SpecialRenderer.submit} pushes the backpack's base model through
 * {@code SubmitNodeCollector.submitItem(...)} with {@code FoilType.NONE} when the stack isn't enchanted. In
 * 26.1 {@code ItemFeatureRenderer.renderItem} only calls {@code getFoilBuffer} (where {@code ItemRendererMixin}
 * injects our glint) when the foil type isn't NONE. Normal flat items get the foil forced on by
 * {@code CuboidItemModelWrapperMixin}; SB builds the {@code ItemSubmit} itself and bypasses that, so an
 * unenchanted glinted backpack never reached the foil draw and showed no glint.
 *
 * <p>Both the held item and the worn backpack ({@code BackpackLayerRenderer.submitBackpack}, which builds its
 * render state via {@code ItemModelResolver.updateForTopItem} and calls {@code ItemStackRenderState.submit})
 * funnel through this one {@code submit}, and in both {@link GlintCarrier#SUBMIT_GLINT} is live (attached by
 * {@code ItemModelResolverMixin}). So forcing the {@code hasFoil} flag true whenever a glint is present makes
 * {@code submitItem} pass {@code FoilType.STANDARD} → the {@code ItemSubmit} node (which already captures the
 * glint) reaches {@code getFoilBuffer} → our glint replaces vanilla's foil. The placed-block renderer doesn't
 * go through this path (no {@code SUBMIT_GLINT}), so a placed backpack stays glint-free, as intended.
 *
 * <p>Soft compat: {@code @Pseudo} + {@code remap=false} + {@code require=0}, no compile-time SB dependency,
 * no-ops when SB is absent.
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedbackpacks.client.render.BackpackItemModel$SpecialRenderer", remap = false)
public class BackpackSpecialRendererMixin {

    /**
     * {@code submit(PoseStack, SubmitNodeCollector, int light, int overlay, boolean hasFoil, int tintColor)}
     * — {@code hasFoil} is the only boolean arg (ordinal 0). Force it on when the submitting item carries a
     * custom glint so the base model routes through the foil (glint) draw.
     */
    @ModifyVariable(method = "submit", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private boolean customglint$forceFoilForGlint(boolean hasFoil) {
        return hasFoil || GlintCarrier.SUBMIT_GLINT.get() != null;
    }
}
