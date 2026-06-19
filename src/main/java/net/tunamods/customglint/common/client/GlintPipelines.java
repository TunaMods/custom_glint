package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.joml.Matrix4f;

import java.util.function.Supplier;

import net.tunamods.customglint.common.CustomGlint;

/**
 * Verified 26.1.2 render-pipeline foundation for the glint backend. Split out of
 * {@link CustomGlintRenderer} (which is mid-port) so the building blocks compile and can be reused
 * by every {@code forXxx} RenderType factory once the body rewrite lands.
 *
 * <p>The 1.21.1 {@code RenderStateShard}/{@code CompositeState}
 * model is gone; GPU state is now an immutable {@link RenderPipeline} wrapped by a {@link RenderSetup}
 * and finalized with {@link RenderType#create(String, RenderSetup)}. Per-RenderType setup runnables
 * (the old {@code setShaderColor}/{@code setTextureMatrix} hooks) no longer exist — color rides the
 * vertex color (custom {@code customglint:core/glint_color} shader, POSITION_TEX_COLOR), animation
 * rides a per-draw {@link TextureTransform} supplier, and stencil state is baked into the pipeline as
 * a {@link StencilTest}.
 */
public final class GlintPipelines {
    private GlintPipelines() {}

    /** Custom colored-glint shader (vanilla core/glint plus a per-vertex Color so simultaneous
     *  multi-color layers batch in one draw). Files live at
     *  {@code assets/customglint/shaders/core/glint_color.{vsh,fsh}}. */
    public static final Identifier GLINT_COLOR_SHADER =
            CustomGlint.res("core/glint_color");

    /** Custom GUI item-outline FRAGMENT shader: emits the flat per-vertex colour masked by the bound
     *  texture's alpha (pairs with vanilla {@code core/position_tex_color.vsh}). Used by
     *  {@link #GUI_ITEM_OUTLINE} for the inventory-icon glow halo. File at
     *  {@code assets/customglint/shaders/core/gui_item_outline.fsh}. */
    public static final Identifier GUI_ITEM_OUTLINE_SHADER =
            CustomGlint.res("core/gui_item_outline");

    /**
     * GUI blit pipeline for the inventory-icon glow halo. Derived from vanilla {@code GUI_TEXTURED}
     * (2D pose, POSITION_TEX_COLOR, TRANSLUCENT blend, Sampler0) with only the fragment shader swapped
     * to {@link #GUI_ITEM_OUTLINE_SHADER}, so a {@code BlitRenderState} of the item's atlas slot draws
     * the slot's silhouette in a flat colour (the blit's vertex colour = the glow colour) rather than the
     * item texture tinted. {@code GuiRendererMixin} blits this at 1-px offsets behind the real item.
     */
    public static final RenderPipeline GUI_ITEM_OUTLINE = RenderPipelines.GUI_TEXTURED.toBuilder()
            .withLocation(CustomGlint.res("pipeline/gui_item_outline"))
            .withFragmentShader(GUI_ITEM_OUTLINE_SHADER)
            .build();

    /** Silhouette shader for the isolated glow target: writes a per-fragment distance falloff into the
     *  mask's alpha (read by the composite as a 0..1 thickness scale) so the ring thins with distance like
     *  the old geometry-dilated ring did. */
    public static final Identifier GLOW_SILHOUETTE_SHADER =
            CustomGlint.res("core/glow_silhouette");

    /**
     * Base colored-glint pipeline. Derived from vanilla {@link RenderPipelines#GLINT} so it inherits
     * the matrices/fog/globals uniform blocks, then: custom shader, vertex format with color,
     * {@code BlendFunction.GLINT} (carried over from the vanilla glint color target), depth test
     * EQUAL with no depth write, cull off. Depth/stencil/layering that vary per call site are
     * applied on the derived pipelines / setups, not here.
     */
    public static final RenderPipeline GLINT_COLOR = RenderPipelines.GLINT.toBuilder()
            .withLocation(CustomGlint.res("pipeline/glint_color"))
            .withVertexShader(GLINT_COLOR_SHADER)
            .withFragmentShader(GLINT_COLOR_SHADER)
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
            .withDepthStencilState(new DepthStencilState(CompareOp.EQUAL, false))
            .withCull(false)
            .build();

    /** REPEAT + LINEAR sampler for the scrolling, tiling grayscale glint texture. LINEAR (not NEAREST):
     *  the animation scrolls the design via a texture matrix, and with NEAREST a slow scroll only changes
     *  the sampled texel when it crosses a texel boundary — smooth in the high-res world view (the steps
     *  spread across many pixels) but visibly CHOPPY at low speed in the small GUI item slot (few pixels,
     *  synchronized steps). LINEAR interpolates between texels so sub-texel movement is smooth at any
     *  resolution and speed, matching vanilla's glint (which also filters). Wrap/filter live on the
     *  per-binding {@link GpuSampler} now (was {@code glTexParameteri} on the texture). */
    public static GpuSampler glintSampler() {
        return RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
    }

    /**
     * Builds a glint {@link RenderType} off {@link #GLINT_COLOR} for the given grayscale texture,
     * layering transform, and animation matrix supplier. Item vs armor vs horse/mount differ only in
     * the {@code layering} (item/mount: {@link LayeringTransform#NO_LAYERING}; humanoid/elytra armor:
     * {@link LayeringTransform#VIEW_OFFSET_Z_LAYERING} to match {@code armor_cutout_no_cull}).
     */
    public static RenderType glintType(String name, Identifier grayTexture,
                                       LayeringTransform layering, Supplier<Matrix4f> animation) {
        return glintType(name, GLINT_COLOR, grayTexture, layering, animation);
    }

    /** As {@link #glintType(String, Identifier, LayeringTransform, Supplier)} but on a specific glint
     *  pipeline. */
    public static RenderType glintType(String name, RenderPipeline pipeline, Identifier grayTexture,
                                       LayeringTransform layering, Supplier<Matrix4f> animation) {
        RenderSetup setup = RenderSetup.builder(pipeline)
                .withTexture("Sampler0", grayTexture, GlintPipelines::glintSampler)
                .setLayeringTransform(layering)
                .setTextureTransform(new TextureTransform(name + "|tex", animation))
                .bufferSize(256)
                .createRenderSetup();
        return RenderType.create(name, setup);
    }

    /**
     * The classic vanilla glint scroll/rotate matrix, parameterized by speed, pattern scale, and a
     * per-color phase. Faithful port of the old {@code TexturingStateShard} runnable; evaluated each
     * draw by the {@link TextureTransform} supplier so the animation keeps moving.
     */
    public static Matrix4f animationMatrix(double speed, float patternScale, int colorIdx, int colorCount) {
        float phase = (float) colorIdx / Math.max(1, colorCount);
        long t = (long) (Util.getMillis() * 8.0 * speed);
        float f  = (float) (t % 110000L) / 110000.0F + phase;
        float f1 = (float) (t % 30000L)  /  30000.0F;
        Matrix4f m = new Matrix4f().translation(-f, f1, 0.0F);
        m.rotateZ((float) (Math.PI / 3.0));
        m.translate(f, -f1, 0.0F);
        m.rotateZ((float) (Math.PI / 3.0));
        m.translate(-f, f1, 0.0F);
        m.rotateZ((float) (Math.PI / 3.0));
        m.translate(f, f1, 0.0F);
        m.scale(patternScale);
        return m;
    }

    // ── Item-glint animation (forGlint variant) ─────────────────────────────────

    /** The item-glint scroll matrix (U-only scroll + atlas-calibrated scaleU/scaleV), a faithful
     *  port of the old {@code forGlint} TexturingStateShard. Differs from {@link #animationMatrix}
     *  (the armor/entity variant) which scrolls in both axes. */
    public static Matrix4f itemAnimationMatrix(double speed, float scaleU, float scaleV,
                                               float patternScale, int colorIdx, int colorCount) {
        float phase = (float) colorIdx / Math.max(1, colorCount);
        long t = (long) (Util.getMillis() * 8.0 * speed);
        float f  = (float) (t % 110000L) / 110000.0F + phase;
        float f1 = (float) (t % 30000L)  /  30000.0F;
        Matrix4f m = new Matrix4f().translation(-f, 0.0F, 0.0F);
        m.rotateZ((float) (Math.PI / 3.0));
        m.translate(f, 0.0F, 0.0F);
        m.rotateZ((float) (Math.PI / 3.0));
        m.translate(-f, 0.0F, 0.0F);
        m.rotateZ((float) (Math.PI / 3.0));
        m.translate(f + f1, 0.0F, 0.0F);
        // Scale about the texture centre (0.5, 0.5), not the UV origin. Origin-pivot scaling translates the
        // visible pattern proportionally to the scale, so raising patternScale slid the design off the item
        // (most visible on a large 3D model like a shield — it drifted downward). T(0.5)·S·T(-0.5) keeps the
        // pattern centred as it grows.
        m.translate(0.5f, 0.5f, 0.0f);
        m.scale(scaleU * patternScale, scaleV * patternScale, 1.0f);
        m.translate(-0.5f, -0.5f, 0.0f);
        return m;
    }

    /** Combined glow-mask RenderType for {@link #GLOW_MASK_PIPE}: Sampler0 = the entity texture (drives
     *  the silhouette alpha-discard), DepthSampler = the full-res scene depth (bound by Identifier via a
     *  per-frame-updated holder — see CustomGlintRenderer.bindSceneDepth) so the shader can do the
     *  per-fragment occlusion test. Clamp/nearest on the depth so edge UVs don't wrap. */
    public static RenderType glowMaskType(String name, Identifier texture, Identifier sceneDepth) {
        RenderSetup setup = RenderSetup.builder(GLOW_MASK_PIPE)
                .withTexture("Sampler0", texture)
                .withTexture("DepthSampler", sceneDepth,
                        () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
                .setOutputTarget(OutputTarget.MAIN_TARGET)
                .bufferSize(1536)
                .createRenderSetup();
        return RenderType.create(name, setup);
    }

    // ── Isolated glow-outline pipeline (parallel framegraph outline target) ──────────────────────
    //
    // Replaces the dead-end per-pixel-depth stencil band ring the 1.20.1/1.21.1 builds used (the stencil
    // two-pass outline, removed in 26.1). That ring flickered/dropped at silhouette edges because the
    // dilated band's depth is unusable there regardless of bias — confirmed dead end. Two stages:
    //  1. GLOW_MASK_PIPE — ONE render of each glowing model into our own isolated half-res mask target,
    //     depth test ALWAYS_PASS (marks the whole outer shape). core/glow_silhouette decides occlusion
    //     per-fragment by sampling the full-res scene depth (DepthSampler) and encodes shape + visibility
    //     + distance thickness into alpha. Collapses the earlier two passes (full-shape + visible) and
    //     the separate depth-downsample pass into one, halving per-mob CPU emit and GPU silhouette fill.
    //  2. GLOW_COMPOSITE_ID_PIPE — one fullscreen pass that turns the mask into rings (post/glow_outline_id),
    //     per-object id-aware so adjacent/overlapping glows keep separate outlines instead of merging. The
    //     mask packs object id + visibility into alpha; the composite rings a pixel where a DIFFERENT
    //     object's visible silhouette is within reach.

    /** SINGLE combined-mask pipeline (replaces the old separate GLOW_SILHOUETTE + GLOW_SHAPE passes).
     *  One model render per mob produces both shape and per-fragment visibility, encoded in alpha by
     *  core/glow_silhouette — occlusion against the WORLD is decided in-shader by sampling the full-res
     *  scene depth (DepthSampler) and writing a visibility flag in alpha.
     *
     *  Depth state: LESS_THAN_OR_EQUAL + depth WRITE against the mask target's OWN depth (cleared to far
     *  each frame, separate from the sampled scene depth). This is purely an INTER-MOB early-Z optimization
     *  for the "cram space of glowing mobs" case: without it the old ALWAYS_PASS mask fully rasterized
     *  every mob behind every other mob (no early rejection) → enormous full-res overdraw. With it, only
     *  the front-most mob per pixel runs the fragment shader; the hidden ones are early-Z rejected. It does
     *  NOT change the result: a nearer mob only suppresses pixels that are interior to its own silhouette,
     *  which the composite discards anyway, and the nearer mob's world-visibility flag is the correct one
     *  there (anything behind it is occluded by it). DepthSampler is added on top of OUTLINE_NO_CULL's
     *  Sampler0; output target is overridden at draw time via RenderSystem.outputColor/DepthTextureOverride
     *  to our mask target. */
    public static final RenderPipeline GLOW_MASK_PIPE = RenderPipelines.OUTLINE_NO_CULL.toBuilder()
            .withLocation(CustomGlint.res("pipeline/glow_mask"))
            .withVertexShader(GLOW_SILHOUETTE_SHADER)
            .withFragmentShader(GLOW_SILHOUETTE_SHADER)
            .withSampler("DepthSampler")
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 0.0f, 0.0f))
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build();

    /** Bilinear upscale of the half-res ring onto the main target (blended). */
    public static final RenderPipeline GLOW_UPSCALE_PIPE = RenderPipeline.builder()
            .withLocation(CustomGlint.res("pipeline/glow_upscale"))
            .withVertexShader(CustomGlint.res("core/screenquad"))
            .withFragmentShader(CustomGlint.res("post/glow_upscale"))
            .withSampler("InSampler")
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .build();

    /** Per-object id-aware ring composite — single fullscreen pass (replaces the separable glow_dilate
     *  pair). Reads the mask (per-object id + visibility packed in alpha by core/glow_silhouette) and rings
     *  each object separately, so adjacent/overlapping glows don't fuse into one union ring. screenquad
     *  vertex + post/glow_outline_id fragment; driven via RenderPass.setPipeline, so it must be registered
     *  through RegisterRenderPipelinesEvent — see CustomGlintClientInit. */
    public static final RenderPipeline GLOW_COMPOSITE_ID_PIPE = RenderPipeline.builder()
            .withLocation(CustomGlint.res("pipeline/glow_outline_id"))
            .withVertexShader(CustomGlint.res("core/screenquad"))
            .withFragmentShader(CustomGlint.res("post/glow_outline_id"))
            .withSampler("MaskSampler")
            .withSampler("DepthSampler")   // full-res scene depth — distance-proportional ring thinning
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .build();
}
