package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.CustomGlint.Layer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bakes the procedural chromatic slick into an ordinary RGBA texture, once per frame per distinct layer config.
 *
 * <p>Chromatic used to be the only design with no PNG, so it needed a private core shader, and Iris colour-masks
 * any non-{@code ExtendedShader} to nothing during the level pass (see
 * {@link CustomGlintRenderer#resolveGlintShader}). The post-composite replay, the scene-depth snapshot, the
 * in-shader depth compare and the alpha gate all existed to crawl back from that one fact, and they left chromatic
 * as the only thing in the frame the pack's TAA never resolved: a post-composite draw lands after the resolve, so
 * the slick kept its per-frame aliasing while the scene around it was filtered. It flickered and read as
 * z-fighting on every chromatic surface under any pack with TAA on (BSL, Bliss). Giving it a texture deletes that
 * premise. Once the slick is a texture, chromatic rides the same path as the other 55 designs: in-gbuffer, through
 * the pack's own GLINT program, TAA-resolved like everything else.
 *
 * <p>The bake runs a frame behind. It needs our private shader to actually draw, so it has to sit where Iris is
 * not overriding or masking ({@code RenderTickEvent.START}, before the level pass), but which configs are on
 * screen is only known once the level renders. So {@link #textureId} marks its config wanted and hands back the
 * texture as it stands, and {@link #bakeFrame} re-bakes everything wanted since the last frame. Content is one
 * frame stale, which is invisible on a slick that takes seconds to drift; a config seen for the first time draws
 * black for exactly one frame.
 *
 * <p>The baked texture is sampled under {@code patternScale} with {@code GL_REPEAT}, so the noise has to tile
 * without a seam. {@code chromatic.fsh} wraps its value-noise lattice at {@link #PERIOD} and steps octaves by integer
 * factors, so every octave's lattice divides the period and the whole field repeats over one UV unit. The flow
 * offsets are wrapped to the period here on the CPU in double precision: translating a periodic field keeps it
 * periodic, and wrapping keeps the coordinates the hash sees small (the old {@code GameTime * 5000} grew without
 * bound and quantised the hash as a session aged).
 */
public final class ChromaticTextureBaker {
    private ChromaticTextureBaker() {}

    /** Baked slick resolution. The design PNGs are far smaller; this is the noise, not a sprite, and it is what
     *  the octave detail has to survive being minified from. */
    private static final int SIZE = 256;

    /** Live baked textures. Each is an FBO + RGBA texture plus its mip chain, ~350 KB, so this is capped like the
     *  RT caches are. One config is a distinct (colours, speed, seed) triple, and every chromatic layer rolls its
     *  own seed, so an 8-layer chromatic trim is 8 configs and a fully-chromatic player is ~40. Sized to hold that
     *  plus headroom; past it the LRU churns rather than grows. */
    private static final int CACHE_CAP = 64;

    /** Noise lattice period, in cells, over one UV unit. Must equal {@code DENSITY} in {@code chromatic.fsh} for
     *  the bake to tile: the fsh spreads exactly this many cells across the texture and wraps the lattice here. */
    private static final float PERIOD = 7.0f;

    /** Flow rate in noise-cells per second per unit speed. Reproduces the old in-shader rate exactly:
     *  {@code GameTime} advanced 1/24000 per tick × 20 ticks/s × the old 5000 scale = 5000/1200 units/s. */
    private static final double FLOW_RATE = 5000.0 / 1200.0;

    /** Access-ordered LRU; eviction destroys the evicted target's FBO + texture rather than orphaning it. */
    private static final Map<String, Baked> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Baked> eldest) {
            if (size() <= CACHE_CAP) return false;
            eldest.getValue().destroy();
            return true;
        }
    };

    /** Configs drawn since the last {@link #bakeFrame}, with what it takes to (re)build them. Insertion-ordered so
     *  the bake order is stable. */
    private static final Map<String, Request> WANTED = new LinkedHashMap<>();

    private record Request(Layer layer, int[] colors) {}

    private static String key(int[] colors, double speed, int seed) {
        return CustomGlintRenderer.colorsKey(colors) + "|" + speed + "|" + seed;
    }

    /**
     * GL texture id of this layer's baked slick, marking the config wanted so {@link #bakeFrame} refreshes it next
     * frame. Called from the chromatic RenderType's {@code setupRenderState} on every draw, which is also what
     * keeps the entry warm in the LRU (the map is access-ordered, so the {@code get} counts).
     *
     * <p>Returns 0 (no texture bound, so the layer samples black and an additive glint contributes nothing) for
     * the one frame after a config is first seen. LOAD-BEARING that it does NOT create the target here: this runs
     * inside a RenderType's {@code setupRenderState}, i.e. mid-pass, and Forge's {@code RenderTarget.createBuffers}
     * ends in {@code unbindWrite()}, which binds framebuffer 0 rather than main, so the rest of the pass would render
     * to the default framebuffer. Creation belongs in {@link #bakeFrame}, which rebinds main in its finally.
     */
    public static int textureId(Layer layer, int[] colors) {
        String k = key(colors, layer.speed(), layer.seed());
        WANTED.put(k, new Request(layer, colors));
        Baked b = CACHE.get(k);
        return b != null ? b.colorTextureId() : 0;
    }

    /**
     * Re-bake every config drawn last frame at the current time. Hooked to {@code RenderTickEvent.START}: the
     * bake binds our own chromatic program, which only draws where Iris is not overriding shaders, and this runs
     * before the level pass where that gate is false.
     */
    public static void bakeFrame() {
        if (WANTED.isEmpty()) return;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();

        // Save every piece of global state the bake mutates and hand it back in the finally. This runs before the
        // frame's own rendering, so a leak here would bleed into the whole frame, not just one pass. Same class of
        // guard GlowOutlineRenderer.bindMainAndResetState and the old replay drain used.
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        // setProjectionMatrix also sets RenderSystem's global VertexSorting, and that is NOT GL state; it is read
        // later by MultiBufferSource.endBatch and applied by every sortOnUpload RenderType. It must be handed back
        // with the projection or the frame's item bases re-sort against our ortho and lose their index buffers.
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        boolean savedDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean savedCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        PoseStack mv = RenderSystem.getModelViewStack();
        mv.pushPose();
        try {
            mv.setIdentity();
            RenderSystem.applyModelViewMatrix();
            // Unit ortho: the quad spans 0..1 and UV0 spans 0..1, so one texel of the target is one texel of the
            // slick and the fsh's UV is the noise domain directly.
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0.0f, 1.0f, 0.0f, 1.0f, -1.0f, 1.0f),
                    VertexSorting.ORTHOGRAPHIC_Z);
            // Blend is deliberately NOT touched here. chromatic.json declares a ONE/ZERO replace blend, and
            // ShaderInstance.apply() applies it from inside the draw, after anything set here. Letting it own the
            // blend end to end leaves GL's enable state agreeing with BlendMode's static lastApplied, which is the
            // invariant a save/restore around it would break (see the LOAD-BEARING note in CustomGlintRenderer
            // above CHROMATIC_MODEL_UV_SCALE). Depth/cull off: a screen-space quad into a depth-less target.
            RenderSystem.disableDepthTest();
            RenderSystem.disableCull();
            // The previous frame's GUI pass can leave the mask off; a masked bake writes an empty slick.
            RenderSystem.colorMask(true, true, true, true);

            // Copy first: creating a Baked puts into CACHE, whose LRU eviction can destroy another entry, and the
            // iteration must not see that.
            List<Map.Entry<String, Request>> todo = new ArrayList<>(WANTED.entrySet());
            for (Map.Entry<String, Request> e : todo) {
                Baked b = CACHE.get(e.getKey());
                if (b == null) {
                    b = Baked.create(e.getValue().layer(), e.getValue().colors());
                    if (b == null) continue; // target creation failed; retried next time it is drawn
                    CACHE.put(e.getKey(), b);
                }
                b.bake();
            }
        } finally {
            WANTED.clear();
            mv.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProj, savedSorting);
            main.bindWrite(true);
            RenderSystem.setShaderTexture(0, 0);
            RenderSystem.setShaderTexture(1, 0);
            RenderSystem.resetTextureMatrix();
            if (savedCull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            if (savedDepthTest) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        }
    }

    /** Destroy every baked target. Hooked into the resource-reload cleanup alongside the glint texture cache. */
    public static void release() {
        for (Baked b : CACHE.values()) b.destroy();
        CACHE.clear();
        WANTED.clear();
    }

    /** Wraps {@code v} into {@code [0, period)} in double precision, so the float the shader sees stays small no
     *  matter how long the session has run. The field is periodic, so this is exactly identity on the result. */
    private static float wrap(double v, double period) {
        double m = v % period;
        return (float) (m < 0.0 ? m + period : m);
    }

    /** One config's target plus the payload its bake needs. */
    private static final class Baked {
        private final TextureTarget target;
        private final Layer layer;
        private final ResourceLocation palette;
        private final int colorCount;
        private final float seedX, seedY;

        private Baked(TextureTarget target, Layer layer, ResourceLocation palette, int colorCount) {
            this.target = target;
            this.layer = layer;
            this.palette = palette;
            this.colorCount = colorCount;
            // Decorrelates every trim's pattern ("no two look alike"). Same derivation the old vsh used, moved to
            // the CPU now that the seed no longer has to survive a round trip through a spare matrix slot.
            float s = (layer.seed() & 0xFFFF) / 256.0f;
            this.seedX = s * 3.1f;
            this.seedY = s * 6.7f;
        }

        static Baked create(Layer layer, int[] colors) {
            TextureTarget t;
            try {
                t = new TextureTarget(SIZE, SIZE, false, Minecraft.ON_OSX); // no depth: screen-space quad
            } catch (Throwable e) {
                return null;
            }
            t.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            Baked b = new Baked(t, layer, CustomGlintRenderer.getPaletteTexture(colors),
                    Math.min(colors.length, CustomGlint.MAX_COLORS_PER_LAYER));
            b.configureTexture();
            return b;
        }

        /** REPEAT so patternScale > 1 tiles, and a mipmap chain so a minified slick filters instead of aliasing.
         *  TextureTarget builds its colour texture CLAMP_TO_EDGE + LINEAR, so both must be re-set here. */
        private void configureTexture() {
            GlStateManager._bindTexture(target.getColorTextureId());
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GlStateManager._bindTexture(0);
            // Over the cleared texture, before anything samples it: a LINEAR_MIPMAP_LINEAR texture with no chain
            // allocated is incomplete and samples black.
            regenerateMipmaps();
        }

        private void regenerateMipmaps() {
            GlStateManager._bindTexture(target.getColorTextureId());
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            GlStateManager._bindTexture(0);
        }

        int colorTextureId() { return target.getColorTextureId(); }

        void bake() {
            target.bindWrite(true);
            RenderSystem.setShader(CustomGlintRenderer::chromaticShader);
            RenderSystem.setShaderTexture(0, 0); // the bake fsh reads only the palette, on Sampler1
            RenderSystem.setShaderTexture(1, palette);
            RenderSystem.setTextureMatrix(payload());

            BufferBuilder bb = Tesselator.getInstance().getBuilder();
            bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            bb.vertex(0.0, 0.0, 0.0).uv(0.0f, 0.0f).endVertex();
            bb.vertex(0.0, 1.0, 0.0).uv(0.0f, 1.0f).endVertex();
            bb.vertex(1.0, 1.0, 0.0).uv(1.0f, 1.0f).endVertex();
            bb.vertex(1.0, 0.0, 0.0).uv(1.0f, 0.0f).endVertex();
            BufferUploader.drawWithShader(bb.end());

            regenerateMipmaps();
        }

        /** TextureMat as a flat payload, NOT a transform: the bake's UV needs no scaling (the fsh spreads DENSITY
         *  cells across 0..1 itself), so all 16 slots are free and the vsh reads these as plain uniform elements.
         *  Layout mirrors the comment block in chromatic.vsh; keep the two in step. */
        private Matrix4f payload() {
            double t = (Util.getMillis() / 1000.0) * FLOW_RATE * Math.max(0.05, layer.speed());
            Matrix4f m = new Matrix4f();
            m.m00(seedX);                                  // [0][0] seed offset x
            m.m01(seedY);                                  // [0][1] seed offset y
            m.m10(wrap(t * 0.10, PERIOD));                 // [1][0] field-1 flow x
            m.m11(wrap(-t * 0.07, PERIOD));                // [1][1] field-1 flow y
            // Field 2 is sampled at 2× frequency, so its lattice period is 2× and its flow wraps against that.
            m.m20(wrap(-t * 0.06, PERIOD * 2.0));          // [2][0] field-2 flow x
            m.m21(wrap(-t * 0.04, PERIOD * 2.0));          // [2][1] field-2 flow y
            m.m30((float) colorCount);                     // [3][0] colour count (0 → rainbow fallback)
            m.m31(wrap(t * 0.02, 1.0));                    // [3][1] hue phase (the old t*0.02 colour drift)
            return m;
        }

        void destroy() { target.destroyBuffers(); }
    }
}
