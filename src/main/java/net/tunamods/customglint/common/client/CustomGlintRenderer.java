package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import org.joml.Matrix4f;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static net.tunamods.customglint.CustomGlintMod.MOD_ID;

import net.tunamods.customglint.common.CustomGlint.Data;
import net.tunamods.customglint.common.CustomGlint.Layer;

/**
 * Client-only rendering backend. Split out of {@link CustomGlint} so that the data-API class
 * remains loadable on dedicated servers (where {@link RenderStateShard} and other client classes
 * are absent). Mods bundling the api jar should call render-pipeline methods through this class;
 * NBT/data API stays on {@link CustomGlint}.
 */
public final class CustomGlintRenderer extends RenderStateShard {

    // ── Texture cache ─────────────────────────────────────────────────────────

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceLocation, ResourceLocation> textureCache = new HashMap<>();


    public static ResourceLocation getTexture(ResourceLocation design) {
        if (textureCache.containsKey(design)) return textureCache.get(design);
        ResourceLocation result = generateTexture(design);
        textureCache.put(design, result);
        return result;
    }

    public static void clearTextures() {
        Minecraft mc = Minecraft.getInstance();
        for (ResourceLocation loc : textureCache.values())
            if (loc != null) mc.getTextureManager().release(loc);
        textureCache.clear();
        for (Runnable r : additionalReloadCleanup) {
            try { r.run(); } catch (Throwable t) {
                LOGGER.warn("[{}/CustomGlint] additional reload cleanup threw", MOD_ID, t);
            }
        }
        for (RenderType rt : BY_GLINT.values())             unregisterFixedBuffer(rt);
        for (RenderType rt : BY_ARMOR_GLINT.values())       unregisterFixedBuffer(rt);
        for (RenderType rt : BY_HORSE_ARMOR_GLINT.values()) unregisterFixedBuffer(rt);
        for (RenderType rt : BY_MOUNT_ARMOR_GLINT.values()) unregisterFixedBuffer(rt);
        for (RenderType rt : BY_MOUNT_ARMOR_MASK.values())  unregisterFixedBuffer(rt);
        for (RenderType rt : BY_CHROMATIC.values())         unregisterFixedBuffer(rt);
        BY_GLINT.clear();
        GLINT_FAST.clear();
        BY_ARMOR_GLINT.clear();
        BY_HORSE_ARMOR_GLINT.clear();
        BY_MOUNT_ARMOR_GLINT.clear();
        BY_MOUNT_ARMOR_MASK.clear();
        BY_CHROMATIC.clear();
        GLINT_COLORS.clear();
        for (ResourceLocation loc : paletteCache.values())
            if (loc != null) mc.getTextureManager().release(loc);
        paletteCache.clear();
        // Drop tagged-RT references too, else each reload orphans the previous generation of compat
        // (EK decoration) RenderTypes here even after their own caches are evicted - they'd never GC.
        SHADER_TT_TAGGED.clear();
        ChromaticTextureBaker.release(); // baked slicks are re-created on demand at the next draw
        if (whiteTex != null) { mc.getTextureManager().release(whiteTex); whiteTex = null; }
    }


    private static ResourceLocation generateTexture(ResourceLocation design) {
        Minecraft mc = Minecraft.getInstance();
        NativeImage source;
        try {
            var resource = mc.getResourceManager().getResource(design);
            if (resource.isEmpty()) return null;
            try (InputStream stream = resource.get().open()) {
                source = NativeImage.read(stream);
            }
        } catch (IOException e) {
            return null;
        }

        NativeImage gray = new NativeImage(source.getWidth(), source.getHeight(), false);
        try {
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    // NativeImage pixel format is ABGR stored as int: (A<<24)|(B<<16)|(G<<8)|R
                    int pixel = source.getPixelRGBA(x, y);
                    int r =  pixel        & 0xFF;
                    int g = (pixel >>  8) & 0xFF;
                    int b = (pixel >> 16) & 0xFF;
                    int a = (pixel >> 24) & 0xFF;
                    int lum = (r + g + b) / 3;
                    gray.setPixelRGBA(x, y, (a << 24) | (lum << 16) | (lum << 8) | lum);
                }
            }
        } finally {
            source.close();
        }

        String safePath = design.getNamespace() + "/" + design.getPath().replace('/', '_').replace('.', '_');
        ResourceLocation loc = CustomGlint.res("glint/" + safePath);
        DynamicTexture dt = new DynamicTexture(gray);
        mc.getTextureManager().register(loc, dt);
        dt.bind();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        return loc;
    }

    // ── Render types ──────────────────────────────────────────────────────────

    /** Assigned by RenderBuffersMixin on RenderBuffers construction; null until then. */
    public static SortedMap<RenderType, BufferBuilder> fixedBufferRegistry;
    public static final ThreadLocal<ItemStack> CURRENT_ITEM_STACK = new ThreadLocal<>();
    public static final ThreadLocal<float[]> COLOR_BUF = ThreadLocal.withInitial(() -> new float[4]);

    /** Premultiply an ARGB int into a 4-float shader color (rgb scaled by alpha, alpha slot forced to 1).
     *  Every glint fan-out (items, armor, entities, and the compat glue) fills {@link #COLOR_BUF} this way
     *  before looking up its RenderType. */
    public static void fillPremul(float[] buf, int argb) {
        float a = ((argb >> 24) & 0xFF) / 255.0f;
        buf[0] = ((argb >> 16) & 0xFF) / 255.0f * a;
        buf[1] = ((argb >>  8) & 0xFF) / 255.0f * a;
        buf[2] = ( argb        & 0xFF) / 255.0f * a;
        buf[3] = 1.0f;
    }

    /** Per-key mutable float[4] holders; RenderType lambdas close over these references and read them each frame. */
    private static final Map<String, float[]>    GLINT_COLORS          = new HashMap<>();
    private static final Map<String, RenderType> BY_GLINT              = new HashMap<>();
    private static final Map<String, RenderType> BY_ARMOR_GLINT        = boundedGlintCache();
    private static final Map<String, RenderType> BY_HORSE_ARMOR_GLINT  = boundedGlintCache();
    private static final Map<String, RenderType> BY_MOUNT_ARMOR_GLINT  = boundedGlintCache();
    private static final Map<ResourceLocation, RenderType> BY_MOUNT_ARMOR_MASK = new HashMap<>();

    /** The model-path glint RT caches (armor / horse / mount / entity) are keyed by the full glint config, so a
     *  scene throwing many distinct per-instance palettes at them (varied glints across many mobs, {@code /glint
     *  entity} spam) would otherwise grow one RenderType + native BufferBuilder per config for the whole session -
     *  the same accumulation {@link #BY_CHROMATIC} is capped for. Access-ordered LRU; eviction recycles the RT's
     *  builder ({@link #unregisterFixedBuffer}) and drops its colour holder. {@link #BY_GLINT} stays uncapped: its
     *  entries are pinned by the {@link #GLINT_FAST} memo, and held-item configs are inherently few. */
    private static final int GLINT_CACHE_CAP = 256;
    private static Map<String, RenderType> boundedGlintCache() {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, RenderType> eldest) {
                if (size() <= GLINT_CACHE_CAP) return false;
                unregisterFixedBuffer(eldest.getValue());
                GLINT_COLORS.remove(eldest.getKey());
                return true;
            }
        };
    }

    // ── Fixed-buffer registration + builder recycling ───────────────────────────
    // Each custom glint RenderType needs a dedicated BufferBuilder in the fixed-buffer map. In 1.20.1 a
    // BufferBuilder's backing buffer is a raw MemoryUtil.memAlloc (no Cleaner, no close()), so simply dropping
    // a builder when its RT is removed (chromatic LRU eviction, resource reload, EpicKnightsGlintRT) orphans
    // that off-heap allocation. Recycle removed builders through this pool and hand them back on the next
    // registration so native memory is bounded by the peak live RT count instead of growing for the session.
    // Render-thread only (same as the BY_* maps), so no synchronization.
    private static final ArrayDeque<BufferBuilder> RECYCLED_BUILDERS = new ArrayDeque<>();

    private static BufferBuilder obtainBuilder(int size) {
        BufferBuilder bb = RECYCLED_BUILDERS.poll();
        if (bb == null) return new BufferBuilder(size);
        bb.discard(); // clear any leftover state from its previous RT before reuse
        return bb;
    }

    /** Register {@code rt}'s dedicated builder into the captured registry and the live fixed-buffer map
     *  (usually the same instance), reusing a pooled builder when one is free. Idempotent. */
    public static void registerFixedBuffer(RenderType rt) {
        SortedMap<RenderType, BufferBuilder> live = null;
        try { live = Minecraft.getInstance().renderBuffers().fixedBuffers; } catch (Throwable ignored) {}
        if (fixedBufferRegistry != null && !fixedBufferRegistry.containsKey(rt))
            fixedBufferRegistry.put(rt, obtainBuilder(rt.bufferSize()));
        if (live != null && live != fixedBufferRegistry && !live.containsKey(rt))
            live.put(rt, obtainBuilder(rt.bufferSize()));
    }

    /** Remove {@code rt} from the fixed-buffer map(s) and recycle its builder(s) rather than orphaning the
     *  native buffer. Used by {@link #clearTextures}, the chromatic LRU eviction, and EpicKnightsGlintRT. */
    public static void unregisterFixedBuffer(RenderType rt) {
        if (fixedBufferRegistry != null) {
            BufferBuilder bb = fixedBufferRegistry.remove(rt);
            if (bb != null) RECYCLED_BUILDERS.offer(bb);
        }
        try {
            SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
            if (live != null && live != fixedBufferRegistry) {
                BufferBuilder bb = live.remove(rt);
                if (bb != null) RECYCLED_BUILDERS.offer(bb);
            }
        } catch (Throwable ignored) {}
    }

    /** Fast path for {@link #forGlint}: the resolved (RenderType, colour holder) memoised per immutable
     *  {@link Layer}, indexed by (colorIdx, isItem). The steady state then skips rebuilding the ~10-part
     *  String key (and its {@code Arrays.toString}) plus the map probes every frame for a cache that always
     *  hits after warmup - it just refreshes the holder the RT's shader closure reads. {@code readCached}
     *  hands back stable Layer instances; the resolved RT stays registered until {@link #clearTextures}
     *  (which clears this memo too), so the fast path needs no live-map probe. */
    private record GlintRT(RenderType rt, float[] holder) {}
    private static final Map<Layer, GlintRT[]> GLINT_FAST = new WeakHashMap<>();

    // ── Scroll direction → UV drift ─────────────────────────────────────────────
    // The glint motif drifts along one of eight compass directions (or freezes when STATIC). The pattern
    // *appears* to move opposite to the UV drift, so these are negated from screen direction; texture-V runs
    // DOWN so North (pattern up) drifts +V. This directional drift REPLACES the vanilla 3-rotation scroll for
    // every surface (default East = the historical horizontal scroll look). Shared immutable vectors avoid a
    // per-flush float[] allocation.
    private static final float SQ = 0.70710677f; // 1/√2
    private static final float[] SU_E  = {-1f, 0f},  SU_NE = {-SQ, SQ}, SU_N  = {0f, 1f},  SU_NW = {SQ, SQ},
                                 SU_W  = { 1f, 0f},  SU_SW = { SQ,-SQ}, SU_S  = {0f,-1f},  SU_SE = {-SQ,-SQ},
                                 SU_STATIC = {0f, 0f};
    private static final ThreadLocal<float[]> SCROLL_BUF = ThreadLocal.withInitial(() -> new float[2]);
    /** Reused per-thread texture matrix for the scroll/chromatic shards - these run in a RenderType setup
     *  lambda once per glint flush per frame, so a fresh Matrix4f each call is needless GC churn. {@code
     *  translation()} fully overwrites the matrix, so no explicit {@code identity()} is needed. */
    private static final ThreadLocal<Matrix4f> SCROLL_MAT = ThreadLocal.withInitial(Matrix4f::new);

    private static float[] scrollUnit(int dir) {
        return switch (dir) {
            case CustomGlint.SCROLL_E  -> SU_E;
            case CustomGlint.SCROLL_NE -> SU_NE;
            case CustomGlint.SCROLL_N  -> SU_N;
            case CustomGlint.SCROLL_NW -> SU_NW;
            case CustomGlint.SCROLL_W  -> SU_W;
            case CustomGlint.SCROLL_SW -> SU_SW;
            case CustomGlint.SCROLL_S  -> SU_S;
            case CustomGlint.SCROLL_SE -> SU_SE;
            default -> SU_STATIC; // SCROLL_STATIC
        };
    }

    /** Per-color UV drift for {@code layer} at the current wall-clock time. Animated dirs scroll along
     *  {@link #scrollUnit}; STATIC freezes at {@code scrollOffset} and fans simultaneous colors out by phase
     *  (else each color samples the same frozen UV and stacks exactly atop the others). */
    private static float[] scrollAmount(Layer layer, int colorIdx) {
        float[] out = SCROLL_BUF.get();
        float phase = (float) colorIdx / Math.max(1, layer.colors().length);
        if (layer.scrollDir() == CustomGlint.SCROLL_STATIC) {
            out[0] = layer.scrollOffset() + phase;
            out[1] = 0.0f;
        } else {
            // 110000/30000 ms periods and the 8× rate mirror vanilla's enchant-glint scroll (GlintTexturing).
            long t = (long) (Util.getMillis() * 8.0 * layer.speed());
            float f  = (float) (t % 110000L) / 110000.0F + phase;
            float f1 = (float) (t % 30000L)  /  30000.0F;
            float[] dir = scrollUnit(layer.scrollDir());
            out[0] = (f + f1) * dir[0];
            out[1] = (f + f1) * dir[1];
        }
        return out;
    }

    /** Item-glint directional-drift texture matrix (atlas-calibrated scale). Drift first, then scale about the
     *  texture centre so growing patternScale doesn't slide the motif off (centre-pivot). */
    private static void setItemScrollMatrix(Layer layer, int colorIdx, float scaleU, float scaleV) {
        float[] sc = scrollAmount(layer, colorIdx);
        Matrix4f m = SCROLL_MAT.get().translation(sc[0], sc[1], 0.0F);
        m.translate(0.5f, 0.5f, 0.0f);
        m.scale(scaleU * layer.patternScale(), scaleV * layer.patternScale(), 1.0f);
        m.translate(-0.5f, -0.5f, 0.0f);
        RenderSystem.setTextureMatrix(m);
    }

    /** Armor / horse / mount / entity directional-drift texture matrix (uniform model-UV scale). */
    private static void setModelScrollMatrix(Layer layer, int colorIdx, float scale) {
        float[] sc = scrollAmount(layer, colorIdx);
        Matrix4f m = SCROLL_MAT.get().translation(sc[0], sc[1], 0.0F);
        m.translate(0.5f, 0.5f, 0.0f);
        m.scale(scale * layer.patternScale());
        m.translate(-0.5f, -0.5f, 0.0f);
        RenderSystem.setTextureMatrix(m);
    }

    // ── Glint core shader ───────────────────────────────────────────────────────
    // The texture glints draw through OUR OWN copy of vanilla's rendertype_glint program instead of binding
    // vanilla's. The GLSL is identical (assets/customglint/shaders/core/glint.{vsh,fsh} are verbatim copies);
    // only the JSON's declared blend differs, and that difference is the entire reason this exists - see the
    // LOAD-BEARING note above CHROMATIC_MODEL_UV_SCALE.
    private static ShaderInstance glintShader;

    /** Shader shard for every texture-glint RenderType (items, armor, entities, horse/mount armor, and the
     *  compat builders). Resolved per draw by {@link #resolveGlintShader()}. */
    public static final RenderStateShard.ShaderStateShard GLINT_SHADER_SHARD =
            new RenderStateShard.ShaderStateShard(CustomGlintRenderer::resolveGlintShader);

    /**
     * LOAD-BEARING: which glint program to bind, decided per draw against Iris's own gate.
     *
     * <p>Iris/Oculus hangs BOTH of these on {@code ShaderRenderingPipeline.shouldOverrideShaders()}
     * (verified in oculus-mc1.20.1-1.8.0):
     * <ul>
     *   <li>{@code MixinGameRenderer.iris$overrideGlintShader} - while true, vanilla's
     *       {@code getRendertypeGlintShader()} returns the PACK's GLINT program (an {@code ExtendedShader}).</li>
     *   <li>{@code MixinShaderInstance.iris$lockDepthColorState} - while true, {@code ShaderInstance.apply()} on
     *       any shader that is NOT an {@code ExtendedShader}/{@code FallbackShader} calls
     *       {@code DepthColorStorage.disableDepthColor()}, i.e. {@code colorMask(false,false,false,false)}.</li>
     * </ul>
     *
     * <p>So the two are exactly complementary. While it is TRUE, our own program would be colour-masked to
     * nothing, and vanilla's accessor is the only way to get a program Iris will actually draw - so use that.
     * While it is FALSE (GUI/HUD and off-pack) nothing is overridden or masked, so use ours, and its declared
     * blend is what keeps the glint from filling the item.
     *
     * <p>Ask Iris the same question it asks itself rather than substituting our own {@link #isRenderingWorld()}
     * flag: the two do not agree at every phase, and a mismatch silently binds the wrong program's blend.
     */
    private static ShaderInstance resolveGlintShader() {
        if (glintShader == null || irisShouldOverrideShaders()) return GameRenderer.getRendertypeGlintShader();
        return glintShader;
    }

    // ── Procedural chromatic glint ──────────────────────────────────────────────
    // The chromatic design has no PNG, so a custom core shader (registered via RegisterShadersEvent) synthesises
    // an oil-slick from value-noise. That shader no longer draws the glint: ChromaticTextureBaker runs it once per
    // frame into an offscreen texture, and the glint RTs below sample that texture through the ordinary
    // GLINT_SHADER_SHARD, so chromatic is just design #56 as far as the render path is concerned. Up to 8 colours
    // ride a 1px palette strip the BAKE reads on Sampler1; the glint draw itself binds only the baked slick. One
    // RenderType per (colours, speed, scale, seed, surface) - a single draw, no per-colour fan-out.
    //
    // It used to bind its own program at draw time, which is the root of every workaround this file has since shed:
    // Iris colour-masks a non-ExtendedShader to nothing during the level pass (see resolveGlintShader), so the draw
    // had to be captured and replayed after the pack composited - and a post-composite draw is downstream of the
    // pack's TAA resolve, which is why chromatic was the only thing in the frame that flickered under BSL/Bliss.
    private static ShaderInstance chromaticShader;
    /** Each applied chromatic trim rolls a fresh seed, and the RenderType key includes that seed - so without
     *  a bound these accumulate one RenderType + BufferBuilder per distinct seed for the whole session. Cap
     *  with an access-ordered LRU whose eviction unregisters the evicted RT (recycling its builder via
     *  {@link #unregisterFixedBuffer}), mirroring {@link #clearTextures()}. */
    private static final int CHROMATIC_CACHE_CAP = 256;
    private static final Map<String, RenderType> BY_CHROMATIC =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, RenderType> eldest) {
                    if (size() <= CHROMATIC_CACHE_CAP) return false;
                    unregisterFixedBuffer(eldest.getValue()); // recycle the evicted RT's builder, don't orphan it
                    return true;
                }
            };

    /** 1px-tall palette strips (one RGBA texel per colour) bound to Sampler1, keyed by colour set. Cleared on
     *  resource reload alongside the texture cache. */
    private static final Map<String, ResourceLocation> paletteCache = new HashMap<>();
    private static ResourceLocation whiteTex; // 1×1 opaque-white dummy for Sampler0 (the shader never reads it)

    /** Mod-bus listener (hooked from {@link CustomGlintClientInit}): registers both core shaders - the texture
     *  glint (our copy of vanilla's program, differing only in its declared blend) and the procedural
     *  chromatic. Both are POSITION_TEX so they slot into the same fixed-buffer paths. */
    public static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            MOD_ID + ":glint",
                            DefaultVertexFormat.POSITION_TEX),
                    shader -> glintShader = shader);
        } catch (Exception e) {
            LOGGER.error("[{}/CustomGlint] failed to register glint shader", MOD_ID, e);
        }
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            MOD_ID + ":chromatic",
                            DefaultVertexFormat.POSITION_TEX),
                    shader -> chromaticShader = shader);
        } catch (Exception e) {
            LOGGER.error("[{}/CustomGlint] failed to register chromatic shader", MOD_ID, e);
        }
    }

    /** 1×1 opaque-white texture for Sampler0 (declared by the shader pipeline but unused by the fsh). */
    private static ResourceLocation getWhiteTexture() {
        if (whiteTex != null) return whiteTex;
        NativeImage img = new NativeImage(1, 1, false);
        img.setPixelRGBA(0, 0, 0xFFFFFFFF);
        DynamicTexture dt = new DynamicTexture(img);
        ResourceLocation loc = CustomGlint.res("chromatic_white");
        Minecraft.getInstance().getTextureManager().register(loc, dt);
        whiteTex = loc;
        return loc;
    }

    /** The chromatic BAKE program, for {@link ChromaticTextureBaker}. Never bound by a glint RenderType any more:
     *  chromatic draws through {@link #GLINT_SHADER_SHARD} like every other design, and this only ever draws the
     *  baker's offscreen quad. */
    static ShaderInstance chromaticShader() { return chromaticShader; }

    static String colorsKey(int[] colors) {
        StringBuilder sb = new StringBuilder(colors.length * 7);
        for (int c : colors) sb.append(Integer.toHexString(c)).append('_');
        return sb.length() == 0 ? "rainbow" : sb.toString();
    }

    /** Palette strip for Sampler1: width = max(1, colours), one opaque RGBA texel per colour (RGB only -
     *  the shader applies its own brightness). With no colours a 1px white keeps Sampler1 bound; the shader
     *  reads colour-count 0 and falls back to a full-spectrum rainbow, so the strip's contents go unused. */
    static ResourceLocation getPaletteTexture(int[] colors) {
        String key = colorsKey(colors);
        ResourceLocation existing = paletteCache.get(key);
        if (existing != null) return existing;
        int w = Math.max(1, colors.length);
        NativeImage img = new NativeImage(w, 1, false);
        for (int i = 0; i < w; i++) {
            int c = i < colors.length ? colors[i] : 0xFFFFFFFF;
            // NativeImage is ABGR little-endian; force opaque and keep RGB.
            int rgb = c & 0xFFFFFF;
            int abgr = 0xFF000000 | ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
            img.setPixelRGBA(i, 0, abgr);
        }
        DynamicTexture dt = new DynamicTexture(img);
        ResourceLocation loc = CustomGlint.res("chromatic_palette/" + key);
        Minecraft.getInstance().getTextureManager().register(loc, dt);
        paletteCache.put(key, loc);
        return loc;
    }

    /** A colourless chromatic trim (the creative-tab template) renders this neutral white→grey→dark slick
     *  instead of the full-spectrum rainbow fallback - the recognisable "greyscale" look. */
    private static final int[] CHROMATIC_EMPTY_PALETTE = { 0xFFFFFFFF, 0xFF8A8A8A, 0xFF3A3A3A };

    /** Resolve a chromatic layer's palette: its own colours, or the greyscale template when it has none. */
    public static int[] chromaticColors(int[] colors) {
        return colors.length == 0 ? CHROMATIC_EMPTY_PALETTE : colors;
    }

    /** Chromatic texture matrix: scales the baked slick about the texture centre exactly like the texture glint's
     *  scroll matrix does, with no drift - chromatic's motion is baked into the texture, one frame at a time, so
     *  the sampler only has to place it. The bake spreads {@code DENSITY} noise cells across the texture, so a
     *  surface scaled by S shows S x DENSITY cells - the same density the pre-bake shader produced from
     *  {@code noiseCoord * DENSITY}, which is why every caller's scaleU/scaleV carried over untouched. */
    private static void setChromaticMatrix(Layer layer, float scaleU, float scaleV) {
        Matrix4f m = SCROLL_MAT.get().translation(0.0f, 0.0f, 0.0f);
        m.translate(0.5f, 0.5f, 0.0f);
        m.scale(scaleU * layer.patternScale(), scaleV * layer.patternScale(), 1.0f);
        m.translate(-0.5f, -0.5f, 0.0f);
        RenderSystem.setTextureMatrix(m);
    }

    /** Builds (or returns cached) a chromatic RenderType for one layer. {@code layering} matches the surface
     *  the glint draws on (NO_LAYERING for items / horse / entity, VIEW_OFFSET_Z_LAYERING for worn armor),
     *  exactly mirroring the texture-glint depth setup so the EQUAL_DEPTH_TEST pass lines up. */
    private static RenderType chromaticRT(Layer layer, String tag, float scaleU, float scaleV,
                                          RenderStateShard.LayeringStateShard layering) {
        return chromaticRT(layer, tag, scaleU, scaleV, layering, VertexFormat.Mode.QUADS, false, false);
    }

    private static RenderType chromaticRT(Layer layer, String tag, float scaleU, float scaleV,
                                          RenderStateShard.LayeringStateShard layering, VertexFormat.Mode mode) {
        return chromaticRT(layer, tag, scaleU, scaleV, layering, mode, false, false);
    }

    private static RenderType chromaticRT(Layer layer, String tag, float scaleU, float scaleV,
                                          RenderStateShard.LayeringStateShard layering, VertexFormat.Mode mode,
                                          boolean lateForShaders) {
        return chromaticRT(layer, tag, scaleU, scaleV, layering, mode, lateForShaders, false);
    }

    // {@code lateBucketTag} tags the RT into the shader-pack LINES bucket (tagAsLateRenderForShaders), for a
    // surface that must flush AFTER the mod's own draw lands its depth. Epic Knights decorations are the only user:
    // their glint EQUAL-tests against a depth pre-pass, and without the tag FullyBuffered can schedule the glint
    // first, leaving EQUAL to compare against clear-depth. Distinct from lateForShaders, which is the slime shell's
    // OPAQUE_DECAL flag and also flips depth/write-mask/layering.
    private static RenderType chromaticRT(Layer layer, String tag, float scaleU, float scaleV,
                                          RenderStateShard.LayeringStateShard layering, VertexFormat.Mode mode,
                                          boolean lateForShaders, boolean lateBucketTag) {
        int[] colors = chromaticColors(layer.colors());
        final ResourceLocation white = getWhiteTexture();
        String key = tag + "|" + colorsKey(colors) + "|" + layer.speed() + "|" + layer.patternScale()
                + "|" + layer.seed() + "|" + scaleU + "|" + scaleV + "|" + mode + "|" + (lateForShaders ? "late" : "")
                + "|" + (lateBucketTag ? "lb" : "");
        RenderType cached = BY_CHROMATIC.computeIfAbsent(key, k -> {
            RenderType rt = RenderType.create(
                    MOD_ID + ":custom_chromatic|" + k.hashCode(),
                    DefaultVertexFormat.POSITION_TEX,
                    mode,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            // The SAME shard the 54 PNG designs use, which is the whole point: under a pack this
                            // resolves to the pack's own GLINT program, so chromatic draws in-gbuffer and the pack
                            // TAA-resolves it like everything else. Binding our private program here is what
                            // Iris colour-masked to nothing, and what the post-composite replay existed to undo.
                            .setShaderState(GLINT_SHADER_SHARD)
                            .setTextureState(new TextureStateShard(white, false, false) {
                                @Override public void setupRenderState() {
                                    // Resolved per draw rather than captured: this marks the config wanted for the
                                    // next bake and keeps it warm in the baker's LRU. Filtering and GL_REPEAT are
                                    // set on the baked texture itself (ChromaticTextureBaker.configureTexture), so
                                    // super's blur/mipmap binding is deliberately skipped.
                                    RenderSystem.setShaderTexture(0, ChromaticTextureBaker.textureId(layer, colors));
                                    // The slime translucent shell overdraws (dims) this additive glint under
                                    // shaders, so boost it on that path (SHELL_GLINT_SHADER_BOOST). Every other
                                    // path keeps 1 - the slick carries its own colour, so there is nothing to tint.
                                    float b = lateForShaders ? SHELL_GLINT_SHADER_BOOST : 1.0f;
                                    RenderSystem.setShaderColor(b, b, b, 1.0f);
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            // Translucent shell (slime) chromatic glint WRITES depth so its own front faces occlude
                            // its back faces (see forHorseArmorGlint); opaque chromatic writes colour only.
                            .setWriteMaskState(lateForShaders ? COLOR_DEPTH_WRITE : COLOR_WRITE)
                            .setCullState(NO_CULL)
                            // Translucent shell (slime) chromatic glint: LEQUAL against the stable opaque depth it
                            // tests under the OPAQUE_DECAL tag below (see forHorseArmorGlint for the full rationale).
                            .setDepthTestState(lateForShaders ? LEQUAL_DEPTH_TEST : EQUAL_DEPTH_TEST)
                            // Translucent shell: nudge depth toward the camera so the shell doesn't overdraw/dim
                            // the glint texels (see forHorseArmorGlint). Opaque chromatic keeps its layering.
                            .setLayeringState(lateForShaders ? VIEW_OFFSET_Z_LAYERING : layering)
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_chromatic_texturing|" + k.hashCode(),
                                    () -> setChromaticMatrix(layer, scaleU, scaleV), RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            return rt;
        });
        registerFixedBuffer(cached);
        if (lateForShaders) tagAsOpaqueDecalForShaders(cached);
        if (lateBucketTag) tagAsLateRenderForShaders(cached);
        return cached;
    }

    // LOAD-BEARING: every glint RenderType here pairs GLINT_TRANSPARENCY with a shader whose JSON declares
    // that SAME blend - customglint:glint for the texture glints, customglint:chromatic for these. Neither may
    // bind vanilla's rendertype_glint, and neither may drop its "blend" section. Why:
    //
    // ShaderInstance.apply() calls blend.apply() from INSIDE the draw (BufferUploader.drawWithShader), i.e.
    // AFTER setupRenderState ran the transparency shard - so the shader's declared blend OVERRIDES the
    // RenderType's. BlendMode.apply() then compares against a STATIC lastApplied and no-ops when it equals()
    // it, so the effective blend depends on which shader drew last, globally. Two ways that bites:
    //   - No "blend" section parses to BlendMode() with opaque=true, whose apply() calls disableBlend(). A
    //     glint drawn with blend OFF replaces the item surface instead of adding to it - the item reads as a
    //     solid glint-coloured silhouette with its texture gone. That was the "chromatic fills, but only if a
    //     texture glint drew first this frame" bug: the glint pinned lastApplied non-opaque, so chromatic's
    //     opaque mode flipped it back and disabled blend.
    //   - Vanilla's rendertype_glint declares srcalpha/1-srcalpha, so binding it overrides our additive with
    //     an alpha blend on some draws but not others, purely by draw order.
    // Declaring the shard's exact blend (blendFuncSeparate(SRC_COLOR, ONE, ZERO, ONE) -> srccolor/one +
    // srcalpha zero/one) on both shaders makes apply() either a no-op, leaving the shard's state, or a
    // re-apply of that identical state: same GL either way, in any draw order.
    //
    // Note lastApplied is static and NOT GL state, so a GL probe in a RenderType shard reads perfectly correct
    // blend right up to the broken draw. Do not go looking for this class of bug there.
    /** Noise UV scale for armor / elytra / horse-armor / entity-body chromatic. */
    private static final float CHROMATIC_MODEL_UV_SCALE = 8.0f;

    // Brightness compensation for the TRANSLUCENT-shell TEXTURE glint (slime outer shell) under an active
    // shaderpack. That glint flushes in the OPAQUE_DECAL bucket (before the shell) for a stable, flicker-free
    // depth reference; the shell then alpha-blends OVER it in the later GENERAL_TRANSPARENT pass and attenuates
    // the additive glint by the shell's opacity, so it reads dim. The VIEW_OFFSET_Z_LAYERING "punch-through"
    // that would occlude the shell at the glint texels is a no-op under Iris (it rewrites the gbuffer
    // projection, so the projection-matrix offset is ignored), and every depth-based way to occlude the shell
    // reintroduces flicker. So instead pre-boost the glint colour to counter the shell's overdraw and land near
    // the shaders-off brightness. Additive GLINT_TRANSPARENCY uses an SRC_COLOR src factor, so the on-screen
    // contribution scales ~colour^2; this factor is the multiply on the colour, not on the final contribution.
    // Applied only on the translucent shader path (lateForShaders); shaders-off uses the opaque EQUAL path and
    // never sees this. Tunable - verify in the shader test env.
    //
    // TRIED (do NOT retry): replacing this whole scheme with a stencil two-pass - mask the shell's visible
    // pixels in OPAQUE_DECAL, then draw the glint in the LINES bucket with NO_DEPTH_TEST + stencil EQUAL, so it
    // lands on top of the shell undimmed. Dropping the depth write cost the view-independent self-occlusion this
    // RT's COLOR_DEPTH_WRITE provides, and produced exactly what the note above predicts: every face visible
    // from every side, plus angle-dependent dropout. The stencil mark is not a substitute for the depth write.
    //
    // It only meaningfully lifts DARK colours: both call sites clamp (Math.min(1, holder*BOOST)), so any bright
    // colour saturates and is effectively unboosted. The CHROMATIC shell reads it on the same terms as the texture
    // shell now: both draw in-pass, in the OPAQUE_DECAL bucket, and both are overdrawn by the shell.
    private static final float SHELL_GLINT_SHADER_BOOST = 2.6f;

    /** Returns the buffer a chromatic layer's geometry should be fed into: a straight {@code src.getBuffer(crt)}
     *  on every path.
     *
     *  <p>It used to hand the geometry to a post-composite replay under an active pack, because a chromatic draw
     *  bound our own private program, which Iris colour-masks to nothing during the level pass. The slick is a
     *  baked texture now ({@link ChromaticTextureBaker}), so chromatic binds {@link #GLINT_SHADER_SHARD} like the
     *  54 PNG designs, resolves to the pack's own GLINT program, and draws in-gbuffer at full strength - with the
     *  pack's TAA resolving it, which the replay by construction could never do.
     *
     *  <p>Kept as the call site every fan-out already routes through (core mixins, EK, Mekanism, backpacks,
     *  EntityGlintRender) rather than churning them all to {@code getBuffer}, and as the one place to re-introduce
     *  special handling if a chromatic surface ever needs it again. */
    public static VertexConsumer chromaticWorldBuffer(MultiBufferSource src, RenderType crt) {
        return src.getBuffer(crt);
    }

    /** Flat item / 3D held-item chromatic glint (EQUAL depth, no polygon offset). isItem mirrors
     *  {@link #forGlint}'s atlas-calibrated scale so the slick density matches the texture glints. */
    public static RenderType forChromaticGlint(Data glint, int layerIdx, boolean isItem) {
        if (chromaticShader == null) return null;
        // Flat item: uvScale = atlasW/16 (per-axis atlasH/16) cancels the block-atlas sprite compression so a
        // 16px sprite spans patternScale UV units → identical GUI + world. 3D items (trident) keep uvScale 1.0.
        float scaleU = 1.0f, scaleV = 1.0f;
        if (isItem) {
            TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
            scaleU = atlas.width / 16.0f;
            scaleV = atlas.height / 16.0f;
        }
        return chromaticRT(glint.layers()[layerIdx], "item|" + isItem + "|L" + layerIdx, scaleU, scaleV, NO_LAYERING);
    }

    /** Worn-armor chromatic glint (EQUAL depth + VIEW_OFFSET_Z_LAYERING, matching armorCutoutNoCull). */
    public static RenderType forChromaticArmorGlint(Data glint, int layerIdx) {
        if (chromaticShader == null) return null;
        return chromaticRT(glint.layers()[layerIdx], "armor|L" + layerIdx, CHROMATIC_MODEL_UV_SCALE, CHROMATIC_MODEL_UV_SCALE,
                VIEW_OFFSET_Z_LAYERING);
    }

    /** Horse-armor / entity-body chromatic glint (EQUAL depth + NO_LAYERING, matching entityCutoutNoCull). */
    public static RenderType forChromaticEntityGlint(Data glint, int layerIdx) {
        if (chromaticShader == null) return null;
        return chromaticRT(glint.layers()[layerIdx], "entity|L" + layerIdx, CHROMATIC_MODEL_UV_SCALE, CHROMATIC_MODEL_UV_SCALE,
                NO_LAYERING);
    }

    /** Stencil-gated chromatic mount-armor glint (vanilla horse barding / IaF mounts). Chromatic's baked slick is
     *  one full-colour texture rather than the per-colour grayscale fan-out {@link #forMountArmorGlint} builds, so
     *  this stays its own factory - but it shares that one's stencil bit 0x80 EQUAL test, so the draw only lands on
     *  the armor texels {@link #forMountArmorStencilMask} marked. The mask is needed because this geometry is not
     *  this surface: HorseArmorLayer renders the whole horse model with the barding texture, so the armor mesh is
     *  also the saddle and body mesh, and only the barding texture's alpha tells them apart.
     *
     *  @param armorTex retained for call-site symmetry with {@link #forMountArmorStencilMask}; no longer read. It
     *                  used to be alpha-tested by the post-composite sibling, which stood in for the stencil bit
     *                  because that bit does not survive the composite. Chromatic draws in-pass now, so the stencil
     *                  itself does the gating again - the same way the texture glint always has. */
    public static RenderType forChromaticMountArmorGlint(Data glint, int layerIdx, ResourceLocation armorTex) {
        if (chromaticShader == null) return null;
        return chromaticRT(glint.layers()[layerIdx], "mount|L" + layerIdx, CHROMATIC_MODEL_UV_SCALE, CHROMATIC_MODEL_UV_SCALE,
                mountArmorGlintTestLayering());
    }

    /** Chromatic armor-decoration glint for the SHADER-PACK path (Epic Knights). Mirrors
     *  {@code EpicKnightsGlintRT.forDecorationGlintShader} exactly: EQUAL depth against the decoration depth
     *  pre-pass, no layering (the pre-pass writes raw projected depth, so both compare raw depths and match), and
     *  the LINES-bucket late tag so it flushes after the decoration's own depth lands under FullyBuffered. */
    public static RenderType forChromaticDecorationGlint(Data glint, int layerIdx) {
        if (chromaticShader == null) return null;
        return chromaticRT(glint.layers()[layerIdx], "ek-deco-sh|L" + layerIdx, CHROMATIC_MODEL_UV_SCALE,
                CHROMATIC_MODEL_UV_SCALE, NO_LAYERING, VertexFormat.Mode.QUADS, false, true);
    }

    /** Chromatic armor-decoration glint for the STENCIL paths (Epic Knights, shaders off / no shader mod).
     *  {@code layering} carries the decoration's stencil-slot EQUAL test; the caller caches the result and registers
     *  the fixed buffer, matching the texture-glint factories beside it in EK.
     *
     *  <p>Uncached, unlike every other chromatic RT. EK bakes a fresh {@link #nextStencilSlot()} into
     *  {@code layering} every frame, so the key moves with slot allocation order: through {@link #BY_CHROMATIC}, an
     *  LRU capped at {@link #CHROMATIC_CACHE_CAP} whose eviction recycles the RT's builder, that rebuilds
     *  RenderTypes every frame on the hot path.
     *
     *  <p>LEQUAL rather than the EQUAL the built-in chromatic RTs use: EK's decoration depth misses EQUAL by an FP
     *  epsilon, which is why the stencil exists at all (see EpicKnightsGlintRT's header). The stencil does the
     *  trimming here, not the depth func. */
    public static RenderType buildChromaticStencilGlint(Layer layer, String key,
                                                        RenderStateShard.LayeringStateShard layering) {
        if (chromaticShader == null) return null;
        int[] colors = chromaticColors(layer.colors());
        final ResourceLocation white = getWhiteTexture();
        return RenderType.create(
                MOD_ID + ":custom_chromatic_stencil|" + key.hashCode(),
                DefaultVertexFormat.POSITION_TEX,
                VertexFormat.Mode.QUADS,
                256,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(GLINT_SHADER_SHARD)
                        .setTextureState(new TextureStateShard(white, false, false) {
                            @Override public void setupRenderState() {
                                RenderSystem.setShaderTexture(0, ChromaticTextureBaker.textureId(layer, colors));
                                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                            }
                            @Override public void clearRenderState() {
                                super.clearRenderState();
                                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                            }
                        })
                        .setWriteMaskState(COLOR_WRITE)
                        .setCullState(NO_CULL)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setLayeringState(layering)
                        .setTransparencyState(GLINT_TRANSPARENCY)
                        .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_chromatic_stencil_texturing|" + key.hashCode(),
                                () -> setChromaticMatrix(layer, CHROMATIC_MODEL_UV_SCALE, CHROMATIC_MODEL_UV_SCALE),
                                RenderSystem::resetTextureMatrix))
                        .createCompositeState(false));
    }

    /** TRIANGLES-mode entity-body chromatic glint, for renderers that draw through a triangle-list
     *  RenderType (Epic Fight patched entity meshes). */
    public static RenderType forChromaticEntityGlintTriangles(Data glint, int layerIdx) {
        if (chromaticShader == null) return null;
        return chromaticRT(glint.layers()[layerIdx], "entity|L" + layerIdx, CHROMATIC_MODEL_UV_SCALE, CHROMATIC_MODEL_UV_SCALE,
                NO_LAYERING, VertexFormat.Mode.TRIANGLES);
    }

    /** Translucent-surface entity chromatic glint (slime outer shell). Now exactly its texture-glint twin
     *  {@link #forEntityGlintTranslucent}, one design apart: the same OPAQUE_DECAL bucket, LEQUAL against the
     *  stable opaque depth, depth write for self-occlusion, and the same {@link #SHELL_GLINT_SHADER_BOOST} to
     *  counter the shell's overdraw.
     *
     *  <p>It used to defer under a pack instead, because our private chromatic program landed in Iris's gbuffer and
     *  the pack's lighting dimmed it. The slick is a baked texture now, so this rides the pack's own GLINT program
     *  like the texture glint always did, and the whole deferred path (with the in-shader occlusion test its
     *  sibling needed, since a shell cannot depth-test against the composited buffer at all) is gone. Only
     *  EntityGlintRender picks the translucent variants, and only while a pack is active. */
    public static RenderType forChromaticEntityGlintTranslucent(Data glint, int layerIdx, boolean triangles) {
        if (chromaticShader == null) return null;
        return chromaticRT(glint.layers()[layerIdx], "entity|L" + layerIdx, CHROMATIC_MODEL_UV_SCALE, CHROMATIC_MODEL_UV_SCALE,
                NO_LAYERING, triangles ? VertexFormat.Mode.TRIANGLES : VertexFormat.Mode.QUADS, true);
    }

    public static RenderType forArmorGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        if (getTexture(layer.design()) == null) return null;
        String key = "armor|" + layer.design() + "|" + Arrays.toString(layer.colors()) + "|" + layer.speed() + "|" + layer.patternScale() + "|" + colorIdx + "|" + layerIdx + "|" + layer.scrollDir() + "|" + layer.scrollOffset();
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = BY_ARMOR_GLINT.computeIfAbsent(key, k -> {
            ResourceLocation tex = layer.design();
            RenderType rt = RenderType.create(
                    MOD_ID + ":custom_armor_glint|" + k.hashCode(),
                    DefaultVertexFormat.POSITION_TEX,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(GLINT_SHADER_SHARD)
                            .setTextureState(new TextureStateShard(tex, false, false) {
                                @Override public void setupRenderState() {
                                    RenderSystem.setShaderTexture(0, getTexture(tex));
                                    RenderSystem.setShaderColor(holder[0], holder[1], holder[2], holder[3]);
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            // armorCutoutNoCull draws with VIEW_OFFSET_Z_LAYERING (polygonOffset -1,-10),
                            // writing depth as D-ε. Match it: EQUAL depth + the same layering so the glint
                            // tests at D-ε too. LEQUAL is visible but bleeds through transparent cutout holes;
                            // EQUAL without the layering tests at raw D and the glint disappears.
                            .setDepthTestState(EQUAL_DEPTH_TEST)
                            .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_armor_glint_texturing",
                                    () -> setModelScrollMatrix(layer, colorIdx, 1.0f), RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            return rt;
        });
        registerFixedBuffer(cached);
        return cached;
    }

    /**
     * Entity body glint (pigs, cows, zombies, … anything rendered through entityCutoutNoCull).
     * Aliases {@link #forHorseArmorGlint}: same render-state requirements (EQUAL depth + NO_LAYERING),
     * since LivingEntityRenderer body draws have no polygon offset. Separate method only for caller clarity.
     */
    public static RenderType forEntityGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        return forHorseArmorGlint(glint, layerIdx, frameColor, colorIdx);
    }

    /** TRIANGLES-mode entity-body glint, for renderers that draw through a triangle-list RenderType
     *  (Epic Fight patched entity meshes). Same render state as {@link #forEntityGlint}; only the
     *  primitive mode differs, so a triangle vertex stream assembles correctly instead of shattering
     *  into quads. */
    public static RenderType forEntityGlintTriangles(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        return forHorseArmorGlint(glint, layerIdx, frameColor, colorIdx, VertexFormat.Mode.TRIANGLES, false);
    }

    /**
     * Entity glint for a TRANSLUCENT base surface (e.g. the slime's outer shell). Under a shader pack
     * (Oculus/Iris) translucent entity geometry is deferred to a later pass than our fixed glint buffer
     * would otherwise flush, so the glint draws first and the shell then paints over it → the glint vanishes
     * with shaders on (it's fine with shaders off, where everything flushes together). This variant is a
     * DISTINCT RT instance tagged into the shader mod's late (LINES) transparency bucket so it flushes AFTER
     * the shell - a no-op without a shader pack. Opaque entity glint keeps its own untagged instance so its
     * (working) ordering is unchanged.
     */
    public static RenderType forEntityGlintTranslucent(Data glint, int layerIdx, float[] frameColor, int colorIdx,
                                                       boolean triangles) {
        return forHorseArmorGlint(glint, layerIdx, frameColor, colorIdx,
                triangles ? VertexFormat.Mode.TRIANGLES : VertexFormat.Mode.QUADS, true);
    }

    // Horse armor uses entityCutoutNoCull (no polygon offset / no VIEW_OFFSET_Z_LAYERING).
    // forArmorGlint uses EQUAL + VIEW_OFFSET_Z_LAYERING - wrong offset → invisible on horses.
    // This variant keeps EQUAL + NO_LAYERING so depth matches, and scale 1.0 matches forArmorGlint visually.
    public static RenderType forHorseArmorGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        return forHorseArmorGlint(glint, layerIdx, frameColor, colorIdx, VertexFormat.Mode.QUADS, false);
    }

    private static RenderType forHorseArmorGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx,
                                                 VertexFormat.Mode mode, boolean lateForShaders) {
        Layer layer = glint.layers()[layerIdx];
        if (getTexture(layer.design()) == null) return null;
        String key = "horse|" + layer.design() + "|" + Arrays.toString(layer.colors()) + "|" + layer.speed() + "|" + layer.patternScale() + "|" + colorIdx + "|" + layerIdx + "|" + layer.scrollDir() + "|" + layer.scrollOffset() + "|" + mode + "|" + (lateForShaders ? "late" : "");
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = BY_HORSE_ARMOR_GLINT.computeIfAbsent(key, k -> {
            ResourceLocation tex = layer.design();
            RenderType rt = RenderType.create(
                    MOD_ID + ":custom_horse_armor_glint|" + k.hashCode(),
                    DefaultVertexFormat.POSITION_TEX,
                    mode,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(GLINT_SHADER_SHARD)
                            .setTextureState(new TextureStateShard(tex, false, false) {
                                @Override public void setupRenderState() {
                                    RenderSystem.setShaderTexture(0, getTexture(tex));
                                    if (lateForShaders) {
                                        // Translucent shell path: the shell overpaints and dims this additive glint
                                        // under shaders, so pre-boost the colour (see SHELL_GLINT_SHADER_BOOST).
                                        RenderSystem.setShaderColor(
                                                Math.min(1.0f, holder[0] * SHELL_GLINT_SHADER_BOOST),
                                                Math.min(1.0f, holder[1] * SHELL_GLINT_SHADER_BOOST),
                                                Math.min(1.0f, holder[2] * SHELL_GLINT_SHADER_BOOST),
                                                holder[3]);
                                    } else {
                                        RenderSystem.setShaderColor(holder[0], holder[1], holder[2], holder[3]);
                                    }
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            // Translucent shell WRITES depth (in the OPAQUE_DECAL pass) so its own front faces
                            // occlude its back faces on a nearest-wins basis - a view-independent self-occlusion the
                            // shell's Iris-re-sorted depth can't give (without it the far side's glint bleeds through
                            // the near side and faces flip in/out as the camera orbits). Opaque glint writes colour only.
                            .setWriteMaskState(lateForShaders ? COLOR_DEPTH_WRITE : COLOR_WRITE)
                            .setCullState(NO_CULL)
                            // Translucent base surface (slime outer shell = entity_translucent). The hard part under
                            // shaders is the DEPTH REFERENCE, not the glint's own geometry: if the glint flushes after the
                            // translucent shells (LINES bucket), it tests against the shell's depth, which Iris re-sorts
                            // per frame - so the test result flips as the camera moves (flicker) and the coplanar margin
                            // collapses with distance (far dropout). The RT is instead tagged into OPAQUE_DECAL (see the
                            // tag call below), which flushes right after the OPAQUE pass but BEFORE the translucent shells,
                            // so the depth buffer holds only stable opaque geometry (inner bodies, terrain, other slimes'
                            // cubes). LEQUAL against that: the shell's front faces (in front of all opaque) always pass;
                            // faces behind a nearer opaque body (another slime, this slime's own inner cube) are occluded.
                            // Stable reference → no flicker, correct at every range. Opaque entity glint keeps EQUAL.
                            .setDepthTestState(lateForShaders ? LEQUAL_DEPTH_TEST : EQUAL_DEPTH_TEST)
                            // Translucent shell: nudge the glint's written depth toward the camera so the shell's
                            // own LEQUAL depth test discards the shell fragments AT the glint texels - the glint
                            // punches through the shell (full brightness) instead of being painted over and dimmed
                            // by the later GENERAL_TRANSPARENT shell pass. Opaque glint keeps NO_LAYERING.
                            .setLayeringState(lateForShaders ? VIEW_OFFSET_Z_LAYERING : NO_LAYERING)
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_horse_armor_glint_texturing",
                                    () -> setModelScrollMatrix(layer, colorIdx, 1.0f), RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            return rt;
        });
        registerFixedBuffer(cached);
        if (lateForShaders) tagAsOpaqueDecalForShaders(cached);
        return cached;
    }

    /**
     * Stencil-mask write RT for IaF mount armor (dragon / hippogryph / hippocampus). The mount
     * body shares the same EntityModel as the armor layer, so an EQUAL_DEPTH glint RT (the
     * scheme {@link #forHorseArmorGlint} uses for vanilla horse armor) passes depth on every
     * face of the mount, not just the armor - vanilla horse armor avoids this because its
     * armor mesh is a separate model, but IaF reuses the parent mount model with an
     * alpha-cutout armor texture.
     *
     * This RT renders the parent model with the armor texture through entity-cutout's
     * alpha-discard shader and writes only stencil bit {@code 0x80} at opaque texels -
     * the LayeringStateShard does the GL state in setup/clear so timing is deterministic
     * across BufferSource flushes. Bit 0x80 is paired with {@link #forMountArmorGlint}'s
     * stencil EQUAL 0x80 test, constraining the glint draw to the same armor pixels.
     *
     * Bit isolation: outline slots {@link #nextStencilSlot} use values 1..255 and may set
     * bit 0x80 at silhouettes when their slot lands in 128..255. To stay independent, this
     * mask layering clears bit 0x80 across the framebuffer at setup (mask=0x80, glClear),
     * and writes/reads only that bit (stencilMask=0x80). Outline's lower bits stay intact.
     */
    public static RenderType forMountArmorStencilMask(ResourceLocation tex) {
        RenderType cached = BY_MOUNT_ARMOR_MASK.computeIfAbsent(tex, t -> {
            RenderType rt = RenderType.create(
                    MOD_ID + ":mount_armor_mask|" + t.toString().hashCode(),
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    256, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER)
                            .setTextureState(new TextureStateShard(t, false, false))
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(NO_WRITE)
                            .setLayeringState(mountArmorMaskLayering())
                            .createCompositeState(false));
            return rt;
        });
        registerFixedBuffer(cached);
        return cached;
    }

    private static RenderStateShard.LayeringStateShard mountArmorMaskLayering() {
        return new RenderStateShard.LayeringStateShard(
                "custom_glint_mount_armor_mask",
                () -> {
                    Minecraft.getInstance().getMainRenderTarget().enableStencil();
                    GL11.glEnable(GL11.GL_STENCIL_TEST);
                    GL11.glClearStencil(0);
                    if (pendingFrameStencilClear) {
                        GL11.glStencilMask(0xFF);
                        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
                        pendingFrameStencilClear = false;
                    } else {
                        // Clear only our reserved bit so outline slot bits remain intact.
                        GL11.glStencilMask(0x80);
                        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
                    }
                    GL11.glStencilMask(0x80);
                    GL11.glStencilFunc(GL11.GL_ALWAYS, 0x80, 0x80);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
                },
                () -> {
                    GL11.glStencilMask(0xFF);
                    GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
                    GL11.glDisable(GL11.GL_STENCIL_TEST);
                });
    }

    /**
     * Stencil-gated armor glint for IaF mounts. Same render state as
     * {@link #forHorseArmorGlint} (EQUAL depth, no polygon offset, glint transparency,
     * glint shader + scrolling matrix) but its layering shard tests stencil bit 0x80
     * EQUAL 1 instead of NO_LAYERING - only the texels marked by
     * {@link #forMountArmorStencilMask} draw.
     */
    public static RenderType forMountArmorGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        if (getTexture(layer.design()) == null) return null;
        String key = "mount|" + layer.design() + "|" + Arrays.toString(layer.colors()) + "|" + layer.speed() + "|" + layer.patternScale() + "|" + colorIdx + "|" + layerIdx + "|" + layer.scrollDir() + "|" + layer.scrollOffset();
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = BY_MOUNT_ARMOR_GLINT.computeIfAbsent(key, k -> {
            ResourceLocation tex = layer.design();
            RenderType rt = RenderType.create(
                    MOD_ID + ":custom_mount_armor_glint|" + k.hashCode(),
                    DefaultVertexFormat.POSITION_TEX,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(GLINT_SHADER_SHARD)
                            .setTextureState(new TextureStateShard(tex, false, false) {
                                @Override public void setupRenderState() {
                                    RenderSystem.setShaderTexture(0, getTexture(tex));
                                    RenderSystem.setShaderColor(holder[0], holder[1], holder[2], holder[3]);
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            .setDepthTestState(EQUAL_DEPTH_TEST)
                            .setLayeringState(mountArmorGlintTestLayering())
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_mount_armor_glint_texturing",
                                    () -> setModelScrollMatrix(layer, colorIdx, 1.0f), RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            return rt;
        });
        registerFixedBuffer(cached);
        return cached;
    }

    private static RenderStateShard.LayeringStateShard mountArmorGlintTestLayering() {
        return new RenderStateShard.LayeringStateShard(
                "custom_glint_mount_armor_glint_test",
                () -> {
                    Minecraft.getInstance().getMainRenderTarget().enableStencil();
                    GL11.glEnable(GL11.GL_STENCIL_TEST);
                    GL11.glStencilMask(0x00);
                    GL11.glStencilFunc(GL11.GL_EQUAL, 0x80, 0x80);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
                },
                () -> {
                    GL11.glStencilMask(0xFF);
                    GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
                    GL11.glDisable(GL11.GL_STENCIL_TEST);
                });
    }

    public static RenderType forGlint(Data glint, int layerIdx, float[] frameColor, boolean isItem, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        // Fast path (layer 0 only): skip rebuilding the String key + map probes every frame and just refresh
        // the colour holder the RT's shader closure reads. The memo is keyed by Layer VALUE, so it must NOT
        // serve layerIdx > 0 - two identical-value layers at different indices would resolve to the SAME
        // RenderType, and applyGlint would hand VertexMultiConsumer the same delegate twice ("Duplicate
        // delegates" under Sodium/Embeddium). Higher layers take the slow path, whose key includes layerIdx.
        boolean canMemo = layerIdx == 0 && colorIdx < CustomGlint.MAX_COLORS_PER_LAYER;
        int fastSlot = canMemo ? colorIdx * 2 + (isItem ? 1 : 0) : -1; // 2 slots per color: {non-item, item}
        if (canMemo) {
            GlintRT[] fast = GLINT_FAST.get(layer);
            if (fast != null && fast[fastSlot] != null) {
                GlintRT gr = fast[fastSlot];
                System.arraycopy(frameColor, 0, gr.holder(), 0, 4);
                return gr.rt();
            }
        }
        if (getTexture(layer.design()) == null) return null;
        String key = layer.design() + "|" + Arrays.toString(layer.colors()) + "|" + layer.speed() + "|" + layer.interpolate() + "|" + isItem + "|" + layer.patternScale() + "|" + colorIdx + "|" + layerIdx + "|" + layer.scrollDir() + "|" + layer.scrollOffset();
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = BY_GLINT.computeIfAbsent(key, k -> {
            // isItem=true → flat item model (sword, tool, etc.) → scale 8.0 matches vanilla glint().
            // isItem=false → 3D entity model (trident, etc.) → 1.0 gives visible pattern detail;
            // vanilla entityGlint() uses 0.16 but that tiles too infrequently for custom designs.
            // Atlas dims are read here (on cache miss only) - they're baked into the texturing shard below,
            // and the cache is cleared on resource reload if the atlas ever resizes. 1024x512 is the vanilla
            // reference block-atlas size the 8.0 scale is calibrated against, so designs hold aspect ratio
            // when another mod inflates the atlas to a non-square size.
            TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
            int atlasW = atlas.width;
            int atlasH = atlas.height;
            float scaleU = isItem ? (8.0f * atlasW / 1024.0f) : 1.0f;
            float scaleV = isItem ? (8.0f * atlasH / 512.0f) : 1.0f;
            ResourceLocation tex = layer.design();
            RenderType rt = RenderType.create(
                    MOD_ID + ":custom_glint|" + k.hashCode(),
                    DefaultVertexFormat.POSITION_TEX,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(GLINT_SHADER_SHARD)
                            .setTextureState(new TextureStateShard(tex, false, false) {
                                @Override public void setupRenderState() {
                                    RenderSystem.setShaderTexture(0, getTexture(tex));
                                    RenderSystem.setShaderColor(holder[0], holder[1], holder[2], holder[3]);
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            .setDepthTestState(EQUAL_DEPTH_TEST)
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_glint_texturing",
                                    () -> setItemScrollMatrix(layer, colorIdx, scaleU, scaleV),
                                    RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            return rt;
        });
        registerFixedBuffer(cached);
        if (canMemo) {
            GlintRT[] fast = GLINT_FAST.get(layer);
            // 16 slots = MAX_COLORS_PER_LAYER colors × 2 (non-item, item), indexed by fastSlot above.
            if (fast == null) { fast = new GlintRT[CustomGlint.MAX_COLORS_PER_LAYER * 2]; GLINT_FAST.put(layer, fast); }
            fast[fastSlot] = new GlintRT(cached, holder);
        }
        return cached;
    }

    public static int computeAnimatedColor(Data glint, int layerIdx) {
        return computeAnimatedColor(glint, layerIdx, 0.0f);
    }

    /** As {@link #computeAnimatedColor(Data, int)} but shifts the loop by {@code phaseFraction} of a full
     *  cycle (0.5 = half a loop). Used by the glow ring so it never shows the glint's own colour. */
    public static int computeAnimatedColor(Data glint, int layerIdx, float phaseFraction) {
        Layer layer = glint.layers()[layerIdx];
        return animateColors(layer.colors(), layer.speed(), layer.interpolate(), phaseFraction);
    }

    /** Animates through an int[] color array using game time. Default speed=1, interpolate=true. */
    public static int computeAnimatedGlowColor(int[] colors) {
        return computeAnimatedGlowColor(colors, 1.0f, true);
    }

    /** Animates through an int[] color array using game time, at {@code speed} (a higher speed cycles
     *  faster, mirroring the glint layer speed) and either blending between colors ({@code interpolate}) or
     *  stepping hard between them. */
    public static int computeAnimatedGlowColor(int[] colors, float speed, boolean interpolate) {
        return computeAnimatedGlowColor(colors, speed, interpolate, 0.0f);
    }

    /** As {@link #computeAnimatedGlowColor(int[], float, boolean)} but shifts the loop by
     *  {@code phaseFraction} of a full cycle (0.5 = half a loop). */
    public static int computeAnimatedGlowColor(int[] colors, float speed, boolean interpolate, float phaseFraction) {
        return animateColors(colors, speed, interpolate, phaseFraction);
    }

    /** Core colour-loop animator. Cycles {@code colors} on game time; {@code speed} scales the rate,
     *  {@code interpolate} blends vs. steps between colours, and {@code phaseFraction} shifts the whole
     *  loop by that fraction of a full cycle (0 = no shift, matching the original animation exactly). */
    private static int animateColors(int[] colors, float speed, boolean interpolate, float phaseFraction) {
        if (colors.length == 0) return 0xFFFFFFFF;
        if (colors.length == 1) return colors[0];
        if (!Float.isFinite(speed) || speed <= 0) speed = 1.0f;
        Minecraft mc = Minecraft.getInstance();
        long gameTime = mc.level != null ? mc.level.getGameTime() : 0;
        // One full color cycle spans 20 ticks (1 second) per color at speed 1.
        float totalTicks = (20.0f * colors.length) / speed;
        float t = (gameTime % Math.max(1L, (long) totalTicks)) / totalTicks * colors.length;
        // Shift by phaseFraction of the loop and wrap back into [0, length).
        t += phaseFraction * colors.length;
        t = ((t % colors.length) + colors.length) % colors.length;
        int idx = (int) t % colors.length;
        if (!interpolate) return colors[idx];
        float frac = t - (int) t;
        int c1 = colors[idx], c2 = colors[(idx + 1) % colors.length];
        int a = (int)(((c1 >> 24) & 0xFF) * (1 - frac) + ((c2 >> 24) & 0xFF) * frac);
        int r = (int)(((c1 >> 16) & 0xFF) * (1 - frac) + ((c2 >> 16) & 0xFF) * frac);
        int g = (int)(((c1 >>  8) & 0xFF) * (1 - frac) + ((c2 >>  8) & 0xFF) * frac);
        int b = (int)((c1         & 0xFF) * (1 - frac) + (c2         & 0xFF) * frac);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Guards re-entrance during the glow-outline special-item capture: set true while a special / 3D
     *  BEWLR item is re-rendered into the recording buffer (see {@code ItemRendererMixin}), so the
     *  re-render's nested {@code ItemRenderer.render} RETURN doesn't recurse into capture and
     *  {@code applyGlint} routes to the bare recording buffer instead of fanning glint layers. */
    public static final ThreadLocal<Boolean> IN_OUTLINE = ThreadLocal.withInitial(() -> false);

    /** Phase offset (fraction of a full colour loop) applied to the glow OUTLINE ring so it never shows the
     *  same colour as the glint's own animated tint at the same instant. Half a cycle = maximum contrast:
     *  when the inner glint is on one colour, the ring is on the opposite side of the loop. */
    public static final float GLOW_RING_PHASE_OFFSET = 0.5f;

    /** Glow-outline colour for an item: prefers the Glow Trim colours ({@code glowColors} NBT),
     *  falls back to glint layer 0, else white. Consumed by the post-process glow outline
     *  ({@link GlowOutlineRenderer}). The ring is shifted by {@link #GLOW_RING_PHASE_OFFSET} so it stays
     *  out of phase with the glint's inner tint (which animates at offset 0). */
    public static int resolveGlowColor(ItemStack stack) {
        int[] glow = CustomGlint.getGlowColors(stack);
        if (glow.length > 0)
            return computeAnimatedGlowColor(glow, CustomGlint.getGlowSpeed(stack),
                    CustomGlint.getGlowInterpolate(stack), GLOW_RING_PHASE_OFFSET);
        Data glint = CustomGlint.readCached(stack);
        return glint != null ? computeAnimatedColor(glint, 0, GLOW_RING_PHASE_OFFSET) : 0xFFFFFFFF;
    }

    /** Once-per-frame stencil-clear gate. Armed at frame start by {@code CustomGlintClientInit}'s
     *  RenderTickEvent.START; the first stencil-mask setup of the frame clears the stencil buffer
     *  and unsets it. Used by the mount-armor glint mask ({@link #forMountArmorStencilMask}) and the
     *  Epic Knights decoration glint mask, which constrain the glint to the armor/decoration shape. */
    public static volatile boolean pendingFrameStencilClear = true;

    /** Reload hooks appended by compat modules; invoked by {@link #clearTextures()} so each
     *  compat can release its own {@code DynamicTexture}s without {@code CustomGlintRenderer}
     *  needing to know about them. */
    public static final List<Runnable> additionalReloadCleanup = new CopyOnWriteArrayList<>();

    // Disables both color and depth writes - for the stencil mask pass that constrains glint to
    // the armor/decoration silhouette (mount armor, EK decorations).
    private static final RenderStateShard.WriteMaskStateShard NO_WRITE =
            new RenderStateShard.WriteMaskStateShard(false, false);

    // ── Per-glint stencil-slot pool ────────────────────────────────────────────
    // Allocates a unique stencil value (1..255) per glint-mask call so overlapping decoration
    // glints (multiple Epic Knights decorations on one figure) don't share a stencil silhouette.
    private static int stencilSlotCounter = 0;

    /** Reserve a unique stencil slot value (1..255) for this glint-mask call. Wraps if exceeded. */
    public static int nextStencilSlot() {
        stencilSlotCounter++;
        if (stencilSlotCounter > 255) stencilSlotCounter = 1;
        return stencilSlotCounter;
    }

    /** Frame-start reset; called from {@code CustomGlintClientInit}. */
    public static void resetStencilSlots() { stencilSlotCounter = 0; }

    // OutputStateShard that binds the main render target. Exposed for compat glint RTs (Epic Knights
    // decoration depth pre-write under a shader pack) that need the Oculus-no-pack-safe FBO binding.
    private static final int[] SAVED_FBO = new int[1];
    public static final RenderStateShard.OutputStateShard FORCE_MAIN_TARGET =
            new RenderStateShard.OutputStateShard("custom_glint_force_main_target",
                () -> {
                    SAVED_FBO[0] = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                    Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
                },
                () -> {
                    GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, SAVED_FBO[0]);
                });

    private CustomGlintRenderer() { super("", () -> {}, () -> {}); }

    // ── Shader-mod detection ──────────────────────────────────────────────────
    // Reflective so we don't need a compileOnly dep on the shader mod. Common shader
    // mods expose the same public detection surface, resolved once and cached as a
    // reflect.Method (see SHADER_GET_INSTANCE / SHADER_IS_IN_USE) and reused per call.
    //
    // Consumed by the glow-outline drain timing (LevelRendererMixin routes the world drain
    // through GlowOutlineRenderer.drainWorldShaderPack when a pack is active) and by the Epic
    // Knights decoration glint path, which swaps to a depth-prewrite + late-bucket shader RT
    // under a pack instead of the plain EQUAL-depth glint.
    private static volatile boolean SHADER_LOOKUP_DONE = false;
    private static volatile Method SHADER_GET_INSTANCE = null;
    private static volatile Method SHADER_IS_IN_USE = null;

    public static boolean isShaderPackActive() {
        if (!SHADER_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!SHADER_LOOKUP_DONE) {
                    try {
                        // Package path applies on Forge as well - do not change it.
                        Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                        SHADER_GET_INSTANCE = api.getMethod("getInstance");
                        SHADER_IS_IN_USE = api.getMethod("isShaderPackInUse");
                    } catch (Throwable ignored) {
                        SHADER_GET_INSTANCE = null;
                        SHADER_IS_IN_USE = null;
                    }
                    SHADER_LOOKUP_DONE = true;
                }
            }
        }
        if (SHADER_IS_IN_USE == null) return false;
        try {
            Object inst = SHADER_GET_INSTANCE.invoke(null);
            return (Boolean) SHADER_IS_IN_USE.invoke(inst);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True iff a shader mod is installed (detection API class resolved), regardless of whether
     * a shaderpack is currently active. The shader mod replaces the buffer-source pipeline
     * whenever it's loaded, so the Epic Knights decoration glint switches to its late-bucket
     * shader RT on mod presence, not just on an active pack.
     */
    public static boolean isShaderModInstalled() {
        if (!SHADER_LOOKUP_DONE) isShaderPackActive();
        return SHADER_IS_IN_USE != null;
    }

    // Iris/Oculus renders the first-person hand under its OWN captured gbuffer projection (the world /
    // sprint-FOV one), not the vanilla hand-FOV projection that RenderSystem.getProjectionMatrix() still
    // holds. The glow-outline FP drain must replay the item silhouette under that same projection or the
    // ring self-occludes (draws behind the item) and offsets on sprint. Read it reflectively - no compileOnly
    // Oculus dep; null when no shader mod is present or the class can't be resolved.
    private static volatile boolean IRIS_PROJ_LOOKUP_DONE = false;
    private static volatile Object IRIS_CRS_INSTANCE = null;
    private static volatile Method IRIS_GET_GBUFFER_PROJ = null;

    /** The projection Iris is currently drawing gbuffers (incl. the FP hand) with, or null when a shader mod
     *  isn't present / resolvable. The returned matrix is Iris's live instance - copy it, don't retain it. */
    public static org.joml.Matrix4f getShaderGbufferProjection() {
        if (!IRIS_PROJ_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!IRIS_PROJ_LOOKUP_DONE) {
                    try {
                        Class<?> crs = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
                        IRIS_CRS_INSTANCE = crs.getField("INSTANCE").get(null);
                        IRIS_GET_GBUFFER_PROJ = crs.getMethod("getGbufferProjection");
                    } catch (Throwable ignored) {
                        IRIS_CRS_INSTANCE = null;
                        IRIS_GET_GBUFFER_PROJ = null;
                    }
                    IRIS_PROJ_LOOKUP_DONE = true;
                }
            }
        }
        if (IRIS_GET_GBUFFER_PROJ == null || IRIS_CRS_INSTANCE == null) return null;
        try {
            Object m = IRIS_GET_GBUFFER_PROJ.invoke(IRIS_CRS_INSTANCE);
            return (m instanceof org.joml.Matrix4f) ? (org.joml.Matrix4f) m : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // Under a shader pack the first-person hand is not drawn via GameRenderer.renderItemInHand /
    // ItemInHandRenderer.renderHandsWithItems - Iris relocates it into its own HAND_SOLID / HAND_TRANSLUCENT
    // rendering phase inside the gbuffer pass, with a THIRD_PERSON display context. So our renderItemInHand /
    // renderHandsWithItems flags never arm and the held item misroutes to the world outline queue. Iris exposes
    // the current phase; when it is a HAND phase the item being drawn IS the FP held item. Reflective - no dep.
    private static volatile boolean IRIS_PHASE_LOOKUP_DONE = false;
    private static volatile Method IRIS_GET_PIPELINE_MANAGER = null;
    private static volatile Method IRIS_GET_PIPELINE_NULLABLE = null;
    private static volatile Method IRIS_GET_PHASE = null;
    private static volatile Object IRIS_PHASE_HAND_SOLID = null;
    private static volatile Object IRIS_PHASE_HAND_TRANSLUCENT = null;

    /** True while Iris is in its HAND_SOLID / HAND_TRANSLUCENT phase, i.e. drawing the first-person hand item
     *  under a shader pack. False when no shader mod is present / not resolvable / not the hand phase. */
    public static boolean isShaderHandPass() {
        if (!IRIS_PHASE_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!IRIS_PHASE_LOOKUP_DONE) {
                    try {
                        Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                        IRIS_GET_PIPELINE_MANAGER = iris.getMethod("getPipelineManager");
                        Class<?> pm = Class.forName("net.irisshaders.iris.pipeline.PipelineManager");
                        IRIS_GET_PIPELINE_NULLABLE = pm.getMethod("getPipelineNullable");
                        Class<?> wrp = Class.forName("net.irisshaders.iris.pipeline.WorldRenderingPipeline");
                        IRIS_GET_PHASE = wrp.getMethod("getPhase");
                        Class<?> phase = Class.forName("net.irisshaders.iris.pipeline.WorldRenderingPhase");
                        for (Object c : phase.getEnumConstants()) {
                            String n = ((Enum<?>) c).name();
                            if (n.equals("HAND_SOLID")) IRIS_PHASE_HAND_SOLID = c;
                            else if (n.equals("HAND_TRANSLUCENT")) IRIS_PHASE_HAND_TRANSLUCENT = c;
                        }
                    } catch (Throwable ignored) {
                        IRIS_GET_PIPELINE_MANAGER = null;
                        IRIS_GET_PHASE = null;
                    }
                    IRIS_PHASE_LOOKUP_DONE = true;
                }
            }
        }
        if (IRIS_GET_PIPELINE_MANAGER == null || IRIS_GET_PIPELINE_NULLABLE == null || IRIS_GET_PHASE == null)
            return false;
        try {
            Object pm = IRIS_GET_PIPELINE_MANAGER.invoke(null);
            if (pm == null) return false;
            Object pipe = IRIS_GET_PIPELINE_NULLABLE.invoke(pm);
            if (pipe == null) return false;
            Object ph = IRIS_GET_PHASE.invoke(pipe);
            return ph == IRIS_PHASE_HAND_SOLID || ph == IRIS_PHASE_HAND_TRANSLUCENT;
        } catch (Throwable t) {
            return false;
        }
    }

    // Under shader mods, every RenderType is mixed in to implement BlendingStateHolder with a
    // TransparencyType field (default GENERAL_TRANSPARENT). The batched FullyBufferedMultiBuffer-
    // Source flushes by TransparencyType in enum order (OPAQUE → OPAQUE_DECAL → GENERAL_TRANSPARENT
    // → DECAL → WATER_MASK → LINES). The geometry a glint draws over uses GENERAL_TRANSPARENT; if the
    // glint RT also sits there, the order within the same bucket is undefined → the glint can flush
    // before the base geometry → depth buffer empty when the glint draws → its EQUAL depth test can't
    // line up. Tagging the glint RT as LINES (last bucket) forces the shader mod to flush ALL base
    // geometry first, then the glint - depth ordering works in every camera context (1P / 3P / GROUND).
    // Used by the Epic Knights decoration depth-prewrite + glint RTs. Reflective to avoid compileOnly.
    private static volatile boolean SHADER_TT_LOOKUP_DONE = false;
    private static volatile Method SHADER_TT_SET = null;
    private static volatile Object SHADER_TT_LINES = null;
    private static final Set<RenderType> SHADER_TT_TAGGED =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static void tagAsLateRenderForShaders(RenderType rt) {
        if (rt == null) return;
        if (SHADER_TT_TAGGED.contains(rt)) return;
        if (!SHADER_TT_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!SHADER_TT_LOOKUP_DONE) {
                    try {
                        Class<?> ttCls = Class.forName("net.irisshaders.batchedentityrendering.impl.TransparencyType");
                        Class<?> bshCls = Class.forName("net.irisshaders.batchedentityrendering.impl.BlendingStateHolder");
                        SHADER_TT_SET = bshCls.getMethod("setTransparencyType", ttCls);
                        @SuppressWarnings({"rawtypes", "unchecked"})
                        Object lines = Enum.valueOf((Class<? extends Enum>) ttCls, "LINES");
                        SHADER_TT_LINES = lines;
                    } catch (Throwable ignored) {
                        SHADER_TT_SET = null;
                        SHADER_TT_LINES = null;
                    }
                    SHADER_TT_LOOKUP_DONE = true;
                }
            }
        }
        if (SHADER_TT_SET != null && SHADER_TT_LINES != null) {
            try { SHADER_TT_SET.invoke(rt, SHADER_TT_LINES); } catch (Throwable ignored) {}
        }
        SHADER_TT_TAGGED.add(rt);
    }

    // OPAQUE_DECAL counterpart of tagAsLateRenderForShaders. The FullyBufferedMultiBufferSource flushes by
    // TransparencyType in enum order (OPAQUE → OPAQUE_DECAL → GENERAL_TRANSPARENT → DECAL → WATER_MASK → LINES).
    // Tagging a glint OPAQUE_DECAL makes it flush right AFTER opaque geometry but BEFORE the GENERAL_TRANSPARENT
    // pass that draws translucent bases (the slime outer shell). So the glint depth-tests against a depth buffer
    // holding only stable opaque geometry - never the shell's own Iris-re-sorted translucent depth, which is what
    // made a LINES-tagged shell glint flicker with camera angle and drop out at distance. Reflective, no compileOnly.
    private static volatile boolean SHADER_TT_OD_LOOKUP_DONE = false;
    private static volatile Method SHADER_TT_OD_SET = null;
    private static volatile Object SHADER_TT_OPAQUE_DECAL = null;

    public static void tagAsOpaqueDecalForShaders(RenderType rt) {
        if (rt == null) return;
        if (SHADER_TT_TAGGED.contains(rt)) return;
        if (!SHADER_TT_OD_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!SHADER_TT_OD_LOOKUP_DONE) {
                    try {
                        Class<?> ttCls = Class.forName("net.irisshaders.batchedentityrendering.impl.TransparencyType");
                        Class<?> bshCls = Class.forName("net.irisshaders.batchedentityrendering.impl.BlendingStateHolder");
                        SHADER_TT_OD_SET = bshCls.getMethod("setTransparencyType", ttCls);
                        @SuppressWarnings({"rawtypes", "unchecked"})
                        Object od = Enum.valueOf((Class<? extends Enum>) ttCls, "OPAQUE_DECAL");
                        SHADER_TT_OPAQUE_DECAL = od;
                    } catch (Throwable ignored) {
                        SHADER_TT_OD_SET = null;
                        SHADER_TT_OPAQUE_DECAL = null;
                    }
                    SHADER_TT_OD_LOOKUP_DONE = true;
                }
            }
        }
        if (SHADER_TT_OD_SET != null && SHADER_TT_OPAQUE_DECAL != null) {
            try { SHADER_TT_OD_SET.invoke(rt, SHADER_TT_OPAQUE_DECAL); } catch (Throwable ignored) {}
        }
        SHADER_TT_TAGGED.add(rt);
    }


    // The shader mod's shadow pass re-invokes the full entity/item render pipeline to populate the
    // shadowmap. If we run our outline path during shadows, two things go wrong:
    //   1) The shadow pass writes into a separate framebuffer with its own format constraints,
    //      and getBuffer() returns a buffer with a different vertex format than we expect →
    //      "Not filled all elements of the vertex" crash on endVertex.
    //   2) Even if it didn't crash, outline geometry has no business depth-writing into the
    //      shadowmap - it would just produce wrong shadows for the outline shell.
    // Skip the whole outline path when shadows are being rendered. Reflective lookup so we
    // don't need a compileOnly dep.
    private static volatile boolean SHADOW_LOOKUP_DONE = false;
    private static volatile Method SHADOW_IS_RENDERING = null;

    public static boolean isInShadowPass() {
        if (!SHADOW_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!SHADOW_LOOKUP_DONE) {
                    try {
                        Class<?> cls = Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState");
                        SHADOW_IS_RENDERING = cls.getMethod("areShadowsCurrentlyBeingRendered");
                    } catch (Throwable ignored) {
                        SHADOW_IS_RENDERING = null;
                    }
                    SHADOW_LOOKUP_DONE = true;
                }
            }
        }
        if (SHADOW_IS_RENDERING == null) return false;
        try {
            return (Boolean) SHADOW_IS_RENDERING.invoke(null);
        } catch (Throwable t) {
            return false;
        }
    }

    // True only between LevelRenderer.renderLevel HEAD and RETURN - i.e. while the 3D world (and the
    // entities in it) are being drawn. Compat that re-renders worn armor/curios through a mod's own
    // renderer (Mekanism special armor, Artifacts) uses this to skip the inventory player preview and
    // GUI item icons, whose GUI-ortho context would otherwise stretch our entity-space glint/glow into a
    // giant projected ray. Set by LevelRendererMixin; reset each frame at RenderTick START.
    private static volatile boolean renderingWorld = false;

    public static boolean isRenderingWorld() { return renderingWorld; }

    public static void setRenderingWorld(boolean value) { renderingWorld = value; }

    // Iris's own "am I overriding/masking shaders right now" gate, mirrored exactly - see resolveGlintShader
    // for why this and not our renderingWorld flag. Iris's MixinGameRenderer asks it as
    //   pipeline instanceof ShaderRenderingPipeline && ((ShaderRenderingPipeline) pipeline).shouldOverrideShaders()
    // so the instanceof matters: a non-shader pipeline must read false, not throw. Reflective (no compileOnly
    // dep on Oculus); false whenever Iris is absent or no pack is active.
    private static volatile boolean IRIS_OVERRIDE_LOOKUP_DONE = false;
    private static volatile Method IRIS_OVR_PIPELINE_MANAGER = null;
    private static volatile Method IRIS_OVR_PIPELINE_NULLABLE = null;
    private static volatile Method IRIS_OVR_SHOULD_OVERRIDE = null;
    private static volatile Class<?> IRIS_OVR_SHADER_PIPELINE = null;

    public static boolean irisShouldOverrideShaders() {
        if (!IRIS_OVERRIDE_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!IRIS_OVERRIDE_LOOKUP_DONE) {
                    try {
                        IRIS_OVR_PIPELINE_MANAGER = Class.forName("net.irisshaders.iris.Iris")
                                .getMethod("getPipelineManager");
                        IRIS_OVR_PIPELINE_NULLABLE = Class.forName("net.irisshaders.iris.pipeline.PipelineManager")
                                .getMethod("getPipelineNullable");
                        IRIS_OVR_SHADER_PIPELINE = Class.forName("net.irisshaders.iris.pipeline.ShaderRenderingPipeline");
                        IRIS_OVR_SHOULD_OVERRIDE = IRIS_OVR_SHADER_PIPELINE.getMethod("shouldOverrideShaders");
                    } catch (Throwable ignored) {
                        IRIS_OVR_PIPELINE_MANAGER = null;
                        IRIS_OVR_PIPELINE_NULLABLE = null;
                        IRIS_OVR_SHADER_PIPELINE = null;
                        IRIS_OVR_SHOULD_OVERRIDE = null;
                    }
                    IRIS_OVERRIDE_LOOKUP_DONE = true;
                }
            }
        }
        if (IRIS_OVR_SHOULD_OVERRIDE == null) return false;
        try {
            Object pm = IRIS_OVR_PIPELINE_MANAGER.invoke(null);
            if (pm == null) return false;
            Object pipe = IRIS_OVR_PIPELINE_NULLABLE.invoke(pm);
            if (!IRIS_OVR_SHADER_PIPELINE.isInstance(pipe)) return false;
            return (Boolean) IRIS_OVR_SHOULD_OVERRIDE.invoke(pipe);
        } catch (Throwable t) {
            return false;
        }
    }
}
