package net.tunamods.customglint.module.compat.iceandfire.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone-only compat: death worm gauntlet BEWLR (RenderDeathWormGauntlet), same problem shape
 * as RenderTrollWeapon. One ModelDeathWormGauntlet, three variants (red/white/yellow) selected by
 * item identity → different textures, same combined geometry. We re-render MODEL with glint render
 * types at RETURN of renderByItem.
 *
 * IaF applies translate(0.5, 0.5, 0.5) before rendering MODEL; we re-apply it for glint.
 */
@Pseudo
@Mixin(targets = "com.github.alexthe666.iceandfire.client.render.tile.RenderDeathWormGauntlet", remap = false)
public class RenderDeathWormGauntletMixin {

    private static volatile Model CG_MODEL;

    private static Model cg_getModel() {
        Model m = CG_MODEL;
        if (m != null) return m;
        try {
            Class<?> cls = Class.forName("com.github.alexthe666.iceandfire.client.render.tile.RenderDeathWormGauntlet");
            Field f = cls.getDeclaredField("MODEL");
            f.setAccessible(true);
            CG_MODEL = (Model) f.get(null);
            return CG_MODEL;
        } catch (Throwable t) {
            return null;
        }
    }

    @Inject(method = "m_108829_", at = @At("RETURN"), require = 0)
    private void cg_apply_srg(ItemStack stack, ItemDisplayContext ctx, PoseStack pose,
            MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        cg_apply(stack, pose, buffer, light, overlay);
    }

    @Inject(method = "renderByItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("RETURN"), require = 0)
    private void cg_apply_named(ItemStack stack, ItemDisplayContext ctx, PoseStack pose,
            MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        cg_apply(stack, pose, buffer, light, overlay);
    }

    private static void cg_apply(ItemStack stack, PoseStack pose, MultiBufferSource buffer,
            int light, int overlay) {
        // See RenderTrollWeaponMixin: during the glow-outline capture re-render (IN_OUTLINE), skip our
        // glint draw so the silhouette traces this variant's real texture (IaF's own per-variant draw)
        // rather than recording a shared full-model-hull bucket under the design texture, which would
        // give all three gauntlet variants the same outline.
        if (CustomGlintRenderer.IN_OUTLINE.get()) return;

        Model model = cg_getModel();
        if (model == null) return;

        CustomGlint.Data glint = CustomGlint.readCached(stack);
        if (glint == null) return;

        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();
        List<VertexConsumer> list = new ArrayList<>();
        for (int li = 0; li < layers.length; li++) {
            // See RenderTrollWeaponMixin: CHROMATIC is procedural, so forGlint returns null and the layer
            // would draw nothing. isItem=false (3D BEWLR model); chromaticWorldBuffer defers the draw past
            // the scene composite under a shaderpack.
            if (CustomGlint.isChromatic(layers[li])) {
                RenderType crt = CustomGlintRenderer.forChromaticGlint(glint, li, false);
                if (crt != null) list.add(CustomGlintRenderer.chromaticWorldBuffer(buffer, crt));
                continue;
            }
            int[] colors = layers[li].colors();
            if (layers[li].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    CustomGlintRenderer.fillPremul(buf, colors[i]);
                    RenderType rt = CustomGlintRenderer.forGlint(glint, li, buf, false, i);
                    if (rt != null) list.add(buffer.getBuffer(rt));
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, li);
                CustomGlintRenderer.fillPremul(buf, color);
                RenderType rt = CustomGlintRenderer.forGlint(glint, li, buf, false, 0);
                if (rt != null) list.add(buffer.getBuffer(rt));
            }
        }
        if (!list.isEmpty()) {
            VertexConsumer combined = list.size() == 1 ? list.get(0)
                    : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
            pose.pushPose();
            try {
                pose.translate(0.5f, 0.5f, 0.5f);
                model.renderToBuffer(pose, combined, light, overlay, 1.0f, 1.0f, 1.0f, 1.0f);
            } finally {
                pose.popPose();
            }
        }
    }
}
