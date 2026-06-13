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
 * Standalone-only compat: troll weapon BEWLR calls MultiBufferSource.getBuffer(RenderType) directly,
 * bypassing ItemRenderer.getFoilBuffer — so ItemRendererMixin never fires for glint. At RETURN of
 * renderByItem, re-renders MODEL with glint render types, and (if glowing) draws an outline using the
 * weapon's actual TEXTURE so each EnumTroll.Weapon variant gets its own opaque-texel silhouette.
 *
 * Cannot @Shadow MODEL: the field's runtime descriptor is Lcom/.../ModelTrollWeapon; (an IaF type we
 * cannot reference at compile time since IaF is not a compileOnly dep). Shadowing as Model fails the
 * descriptor match at field resolution. We resolve MODEL via reflection lazily instead.
 *
 * Texture for outline is captured via @Redirect on the RenderType.entityCutoutNoCull(ResourceLocation)
 * call inside renderByItem — the redirected method just calls through to the real one, side-channelling
 * the location into a ThreadLocal for the @Inject at RETURN to consume.
 */
@Pseudo
@Mixin(targets = "com.github.alexthe666.iceandfire.client.render.tile.RenderTrollWeapon", remap = false)
public class RenderTrollWeaponMixin {

    private static final ThreadLocal<ResourceLocation> CG_TEX = new ThreadLocal<>();
    private static volatile Model CG_MODEL;
    private static volatile boolean CG_REGISTERED = false;

    private static Model cg_getModel() {
        Model m = CG_MODEL;
        if (m != null) return m;
        try {
            Class<?> cls = Class.forName("com.github.alexthe666.iceandfire.client.render.tile.RenderTrollWeapon");
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
                Class.forName("com.github.alexthe666.iceandfire.client.render.tile.RenderTrollWeapon"));
        } catch (Throwable ignored) {}
    }

    // ── Capture the texture passed to RenderType.entityCutoutNoCull(loc) inside renderByItem.
    //    Dual SRG/named pair; the active class-level remap=false makes refmap-free, so SRG name
    //    works only in obfuscated runtime and named name works only in deobf.

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

    // ── Apply glint + outline at RETURN. Pose has been popped by IaF, so re-apply the
    //    (0.5, -0.75, 0.5) translate it uses before rendering MODEL.

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
        // Outline is no longer drawn here. doItemOutline draws the BEWLR glow outline using this
        // weapon's per-variant texture, resolved via BEWLR_OUTLINE_TEXTURE_RESOLVERS (registered in
        // IceAndFireClientCompat). The old doBewlrOutline path used white.png / untextured dilation,
        // which traced the full shared ModelTrollWeapon and covered the whole weapon in glow color.
        // Not registering CUSTOM_OUTLINE_BEWLRS lets doItemOutline handle it instead of skipping.
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
                pose.translate(0.5f, -0.75f, 0.5f);
                model.renderToBuffer(pose, combined, light, overlay, 1.0f, 1.0f, 1.0f, 1.0f);
                pose.popPose();
            }
        }
    }
}
