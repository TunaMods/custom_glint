package net.tunamods.customglint.module.compat.epicknights;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * Compat-local render pipeline for Epic Knights armor decorations.
 *
 * The naive approach (just render parts with a glint render type) fails because:
 * - EQUAL depth test is invisible — EK's decoration depth and our glint depth differ by an FP
 *   epsilon despite using the same VIEW_OFFSET_Z_LAYERING. The same EQUAL works fine on
 *   {@link CustomGlint#forArmorGlint} for vanilla armor, so it's something about EK's pipeline.
 * - LEQUAL depth + no mask renders the full cuboid bounding box of each decoration ModelPart,
 *   bleeding through the transparent regions of the decoration texture — looks "huge" because
 *   the plume's cuboid is much larger than its visible feather shape.
 *
 * Solution: stencil pre-pass.
 * 1. Render parts with {@link CustomGlint#forOutline}(decorationTexture) — this is the outline
 *    shader with alpha-discard, so it skips transparent texels. Stencil op REPLACE writes 1
 *    only where the discard passes. Color/depth masks off so nothing visible is drawn.
 * 2. Render glint passes with stencil test EQUAL 1 — glint only appears on opaque decoration
 *    texels. LEQUAL depth handles occlusion (other geometry in front of the decoration).
 */
public final class EpicKnightsGlintRT extends RenderStateShard {
    private EpicKnightsGlintRT() { super("", () -> {}, () -> {}); }

    private static final Map<String, RenderType> CACHE  = new HashMap<>();
    private static final Map<String, float[]>    COLORS = new HashMap<>();

    /** Glint render type for the stencil-masked second pass. LEQUAL is safe because stencil masks to opaque pixels. */
    public static RenderType forDecorationGlint(CustomGlint.Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        CustomGlint.Layer layer = glint.layers()[layerIdx];
        if (CustomGlintRenderer.getTexture(layer.design()) == null) return null;
        String key = "ek-deco|" + layer.design() + "|" + Arrays.toString(layer.colors())
                + "|" + layer.speed() + "|" + layer.patternScale() + "|" + colorIdx;
        float[] holder = COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = CACHE.computeIfAbsent(key, k -> {
            ResourceLocation tex = layer.design();
            RenderType rt = RenderType.create(
                    "customglint:ek_decoration_glint|" + k.hashCode(),
                    DefaultVertexFormat.POSITION_TEX,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_GLINT_SHADER)
                            .setTextureState(new TextureStateShard(tex, false, false) {
                                @Override public void setupRenderState() {
                                    RenderSystem.setShaderTexture(0, CustomGlintRenderer.getTexture(tex));
                                    RenderSystem.setShaderColor(holder[0], holder[1], holder[2], holder[3]);
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard("customglint:ek_decoration_glint_texturing", () -> {
                                float phase = (float) colorIdx / Math.max(1, layer.colors().length);
                                long t = (long) (Util.getMillis() * 8.0 * layer.speed());
                                float f  = (float) (t % 110000L) / 110000.0F + phase;
                                float f1 = (float) (t % 30000L)  /  30000.0F;
                                Matrix4f m = new Matrix4f().translation(-f, f1, 0.0F);
                                m.rotateZ((float) (Math.PI / 3.0));
                                m.translate(f, -f1, 0.0F);
                                m.rotateZ((float) (Math.PI / 3.0));
                                m.translate(-f, f1, 0.0F);
                                m.rotateZ((float) (Math.PI / 3.0));
                                m.translate(f, f1, 0.0F);
                                m.scale(8.0f * layer.patternScale());
                                RenderSystem.setTextureMatrix(m);
                            }, RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            if (CustomGlintRenderer.fixedBufferRegistry != null)
                CustomGlintRenderer.fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            return rt;
        });
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(cached)) live.put(cached, new BufferBuilder(cached.bufferSize()));
        return cached;
    }

    /**
     * Renders glint for one decoration call's worth of ModelParts, masked to the decoration
     * texture's opaque pixels via stencil.
     */
    public static void applyDecorationGlint(PoseStack pose, MultiBufferSource buffer, int light,
            int overlay, ModelPart[] parts, ResourceLocation decorationTexture, CustomGlint.Data glint,
            boolean glowing) {
        if (!(buffer instanceof MultiBufferSource.BufferSource bs)) return;

        // Flush EK's pending decoration verts FIRST, before we touch GL masks. EK uses
        // RenderType.entityCutoutNoCull(decorationTexture) (verified via bytecode: m_110458_ →
        // entityCutoutNoCull) which isn't a fixedBuffer — its vertices live in BufferSource's
        // shared builder as lastState. If we don't flush them now, bs.getBuffer(stencilType)
        // below will implicitly flush them — but with colorMask=false already set, the deco
        // draws invisibly (vertices consumed, no color output).
        //
        // Use endLastBatch() rather than endBatch(entityCutoutNoCull(decoTex)): the latter
        // depends on RenderType instance equality between EK's call and ours, and we have
        // empirical evidence it isn't flushing reliably here. endLastBatch reads BufferSource's
        // own lastState record and flushes whatever shared type is actually pending.
        bs.endLastBatch();

        RenderType stencilType = CustomGlintRenderer.forOutlineStencilWrite(decorationTexture);
        Minecraft.getInstance().getMainRenderTarget().enableStencil();

        // ── Pass 1: stencil write at opaque decoration pixels ───────────────────────────
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilMask(0xFF);
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        GL11.glColorMask(false, false, false, false);
        GL11.glDepthMask(false);
        GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
        // dpfail=REPLACE: write stencil even when depth test fails (EK's decoration polygon
        // offset may put the stencil-pass fragments slightly behind already-written depth).
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_REPLACE, GL11.GL_REPLACE);

        VertexConsumer stencilVC = bs.getBuffer(stencilType);
        for (ModelPart part : parts) {
            part.render(pose, stencilVC, light, OverlayTexture.NO_OVERLAY, 1, 1, 1, 0);
        }
        bs.endBatch(stencilType);

        // ── Pass 2: glint where stencil == 1 ────────────────────────────────────────────
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);
        GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();
        List<VertexConsumer> list = new ArrayList<>();
        List<RenderType> types = new ArrayList<>();
        for (int li = 0; li < layers.length; li++) {
            int[] colors = layers[li].colors();
            if (layers[li].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    float a = ((colors[i] >> 24) & 0xFF) / 255.0f;
                    buf[0] = ((colors[i] >> 16) & 0xFF) / 255.0f * a;
                    buf[1] = ((colors[i] >>  8) & 0xFF) / 255.0f * a;
                    buf[2] = ( colors[i]        & 0xFF) / 255.0f * a;
                    buf[3] = 1.0f;
                    RenderType rt = forDecorationGlint(glint, li, buf, i);
                    if (rt != null) { types.add(rt); list.add(bs.getBuffer(rt)); }
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, li);
                float a = ((color >> 24) & 0xFF) / 255.0f;
                buf[0] = ((color >> 16) & 0xFF) / 255.0f * a;
                buf[1] = ((color >>  8) & 0xFF) / 255.0f * a;
                buf[2] = ( color        & 0xFF) / 255.0f * a;
                buf[3] = 1.0f;
                RenderType rt = forDecorationGlint(glint, li, buf, 0);
                if (rt != null) { types.add(rt); list.add(bs.getBuffer(rt)); }
            }
        }
        if (!list.isEmpty()) {
            VertexConsumer combined = list.size() == 1 ? list.get(0)
                    : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
            for (ModelPart part : parts) {
                part.render(pose, combined, light, overlay, 1.0f, 1.0f, 1.0f, 1.0f);
            }
            for (RenderType rt : types) bs.endBatch(rt);
        }

        // ── Pass 3: glow outline where stencil == 0 (only when glowing) ─────────────────
        // 4-direction translate halo: draw the decoration parts 4 (and 4 diagonal) times,
        // each translated by a small offset in entity-local XY. Each copy uses the original
        // scale and UVs so the outline shader's alpha-discard correctly traces the
        // decoration shape; the union of the translated copies forms a shape-following
        // halo around the original silhouette. The stencil==0 test gates everything to
        // pixels outside the original feather/plume silhouette so the halo never overlaps
        // the decoration itself.
        //
        // Why not scale 1.04× like doModelOutline? At that scale the geometry is bigger
        // but UVs are unchanged → ring pixels sample the decoration texture's edge UVs,
        // which for most EK decorations (feathers, plumes) are transparent padding →
        // alpha-discarded → no visible outline. Translating preserves UV→texel mapping so
        // alpha-discard keeps working.
        if (glowing) {
            int color = CustomGlintRenderer.glintOutlineColor(glint);
            float oR = ((color >> 16) & 0xFF) / 255.0f;
            float oG = ((color >>  8) & 0xFF) / 255.0f;
            float oB = ( color        & 0xFF) / 255.0f;

            GL11.glStencilFunc(GL11.GL_EQUAL, 0, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

            RenderType outlineRT = CustomGlintRenderer.forOutlineStencilTest(decorationTexture);
            VertexConsumer outlineVC = bs.getBuffer(outlineRT);
            final float t = 0.03f; // halo thickness in entity-local units (~ half a pixel-scale)
            final float d = t * 0.7071f; // diagonal component so 8 dirs are roughly equidistant
            float[][] offsets = {
                    {-t, 0, 0}, {t, 0, 0}, {0, -t, 0}, {0, t, 0},
                    {-d, -d, 0}, {-d, d, 0}, {d, -d, 0}, {d, d, 0},
            };
            for (float[] off : offsets) {
                pose.pushPose();
                pose.translate(off[0], off[1], off[2]);
                for (ModelPart part : parts) {
                    part.render(pose, outlineVC, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                            oR, oG, oB, 1.0f);
                }
                pose.popPose();
            }
            bs.endBatch(outlineRT);
        }

        GL11.glDisable(GL11.GL_STENCIL_TEST);
    }
}
