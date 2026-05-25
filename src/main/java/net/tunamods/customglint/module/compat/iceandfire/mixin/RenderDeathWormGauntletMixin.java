package net.tunamods.customglint.module.compat.iceandfire.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone-only compat: death worm gauntlet BEWLR (RenderDeathWormGauntlet) — same problem shape
 * as RenderTrollWeapon. One ModelDeathWormGauntlet, three variants (red/white/yellow) selected by
 * item identity → different textures, same combined geometry. We re-render MODEL with glint render
 * types at RETURN of renderByItem, capture the variant texture via @Redirect on entityCutoutNoCull
 * (the gauntlet calls it three times in branches; whichever branch executes is what we capture),
 * and draw an outline using that texture so per-variant silhouettes match opaque texels.
 *
 * IaF applies translate(0.5, 0.5, 0.5) before rendering MODEL; we re-apply it for glint+outline.
 */
@Pseudo
@Mixin(targets = "com.github.alexthe666.iceandfire.client.render.tile.RenderDeathWormGauntlet", remap = false)
public class RenderDeathWormGauntletMixin {

    private static final ThreadLocal<ResourceLocation> CG_TEX = new ThreadLocal<>();
    private static volatile Model CG_MODEL;
    private static volatile boolean CG_REGISTERED = false;

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

    private static void cg_ensureRegistered() {
        if (CG_REGISTERED) return;
        CG_REGISTERED = true;
        try {
            CustomGlintRenderer.CUSTOM_OUTLINE_BEWLRS.add(
                Class.forName("com.github.alexthe666.iceandfire.client.render.tile.RenderDeathWormGauntlet"));
        } catch (Throwable ignored) {}
    }

    @Redirect(method = "m_108829_",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/RenderType;m_110452_(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"),
            require = 0)
    private RenderType cg_capTex_srg(ResourceLocation loc) {
        CG_TEX.set(loc);
        return RenderType.entityCutoutNoCull(loc);
    }

    @Redirect(method = "renderByItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/RenderType;entityCutoutNoCull(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"),
            require = 0)
    private RenderType cg_capTex_named(ResourceLocation loc) {
        CG_TEX.set(loc);
        return RenderType.entityCutoutNoCull(loc);
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
        cg_ensureRegistered();
        ResourceLocation tex = CG_TEX.get();
        CG_TEX.remove();
        Model model = cg_getModel();
        if (model == null) return;

        CustomGlint.Data glint = CustomGlint.read(stack);
        if (glint == null) return;

        if (!CustomGlintRenderer.IN_OUTLINE.get()) {
            CustomGlint.Layer[] layers = glint.layers();
            float[] buf = CustomGlintRenderer.COLOR_BUF.get();
            List<VertexConsumer> list = new ArrayList<>();
            for (int li = 0; li < layers.length; li++) {
                int[] colors = layers[li].colors();
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
            if (!list.isEmpty()) {
                VertexConsumer combined = list.size() == 1 ? list.get(0)
                        : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
                pose.pushPose();
                pose.translate(0.5f, 0.5f, 0.5f);
                model.renderToBuffer(pose, combined, light, overlay, 1.0f, 1.0f, 1.0f, 1.0f);
                pose.popPose();
            }
        }

        if (tex != null && !CustomGlintRenderer.IN_OUTLINE.get() && CustomGlint.isGlowing(stack)) {
            pose.pushPose();
            pose.translate(0.5f, 0.5f, 0.5f);
            // Stack overload (not Data) so glowColors NBT drives the outline color when set.
            CustomGlintRenderer.doBewlrOutline(pose, buffer, light, model, tex, stack);
            pose.popPose();
        }
    }
}
