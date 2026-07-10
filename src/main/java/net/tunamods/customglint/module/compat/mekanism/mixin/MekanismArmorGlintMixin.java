package net.tunamods.customglint.module.compat.mekanism.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.module.compat.mekanism.client.MekanismArmorGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Standalone-only Mekanism special-armor compat. Every {@code ICustomArmor} implementation (MekaSuit,
 * Jetpack, Free Runners and their armored variants, Scuba tank/mask) draws its geometry through
 * {@code ItemRenderer.getFoilBufferDirect}. We arm {@link MekanismArmorGlint} for the span of one
 * {@code ICustomArmor.render} so {@code FoilBufferMixin} fans the glint onto that method's return only
 * for Mekanism special armor — the plain Hazmat suit stays on the vanilla armor path our core mixin
 * already handles. RETURN flushes the recorded glow silhouette.
 *
 * All five share the {@code ICustomArmor.render} descriptor (vanilla types only), so one mixin covers
 * every piece. The armored Jetpack/Free Runner variants are static instances of the same classes, so
 * they are covered too. {@code remap = false}: the targets are Mekanism-owned.
 */
@Pseudo
@Mixin(targets = {
        "mekanism.client.render.armor.MekaSuitArmor",
        "mekanism.client.render.armor.JetpackArmor",
        "mekanism.client.render.armor.FreeRunnerArmor",
        "mekanism.client.render.armor.ScubaTankArmor",
        "mekanism.client.render.armor.ScubaMaskArmor"
}, remap = false)
public class MekanismArmorGlintMixin {

    private static final String RENDER =
            "render(Lnet/minecraft/client/model/HumanoidModel;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;"
            + "IIFZ"
            + "Lnet/minecraft/world/entity/LivingEntity;"
            + "Lnet/minecraft/world/item/ItemStack;)V";

    @Inject(method = RENDER, at = @At("HEAD"), require = 0, remap = false)
    private void cg_armMekaArmor(HumanoidModel<?> model, PoseStack pose, MultiBufferSource buffer, int light,
            int overlay, float partialTick, boolean glint, LivingEntity entity, ItemStack stack, CallbackInfo ci) {
        MekanismArmorGlint.arm(entity, stack);
    }

    @Inject(method = RENDER, at = @At("RETURN"), require = 0, remap = false)
    private void cg_flushMekaArmor(HumanoidModel<?> model, PoseStack pose, MultiBufferSource buffer, int light,
            int overlay, float partialTick, boolean glint, LivingEntity entity, ItemStack stack, CallbackInfo ci) {
        MekanismArmorGlint.flush();
    }
}
