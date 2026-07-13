package net.tunamods.customglint.module.compat.artifacts.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.module.compat.artifacts.client.ArtifactGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Standalone-only Artifacts compat. Every {@code ArtifactRenderer} draws its worn model through
 * {@code ItemRenderer.getFoilBuffer}. We arm {@link ArtifactGlint} for the span of one
 * {@code ArtifactRenderer.render} so {@code FoilBufferMixin} fans the glint onto that method's return
 * only for artifacts, then flush the recorded glow silhouette at RETURN.
 *
 * The four base renderers (generic, belt, boot, glove) declare the {@code render} entry; the glowing
 * variants extend them and inherit it, so targeting the four bases covers every artifact. {@code getModel}
 * and per-hand glove handling differ per renderer, but wrapping the shared foil buffer sidesteps that -
 * whatever geometry is drawn is fanned. {@code remap = false}: the targets are Artifacts-owned.
 */
@Pseudo
@Mixin(targets = {
        "artifacts.client.item.renderer.GenericArtifactRenderer",
        "artifacts.client.item.renderer.BeltArtifactRenderer",
        "artifacts.client.item.renderer.BootArtifactRenderer",
        "artifacts.client.item.renderer.GloveArtifactRenderer"
}, remap = false)
public class ArtifactGlintMixin {

    private static final String RENDER =
            "render(Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/world/entity/LivingEntity;"
            + "I"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;"
            + "IFFFFFF)V";

    @Inject(method = RENDER, at = @At("HEAD"), require = 0, remap = false)
    private void cg_armArtifact(ItemStack stack, LivingEntity entity, int light, PoseStack pose,
            MultiBufferSource buffer, int overlay, float f0, float f1, float f2, float f3, float f4, float f5,
            CallbackInfo ci) {
        ArtifactGlint.arm(entity, stack);
    }

    @Inject(method = RENDER, at = @At("RETURN"), require = 0, remap = false)
    private void cg_flushArtifact(ItemStack stack, LivingEntity entity, int light, PoseStack pose,
            MultiBufferSource buffer, int overlay, float f0, float f1, float f2, float f3, float f4, float f5,
            CallbackInfo ci) {
        ArtifactGlint.flush();
    }
}
