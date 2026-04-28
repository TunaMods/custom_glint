package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.glint.CustomGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts {@link HumanoidArmorLayer#renderArmorPiece} at RETURN to draw a custom
 * animated glint on top of each armor piece for any living entity wearing an item with
 * a {@code custom_glint} NBT tag.
 *
 * <p>Injects at RETURN so vanilla armor rendering (including vanilla enchantment glint
 * for enchanted items) completes first; the custom glint layer is drawn on top via a
 * separate {@code model.renderToBuffer} call into the per-config fixed-buffer glint
 * {@link net.minecraft.client.renderer.RenderType}.
 *
 * <p>Dual SRG / named targets with {@code require=0} on both — same pattern as
 * {@link ItemRendererMixin}. Named targets use {@code remap=false} to match literally
 * in dev; SRG targets match in prod. One fires per environment, never both.
 */
@Mixin(HumanoidArmorLayer.class)
public class HumanoidArmorLayerMixin {

    private static final ThreadLocal<float[]> COLOR_BUF = ThreadLocal.withInitial(() -> new float[4]);

    /** SRG target: injects at RETURN of renderArmorPiece in obfuscated environments. */
    @Inject(method = "m_117118_", at = @At("RETURN"), require = 0)
    private void cg_armorGlint_srg(PoseStack pPoseStack, MultiBufferSource pBuffer,
            LivingEntity pLivingEntity, EquipmentSlot pSlot, int pPackedLight,
            HumanoidModel pModel, CallbackInfo ci) {
        applyArmorGlint(pPoseStack, pBuffer, pLivingEntity, pSlot, pPackedLight, pModel);
    }

    /** Named target: injects at RETURN of renderArmorPiece in dev/deobf environments. */
    @Inject(
        method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/model/HumanoidModel;)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void cg_armorGlint_named(PoseStack pPoseStack, MultiBufferSource pBuffer,
            LivingEntity pLivingEntity, EquipmentSlot pSlot, int pPackedLight,
            HumanoidModel pModel, CallbackInfo ci) {
        applyArmorGlint(pPoseStack, pBuffer, pLivingEntity, pSlot, pPackedLight, pModel);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static void applyArmorGlint(PoseStack poseStack, MultiBufferSource buffer,
            LivingEntity entity, EquipmentSlot slot, int packedLight, HumanoidModel model) {
        ItemStack stack = entity.getItemBySlot(slot);
        if (stack.isEmpty()) return;
        CustomGlint.Data glint = CustomGlint.read(stack);
        if (glint == null) return;

        int color = computeAnimatedColor(glint);
        float[] buf = COLOR_BUF.get();
        buf[0] = ((color >> 16) & 0xFF) / 255.0f;
        buf[1] = ((color >>  8) & 0xFF) / 255.0f;
        buf[2] = ( color        & 0xFF) / 255.0f;
        buf[3] = 1.0f;

        VertexConsumer glintConsumer = buffer.getBuffer(CustomGlint.forArmorGlint(glint, buf));
        model.renderToBuffer(poseStack, glintConsumer, packedLight, OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static int computeAnimatedColor(CustomGlint.Data glint) {
        int[] colors = glint.colors();
        if (colors.length == 1) return colors[0];

        Minecraft mc = Minecraft.getInstance();
        long gameTime = mc.level != null ? mc.level.getGameTime() : 0;

        float totalTicks = (20.0f * colors.length) / glint.speed();
        float t = (gameTime % Math.max(1L, (long) totalTicks)) / totalTicks * colors.length;
        int idx = (int) t % colors.length;

        if (!glint.interpolate()) return colors[idx];

        float frac = t - (int) t;
        int c1 = colors[idx];
        int c2 = colors[(idx + 1) % colors.length];
        int r = (int)(((c1 >> 16) & 0xFF) * (1 - frac) + ((c2 >> 16) & 0xFF) * frac);
        int g = (int)(((c1 >>  8) & 0xFF) * (1 - frac) + ((c2 >>  8) & 0xFF) * frac);
        int b = (int)((c1        & 0xFF) * (1 - frac) + (c2        & 0xFF) * frac);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
