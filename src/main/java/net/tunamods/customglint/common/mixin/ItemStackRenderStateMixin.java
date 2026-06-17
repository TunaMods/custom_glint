package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CgGlintHolder;
import net.tunamods.customglint.common.client.GlintCarrier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds the per-item glint on the reusable {@link ItemStackRenderState} scratch object and publishes it
 * into {@link GlintCarrier#SUBMIT_GLINT} for the duration of {@code submit(...)} so each freshly built
 * {@code ItemSubmit} node can carry it to the deferred draw. The glint is attached during
 * {@code ItemModelResolver.updateForTopItem} (see {@code ItemModelResolverMixin}); it is null for items
 * with no custom glint.
 */
@Mixin(ItemStackRenderState.class)
public class ItemStackRenderStateMixin implements CgGlintHolder {

    @Unique private CustomGlint.Data customglint$glint;
    @Unique private boolean customglint$glowing;
    @Unique private int[] customglint$glowColors;

    @Override
    public CustomGlint.Data customglint$getGlint() {
        return this.customglint$glint;
    }

    @Override
    public void customglint$setGlint(CustomGlint.Data glint) {
        this.customglint$glint = glint;
    }

    @Override
    public boolean customglint$isGlowing() {
        return this.customglint$glowing;
    }

    @Override
    public void customglint$setGlowing(boolean glowing) {
        this.customglint$glowing = glowing;
    }

    @Override
    public int[] customglint$getGlowColors() {
        return this.customglint$glowColors;
    }

    @Override
    public void customglint$setGlowColors(int[] glowColors) {
        this.customglint$glowColors = glowColors;
    }

    @Inject(method = "submit", at = @At("HEAD"), require = 0)
    private void cg_submitHead(PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay,
            int outlineColor, CallbackInfo ci) {
        GlintCarrier.SUBMIT_GLINT.set(this.customglint$glint);
        GlintCarrier.SUBMIT_GLOWING.set(this.customglint$glowing);
        GlintCarrier.SUBMIT_GLOW_COLORS.set(this.customglint$glowColors);
        // Fresh per-item token so all of a special item's sub-model submits share one outline group.
        GlintCarrier.SUBMIT_TOKEN.set(new Object());
    }

    @Inject(method = "submit", at = @At("RETURN"), require = 0)
    private void cg_submitReturn(PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay,
            int outlineColor, CallbackInfo ci) {
        GlintCarrier.SUBMIT_GLINT.remove();
        GlintCarrier.SUBMIT_GLOWING.remove();
        GlintCarrier.SUBMIT_GLOW_COLORS.remove();
        GlintCarrier.SUBMIT_TOKEN.remove();
    }
}
