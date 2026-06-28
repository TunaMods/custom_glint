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
 * Standalone-only compat for Ice &amp; Fire Community Edition. CE's TrollWeaponRenderer implements
 * uranus's {@code DynamicItemRenderer} (method {@code render(...)}, not BEWLR {@code renderByItem})
 * and grabs its VertexConsumer via {@code MultiBufferSource.getBuffer} directly, so
 * ItemRendererMixin's getFoilBuffer hook never fires. At RETURN of {@code render} we re-render the
 * troll-weapon model with our glint render types, re-applying the {@code (0.5, -0.75, 0.5)} translate
 * IaF uses (inside a pushPose/popPose, so it's already unwound at RETURN).
 *
 * Glow outline is NOT drawn here: these items read as {@code isCustomRenderer()==true}, so the generic
 * texture-aware special-BEWLR capture in {@code ItemRendererMixin} re-renders them and traces each
 * weapon's real shape against its bound texture (the troll model holds every weapon's geometry; the
 * texture alpha carves which one shows). Only the glint is drawn here.
 *
 * CE's model is a non-static instance field {@code model} (was a static {@code MODEL}); we read it
 * reflectively from the renderer instance and cache the value (a single renderer instance exists).
 */
@Pseudo
@Mixin(targets = "com.iafenvoy.iceandfire.render.item.TrollWeaponRenderer", remap = false)
public class RenderTrollWeaponMixin {

    private static volatile Model CG_MODEL;

    /** Reads the model field (instance or static) off the renderer; {@code f.get(renderer)} handles both. */
    private static Model cg_getModel(Object renderer, String fieldName) {
        Model m = CG_MODEL;
        if (m != null) return m;
        try {
            Field f = renderer.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            CG_MODEL = (Model) f.get(renderer);
            return CG_MODEL;
        } catch (Throwable t) {
            return null;
        }
    }

    @Inject(method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("RETURN"), require = 0)
    private void cg_apply(ItemStack stack, ItemDisplayContext ctx, PoseStack pose,
            MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        if (CustomGlintRenderer.IN_OUTLINE.get()) return;
        Model model = cg_getModel(this, "model");
        if (model == null) return;

        CustomGlint.Data glint = CustomGlint.read(stack);
        if (glint == null) return;

        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();
        List<VertexConsumer> list = new ArrayList<>();
        for (int li = 0; li < layers.length; li++) {
            int[] colors = layers[li].colors().length == 0 ? CustomGlintRenderer.WHITE_COLOR : layers[li].colors();
            if (layers[li].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    float a = ((colors[i] >> 24) & 0xFF) / 255.0f;
                    buf[0] = ((colors[i] >> 16) & 0xFF) / 255.0f * a;
                    buf[1] = ((colors[i] >>  8) & 0xFF) / 255.0f * a;
                    buf[2] = ( colors[i]        & 0xFF) / 255.0f * a;
                    buf[3] = 1.0f;
                    RenderType rt = CustomGlintRenderer.forGlint(glint, li, buf, false, i);
                    if (rt != null) list.add(buffer.getBuffer(rt));
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, li);
                float a = ((color >> 24) & 0xFF) / 255.0f;
                buf[0] = ((color >> 16) & 0xFF) / 255.0f * a;
                buf[1] = ((color >>  8) & 0xFF) / 255.0f * a;
                buf[2] = ( color        & 0xFF) / 255.0f * a;
                buf[3] = 1.0f;
                RenderType rt = CustomGlintRenderer.forGlint(glint, li, buf, false, 0);
                if (rt != null) list.add(buffer.getBuffer(rt));
            }
        }
        if (list.isEmpty()) return;
        VertexConsumer combined = list.size() == 1 ? list.get(0)
                : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
        pose.pushPose();
        pose.translate(0.5f, -0.75f, 0.5f);
        model.renderToBuffer(pose, combined, light, overlay, 0xFFFFFFFF);
        pose.popPose();
    }
}
