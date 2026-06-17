package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
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
import net.neoforged.neoforge.client.stencil.StencilOperation;
import net.neoforged.neoforge.client.stencil.StencilPerFaceTest;
import net.neoforged.neoforge.client.stencil.StencilTest;
import org.joml.Matrix4f;

import java.util.function.Supplier;

import static net.tunamods.customglint.CustomGlintMod.MOD_ID;

/**
 * Verified 26.1.2 render-pipeline foundation for the glint backend. Split out of
 * {@link CustomGlintRenderer} (which is mid-port) so the building blocks compile and can be reused
 * by every {@code forXxx} RenderType factory once the body rewrite lands.
 *
 * <p>The whole approach is documented and source-verified in {@code .claude/context/26/09-
 * renderer-api-verified.md}. In short, the 1.21.1 {@code RenderStateShard}/{@code CompositeState}
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
            Identifier.fromNamespaceAndPath(MOD_ID, "core/glint_color");

    /** Custom outline shader (vanilla core/rendertype_outline minus the ColorModulator dependency, so
     *  the glow ring draws opaque in the per-vertex colour on the MAIN target). Files at
     *  {@code assets/customglint/shaders/core/outline_color.{vsh,fsh}}. See 26/13-outlines.md. */
    public static final Identifier OUTLINE_COLOR_SHADER =
            Identifier.fromNamespaceAndPath(MOD_ID, "core/outline_color");

    /** Custom GUI item-outline FRAGMENT shader: emits the flat per-vertex colour masked by the bound
     *  texture's alpha (pairs with vanilla {@code core/position_tex_color.vsh}). Used by
     *  {@link #GUI_ITEM_OUTLINE} for the inventory-icon glow halo. File at
     *  {@code assets/customglint/shaders/core/gui_item_outline.fsh}. */
    public static final Identifier GUI_ITEM_OUTLINE_SHADER =
            Identifier.fromNamespaceAndPath(MOD_ID, "core/gui_item_outline");

    /**
     * GUI blit pipeline for the inventory-icon glow halo. Derived from vanilla {@code GUI_TEXTURED}
     * (2D pose, POSITION_TEX_COLOR, TRANSLUCENT blend, Sampler0) with only the fragment shader swapped
     * to {@link #GUI_ITEM_OUTLINE_SHADER}, so a {@code BlitRenderState} of the item's atlas slot draws
     * the slot's silhouette in a flat colour (the blit's vertex colour = the glow colour) rather than the
     * item texture tinted. {@code GuiRendererMixin} blits this at 1-px offsets behind the real item.
     */
    public static final RenderPipeline GUI_ITEM_OUTLINE = RenderPipelines.GUI_TEXTURED.toBuilder()
            .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/gui_item_outline"))
            .withFragmentShader(GUI_ITEM_OUTLINE_SHADER)
            .build();

    /** Silhouette shader for the isolated glow target. Like outline_color but writes a per-fragment
     *  distance falloff into the mask's alpha (read by post/glow_dilate_h as a 0..1 thickness scale) so
     *  the ring thins with distance like 1.21.1's geometry-dilated ring. Separate from
     *  OUTLINE_COLOR_SHADER (which the stencil paths share and need opaque alpha=1). */
    public static final Identifier GLOW_SILHOUETTE_SHADER =
            Identifier.fromNamespaceAndPath(MOD_ID, "core/glow_silhouette");
    /** Occlusion-disabled silhouette fragment shader (no DepthSampler / reconstruct) — the cheaper,
     *  show-through-walls variant selected by the client config. Shares the silhouette vertex shader. */
    public static final Identifier GLOW_SILHOUETTE_FLAT_SHADER =
            Identifier.fromNamespaceAndPath(MOD_ID, "core/glow_silhouette_flat");

    /**
     * Base colored-glint pipeline. Derived from vanilla {@link RenderPipelines#GLINT} so it inherits
     * the matrices/fog/globals uniform blocks, then: custom shader, vertex format with color,
     * {@code BlendFunction.GLINT} (carried over from the vanilla glint color target), depth test
     * EQUAL with no depth write, cull off. Depth/stencil/layering that vary per call site are
     * applied on the derived pipelines / setups, not here.
     */
    public static final RenderPipeline GLINT_COLOR = RenderPipelines.GLINT.toBuilder()
            .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/glint_color"))
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

    /** Clamp + NEAREST sampler for the alpha-mask / clamped textures (old CLAMP_TO_EDGE path). */
    public static GpuSampler clampNearestSampler() {
        return RenderSystem.getSamplerCache()
                .getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                        FilterMode.NEAREST, FilterMode.NEAREST, false);
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
        long t = (long) (net.minecraft.util.Util.getMillis() * 8.0 * speed);
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

    // ── Stencil outline pipeline pool ───────────────────────────────────────────
    //
    // The old code allocated a unique stencil slot (1..255) per outline call and mutated GL stencil
    // ref inside a per-RenderType LayeringStateShard runnable. Pipelines are immutable now, so each
    // slot value gets its own pre-built WRITE and TEST pipeline, cached lazily. See
    // 09-renderer-api-verified.md and the feedback_no_bit_per_item_stencil memory (per-slot
    // granularity, never per-bit).

    private static final RenderPipeline[] SLOT_WRITE_PIPE = new RenderPipeline[256];
    private static final RenderPipeline[] SLOT_TEST_PIPE  = new RenderPipeline[256];

    /** WRITE pipeline for {@code slot}: writes the slot value into the stencil buffer everywhere the
     *  geometry draws (dpfail=REPLACE preserved from the old code so the write succeeds even when the
     *  polygon-offset depth fails), no color, no depth write. */
    public static RenderPipeline stencilWritePipe(int slot) {
        RenderPipeline cached = SLOT_WRITE_PIPE[slot];
        if (cached != null) return cached;
        RenderPipeline p = GLINT_COLOR.toBuilder()
                .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/stencil_write_" + slot))
                .withColorTargetState(new ColorTargetState(java.util.Optional.empty(), ColorTargetState.WRITE_NONE))
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                .withStencilTest(new StencilTest(
                        new StencilPerFaceTest(StencilOperation.REPLACE, StencilOperation.REPLACE,
                                StencilOperation.REPLACE, CompareOp.ALWAYS_PASS),
                        0xFF, 0xFF, slot))
                .build();
        SLOT_WRITE_PIPE[slot] = p;
        return p;
    }

    /** TEST pipeline for {@code slot}: draws the dilated outline geometry only where the stencil
     *  still holds the slot value (EQUAL), without further writing it. */
    public static RenderPipeline stencilTestPipe(int slot) {
        RenderPipeline cached = SLOT_TEST_PIPE[slot];
        if (cached != null) return cached;
        RenderPipeline p = GLINT_COLOR.toBuilder()
                .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/stencil_test_" + slot))
                .withStencilTest(new StencilTest(
                        new StencilPerFaceTest(StencilOperation.KEEP, StencilOperation.KEEP,
                                StencilOperation.KEEP, CompareOp.EQUAL),
                        0xFF, 0x00, slot))
                .build();
        SLOT_TEST_PIPE[slot] = p;
        return p;
    }

    // ── Item-glint animation (forGlint variant) ─────────────────────────────────

    /** The item-glint scroll matrix (U-only scroll + atlas-calibrated scaleU/scaleV), a faithful
     *  port of the old {@code forGlint} TexturingStateShard. Differs from {@link #animationMatrix}
     *  (the armor/entity variant) which scrolls in both axes. */
    public static Matrix4f itemAnimationMatrix(double speed, float scaleU, float scaleV,
                                               float patternScale, int colorIdx, int colorCount) {
        float phase = (float) colorIdx / Math.max(1, colorCount);
        long t = (long) (net.minecraft.util.Util.getMillis() * 8.0 * speed);
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

    /** Entity-body depth-only fill (no color), to occlude back-side model outline through alpha gaps. */
    public static final RenderPipeline BODY_DEPTH_FILL = RenderPipelines.ENTITY_SOLID.toBuilder()
            .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/body_depth_fill"))
            .withCull(false)
            .withColorTargetState(new ColorTargetState(java.util.Optional.empty(), ColorTargetState.WRITE_NONE))
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
            .build();

    /** Builds an entity-textured RenderType (mask / body-fill) off the given pipeline. Entity models
     *  emit {@link DefaultVertexFormat#ENTITY} vertices, so these pipelines use that format. */
    public static RenderType entityMaskType(String name, RenderPipeline pipeline, Identifier texture) {
        RenderSetup setup = RenderSetup.builder(pipeline)
                .withTexture("Sampler0", texture)
                .useLightmap()
                .useOverlay()
                .bufferSize(256)
                .createRenderSetup();
        return RenderType.create(name, setup);
    }

    // ── Per-slot colored-outline pipelines (WRITE silhouette / TEST dilated ring) ─
    //
    // Derived from vanilla OUTLINE_NO_CULL (POSITION_TEX_COLOR + core/rendertype_outline, which
    // alpha-discards the bound texture so the ring follows the real silhouette). WRITE stamps the
    // slot value everywhere the geometry projects (no color); TEST draws the dilated ring only where
    // stencil != slot (so other objects' silhouettes don't block it), color on. Output forced to the
    // main target (replaces the old FORCE_MAIN_TARGET FBO juggling). Replaces SLOT_WRITE/SLOT_TEST.

    private static final RenderPipeline[] OUTLINE_WRITE_PIPE = new RenderPipeline[256];
    private static final RenderPipeline[] OUTLINE_TEST_PIPE  = new RenderPipeline[256];

    public static RenderPipeline outlineWritePipe(int slot, boolean polyOffset) {
        RenderPipeline cached = OUTLINE_WRITE_PIPE[slot];
        if (cached != null) return cached;
        RenderPipeline p = RenderPipelines.OUTLINE_NO_CULL.toBuilder()
                .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/outline_write_" + slot))
                .withVertexShader(OUTLINE_COLOR_SHADER)
                .withFragmentShader(OUTLINE_COLOR_SHADER)
                // Match armor_cutout_no_cull's polygon offset on the armor path so the silhouette
                // lands at the armor's depth; items pass false (their base draw has no offset).
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false,
                        polyOffset ? -1.0F : 0.0F, polyOffset ? -10.0F : 0.0F))
                .withColorTargetState(new ColorTargetState(java.util.Optional.empty(), ColorTargetState.WRITE_NONE))
                // WRITE stencil-op (dpfail = op applied on depth-fail). TRIED:
                //   - dpfail=REPLACE (full silhouette stamp) + TEST LEQUAL → ring formed, but dropped at
                //     angles and flickered/"got eaten up" on camera move. Turned out to be the TEST's
                //     slope-scaled depth bias, NOT the stamp.
                //   - dpfail=KEEP (stamp only depth-passing/visible fragments, with the -1,-10 WRITE bias
                //     to avoid self-z-fight) + TEST ALWAYS_PASS → occluded interior is unstamped, so the
                //     ALWAYS_PASS TEST FILLS it solid → glow bleeds through walls (torn-fill screenshot).
                //   - dpfail=KEEP + TEST LEQUAL+bias → still flickered (same TEST slope-bias root cause).
                // CURRENT: dpfail=REPLACE → full, depth-INDEPENDENT silhouette stamp, so the stamp never
                //   flickers. Occlusion is handled entirely by the TEST depth test below.
                .withStencilTest(new StencilTest(
                        new StencilPerFaceTest(StencilOperation.KEEP, StencilOperation.REPLACE,
                                StencilOperation.REPLACE, CompareOp.ALWAYS_PASS),
                        0xFF, 0xFF, slot))
                .build();
        OUTLINE_WRITE_PIPE[slot] = p;
        return p;
    }

    public static RenderPipeline outlineTestPipe(int slot) {
        RenderPipeline cached = OUTLINE_TEST_PIPE[slot];
        if (cached != null) return cached;
        RenderPipeline p = RenderPipelines.OUTLINE_NO_CULL.toBuilder()
                .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/outline_test_" + slot))
                .withVertexShader(OUTLINE_COLOR_SHADER)
                .withFragmentShader(OUTLINE_COLOR_SHADER)
                // TEST depth variants TRIED (occlusion vs the dilated ring band — see 26/13-outlines.md):
                //   - ALWAYS_PASS            → never drops, but glows THROUGH walls (no occlusion). [bad]
                //   - LEQUAL, no bias        → occludes, but band z-fights nearby background (ground at
                //                              the feet, terrain behind) and drops at certain angles. [bad]
                //   - LEQUAL + WRITE dpfail=KEEP + ALWAYS_PASS → occluded interior unstamped → TEST fills
                //                              it solid through walls (torn-fill screenshot). [bad]
                // LEQUAL + toward-camera depth bias so the ring wins z-fights vs coplanar/near background
                // (feet, ground) without glowing through real walls. depthBias = (scaleFactor, constant).
                //   - The main-target STENCIL also had to be cleared OUTSIDE the framegraph pass
                //     (clearStencilTexture throws inside a pass and the failure was swallowed); that clear
                //     was moved to RenderFrameEvent.Pre — see CustomGlintRenderer.clearMainStencil. Needed,
                //     but did NOT fix the flicker on its own.
                //   - TRIED bias (-1,-10) and (-3,-64), both with nonzero scaleFactor → NO change; the
                //     outline still got "eaten up" / went transparent as the camera moved.
                // ROOT CAUSE (that attempt): a NONZERO scaleFactor multiplies the polygon's DEPTH SLOPE.
                // At a silhouette edge the dilated model is nearly edge-on to the camera, so the slope is
                // huge and swings with camera angle → the effective bias is unstable → ring fragments flip
                // pass/fail per frame (the flicker). Tried scaleFactor = 0 + constant-only bias (-64).
                //   - TRIED scaleFactor=0 + constant=-64 → STILL flickered in-game (session 6): pieces of
                //     the ring fade in/out of transparency as the camera orbits. A constant-only bias does
                //     NOT fix it — the band's depth is unusable at the silhouette edge regardless of bias.
                //     Also tried the AfterOpaqueFeatures entry-point move (full committed depth) first — no
                //     effect on the flicker, confirming it's the band depth, not partial-depth timing.
                // DECISIVE COMPARISON vs working-1.21.1 (which occluded cleanly with this SAME LEQUAL +
                // 1.04 dilation): 1.21.1's TEST RT had (a) NO depth bias at all and (b) depth-WRITE ON
                // (default COLOR_DEPTH_WRITE mask, set via the RenderType, not the layering shard). The
                // 26.1 port had drifted to depth-write OFF + a bias. With NO_CULL the dilated shell draws
                // front AND back faces; with depth-write OFF neither self-occludes, so as the camera orbits
                // which face wins LEQUAL vs the scene flips → the flicker. NOW TRYING the faithful 1.21.1
                // config: LEQUAL, depth-WRITE ON, NO bias — the front band fragment writes its depth so the
                // back face is rejected and the ring stays stable.
                //   - TRIED LEQUAL + depth-WRITE ON + no bias (this config) → STILL flickered identically
                //     (session 7). So matching 1.21.1's TEST RT config is NOT sufficient on its own. Since
                //     1.21.1 had the SAME doModelOutline dilation + SAME LEQUAL and DID occlude cleanly, the
                //     divergence is environmental to 26.1, not in this pipeline. Traced the full vanilla
                //     render path (GameRenderer → LevelRenderer.addMainPass → framegraph): the framegraph
                //     "clear" pass clears COLOR+DEPTH only (GlCommandEncoder._clear(GL_COLOR|GL_DEPTH)) and
                //     NEVER the stencil, and the RenderFrameEvent.Pre stencil clear evidently wasn't taking.
                //     Stale per-frame stencil → the NOT_EQUAL test reads garbage → exactly the "only from
                //     certain angles / transparent spots that shift with view" signature. Tried moving the
                //     stencil clear into EntityGlintRender.drainBodyOutlines (at AfterOpaqueFeatures).
                //   - TRIED clearMainStencil() at the drain point → made it WORSE (session 7), reverted. The
                //     raw clearStencilTexture binds the scratch FBO then framebuffer 0 mid-framegraph-pass,
                //     corrupting the render. So the RenderFrameEvent.Pre clear was already working and stale
                //     stencil is RULED OUT. This config is kept (faithful 1.21.1). With depth bias, depth-
                //     write, entry-point timing, and stencil-clear all ruled out, the band-depth stencil ring
                //     is the confirmed dead end → parallel framegraph outline target (see 26/13-outlines.md).
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 0.0f, 0.0f))
                .withStencilTest(new StencilTest(
                        new StencilPerFaceTest(StencilOperation.KEEP, StencilOperation.KEEP,
                                StencilOperation.KEEP, CompareOp.NOT_EQUAL),
                        0xFF, 0x00, slot))
                .build();
        OUTLINE_TEST_PIPE[slot] = p;
        return p;
    }

    /** Builds a colored-outline RenderType (WRITE or TEST) for the given pipeline + texture. The
     *  texture is bound for the outline shader's alpha-discard; output forced to the main target. */
    public static RenderType outlineType(String name, RenderPipeline pipeline, Identifier texture) {
        RenderSetup setup = RenderSetup.builder(pipeline)
                .withTexture("Sampler0", texture)
                .setOutputTarget(OutputTarget.MAIN_TARGET)
                .bufferSize(1536)
                .createRenderSetup();
        return RenderType.create(name, setup);
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

    /** Occlusion-OFF glow-mask RenderType (no DepthSampler): the cheaper, show-through-walls variant
     *  ({@link #GLOW_MASK_FLAT_PIPE} / {@link #GLOW_SILHOUETTE_FLAT_SHADER}). */
    public static RenderType glowMaskTypeFlat(String name, Identifier texture) {
        RenderSetup setup = RenderSetup.builder(GLOW_MASK_FLAT_PIPE)
                .withTexture("Sampler0", texture)
                .setOutputTarget(OutputTarget.MAIN_TARGET)
                .bufferSize(1536)
                .createRenderSetup();
        return RenderType.create(name, setup);
    }

    // ── Isolated glow-outline pipeline (parallel framegraph outline target) ──────────────────────
    //
    // Replaces the dead-end per-pixel-depth stencil band ring for the entity body glow (see the
    // outlineTestPipe TRIED block + 26/13-outlines.md). Two stages:
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
            .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/glow_mask"))
            .withVertexShader(GLOW_SILHOUETTE_SHADER)
            .withFragmentShader(GLOW_SILHOUETTE_SHADER)
            .withSampler("DepthSampler")
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 0.0f, 0.0f))
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build();

    /** Occlusion-OFF mask pipeline: same vertex shader + LEQUAL early-Z, but the flat fragment shader
     *  with NO DepthSampler (cheaper; outlines show through walls). Selected when the client config
     *  outlineOcclusion = false. */
    public static final RenderPipeline GLOW_MASK_FLAT_PIPE = RenderPipelines.OUTLINE_NO_CULL.toBuilder()
            .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/glow_mask_flat"))
            .withVertexShader(GLOW_SILHOUETTE_SHADER)
            .withFragmentShader(GLOW_SILHOUETTE_FLAT_SHADER)
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 0.0f, 0.0f))
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build();

    /** Bilinear upscale of the half-res ring onto the main target (blended). */
    public static final RenderPipeline GLOW_UPSCALE_PIPE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/glow_upscale"))
            .withVertexShader(Identifier.fromNamespaceAndPath(MOD_ID, "core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath(MOD_ID, "post/glow_upscale"))
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
            .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/glow_outline_id"))
            .withVertexShader(Identifier.fromNamespaceAndPath(MOD_ID, "core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath(MOD_ID, "post/glow_outline_id"))
            .withSampler("MaskSampler")
            .withSampler("DepthSampler")   // full-res scene depth — distance-proportional ring thinning
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .build();

    // ── Shader-pack forward-pass outline (no stencil; additive shell behind the item) ───────────
    //
    // Under an active shader pack the stencil OUTLINE shader has no destination program, so this
    // path draws the dilated mesh as a flat additive silhouette via the universal core/position_color
    // (POSITION_COLOR) → gbuffers_basic mapping, pushed behind the item by a positive depth bias.
    // The old raw-GL front-face cull / glDepthRange / normal-push nuances have no declarative
    // equivalent and are collapsed here — this path is shader-pack-only and needs in-game tuning
    // (Iris/Sodium interplay). See 26/09-renderer-api-verified.md.

    /** Flat-colored forward outline (POSITION_COLOR), additive, pushed back, back-face culled. */
    public static final RenderPipeline FORWARD_OUTLINE = RenderPipelines.DEBUG_QUADS.toBuilder()
            .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/forward_outline"))
            .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 1.0F, 10.0F))
            .withCull(true)
            .build();

    /** Textured forward outline (POSITION_TEX_COLOR) for shell / sprite paths that alpha-discard. */
    public static final RenderPipeline FORWARD_OUTLINE_TEX = RenderPipelines.OUTLINE_NO_CULL.toBuilder()
            .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/forward_outline_tex"))
            .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 1.0F, 10.0F))
            .withCull(true)
            .build();

    /** Entity-format forward outline (for normal-push consumers that emit ENTITY vertices). */
    public static final RenderPipeline FORWARD_OUTLINE_ENTITY = RenderPipelines.ENTITY_CUTOUT.toBuilder()
            .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/forward_outline_entity"))
            .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 1.0F, 10.0F))
            .withCull(true)
            .build();

    /** Untextured forward-outline RenderType (POSITION_COLOR). */
    public static RenderType forwardOutline(String name) {
        return RenderType.create(name, RenderSetup.builder(FORWARD_OUTLINE)
                .setOutputTarget(OutputTarget.MAIN_TARGET).bufferSize(1536).createRenderSetup());
    }

    /** Textured forward-outline RenderType (POSITION_TEX_COLOR). */
    public static RenderType forwardOutlineTex(String name, Identifier texture) {
        return RenderType.create(name, RenderSetup.builder(FORWARD_OUTLINE_TEX)
                .withTexture("Sampler0", texture)
                .setOutputTarget(OutputTarget.MAIN_TARGET).bufferSize(1536).createRenderSetup());
    }

    /** Entity-format forward-outline RenderType (for normal-push consumers). */
    public static RenderType forwardOutlineEntity(String name, Identifier texture) {
        return RenderType.create(name, RenderSetup.builder(FORWARD_OUTLINE_ENTITY)
                .withTexture("Sampler0", texture).useLightmap().useOverlay()
                .setOutputTarget(OutputTarget.MAIN_TARGET).bufferSize(1536).createRenderSetup());
    }

    /** Plain (no-stencil) textured outline RenderType — for the GUI item-icon halos. Vanilla
     *  OUTLINE_NO_CULL outputs vertex color masked by the bound texture's alpha. */
    public static RenderType plainOutlineType(String name, Identifier texture) {
        return RenderType.create(name, RenderSetup.builder(RenderPipelines.OUTLINE_NO_CULL)
                .withTexture("Sampler0", texture)
                .setOutputTarget(OutputTarget.MAIN_TARGET).bufferSize(1536).createRenderSetup());
    }
}
