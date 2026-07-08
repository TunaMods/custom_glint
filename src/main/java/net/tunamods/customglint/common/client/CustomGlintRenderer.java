package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.tunamods.customglint.common.CustomGlint;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
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
        // Single map lookup on the common (resolved, non-null) hit path; only null-cached negatives
        // fall through to the containsKey check to distinguish "cached miss" from "never seen".
        ResourceLocation cached = textureCache.get(design);
        if (cached != null || textureCache.containsKey(design)) return cached;
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
        for (ItemGlintEntry e : BY_GLINT_ITEM.values())     evictRt(e.rt);
        for (RenderType rt : BY_ARMOR_GLINT.values())       evictRt(rt);
        for (RenderType rt : BY_HORSE_ARMOR_GLINT.values()) evictRt(rt);
        for (RenderType rt : BY_MOUNT_ARMOR_GLINT.values()) evictRt(rt);
        for (RenderType rt : BY_MOUNT_ARMOR_MASK.values())  evictRt(rt);
        for (RenderType rt : BY_CHROMATIC.values())         evictRt(rt);
        BY_GLINT_ITEM.clear();
        BY_ARMOR_GLINT.clear();
        BY_HORSE_ARMOR_GLINT.clear();
        BY_MOUNT_ARMOR_GLINT.clear();
        BY_MOUNT_ARMOR_MASK.clear();
        BY_CHROMATIC.clear();
        GLINT_COLORS.clear();
        LAYER_KEY_CACHE.clear();
        // Release the chromatic palette strips + white dummy (DynamicTextures registered with the manager).
        for (ResourceLocation loc : paletteCache.values())
            if (loc != null) mc.getTextureManager().release(loc);
        paletteCache.clear();
        if (whiteTex != null) { mc.getTextureManager().release(whiteTex); whiteTex = null; }
        // The shader-TT tag set retains every RenderType it ever saw; drop it on reload (re-applied lazily)
        // so RenderTypes evicted above aren't pinned across reloads.
        SHADER_TT_TAGGED.clear();
        // Drop the entity RenderType verdict cache too, so it doesn't pin RT singletons across reloads.
        EntityGlintRender.GlintWrappingBufferSource.clearRtVerdictCache();
        // Free the private HUD-glint source's per-RenderType buffers; the forGlint RTs they key on were just
        // evicted, so these native builders would dangle. The source + builders re-create lazily next HUD glint.
        for (ByteBufferBuilder b : GUI_GLINT_FIXED.values()) { try { b.close(); } catch (Throwable ignored) {} }
        GUI_GLINT_FIXED.clear();
        if (guiGlintSpare != null) { try { guiGlintSpare.close(); } catch (Throwable ignored) {} guiGlintSpare = null; }
        guiGlintSource = null;
        // Invalidate the cached block-atlas dimensions (the atlas restitches on resource reload).
        cachedAtlasW = 0;
        cachedAtlasH = 0;
    }

    // Block-atlas dimensions, read once and reused (the atlas only restitches on resource reload, which
    // resets these to 0 in clearTextures()). Avoids a getModelManager().getAtlas() lookup per glint draw.
    private static int cachedAtlasW = 0;
    private static int cachedAtlasH = 0;

    private static void ensureAtlasDims() {
        if (cachedAtlasW == 0) {
            TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
            cachedAtlasW = atlas.width;
            cachedAtlasH = atlas.height;
        }
    }


    private static ResourceLocation generateTexture(ResourceLocation design) {
        // The chromatic design is procedural (no PNG). Callers branch to forChromatic* before reaching the
        // texture path; this guard keeps any stragglers (compat stencil paths) from probing the resource
        // manager — they get null and skip, so chromatic silently no-ops there rather than crashing.
        if (CustomGlint.isChromatic(design)) return null;
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
    public static SequencedMap<RenderType, ByteBufferBuilder> fixedBufferRegistry;
    public static final ThreadLocal<ItemStack> CURRENT_ITEM_STACK = new ThreadLocal<>();
    /** The display context of the item currently being rendered (set at ItemRenderer.render HEAD). Lets
     *  {@code applyGlint} tell a HUD/GUI icon apart from a world/held item so the HUD glint can be routed
     *  to the batched {@link #guiGlintBuffer} source instead of drawing inline per item. */
    public static final ThreadLocal<ItemDisplayContext> CURRENT_CTX = new ThreadLocal<>();
    public static final ThreadLocal<float[]> COLOR_BUF = ThreadLocal.withInitial(() -> new float[4]);

    // ── HUD glint batching ──────────────────────────────────────────────────────
    // The HUD hotbar draws each item through GuiGraphics.renderItem, which calls GuiGraphics.flush() after
    // EVERY icon — so inline glint draws one flush per item and same-design icons never batch. Route the HUD
    // glint into this private source instead: vanilla's per-item flush never touches it, so every same-config
    // glint accumulates into ONE RenderType buffer and draws in a single endBatch ({@link #drainGuiGlint},
    // at Gui.render TAIL while the GUI ortho + the icons' depth are still committed). forGlint's color rides a
    // per-(design,colors,…) holder, so same-key icons always resolve the same colour in a frame — deferring
    // the draw is colour-safe. Open screens keep the inline path (they need per-item layering vs tooltips).
    private static final SequencedMap<RenderType, ByteBufferBuilder> GUI_GLINT_FIXED = new LinkedHashMap<>();
    private static MultiBufferSource.BufferSource guiGlintSource;
    private static ByteBufferBuilder guiGlintSpare;

    /** Set by a screen that draws its own item icons (outside the vanilla slot pass) and wants their glint
     *  batched — the Glint Table's design/printed palettes. While armed, {@code applyGlint} routes GUI-context
     *  glint into {@link #guiGlintBuffer} regardless of the open screen; the screen must drain + disarm right
     *  after its icon pass (before it draws anything on top of those icons). */
    public static boolean guiGlintBatchArmed = false;

    /** A glint-layer buffer in the private HUD source (each RenderType gets its own builder so layers/colours
     *  accumulate without flushing one another). The base item RT still goes through the normal GUI source. */
    public static VertexConsumer guiGlintBuffer(RenderType rt) {
        GUI_GLINT_FIXED.computeIfAbsent(rt, k -> new ByteBufferBuilder(k.bufferSize()));
        if (guiGlintSource == null) {
            guiGlintSpare = new ByteBufferBuilder(256);
            guiGlintSource = MultiBufferSource.immediateWithBuffers(GUI_GLINT_FIXED, guiGlintSpare);
        }
        return guiGlintSource.getBuffer(rt);
    }

    /** Draws all HUD glint accumulated this frame in one batch per RenderType. Called at Gui.render TAIL,
     *  where the GUI ortho projection and the icons' committed depth (forGlint EQUAL-depth-tests against it)
     *  are both still live. No-op until the first HUD glint creates the source. */
    public static void drainGuiGlint() {
        if (guiGlintSource != null) guiGlintSource.endBatch();
    }

    /** Reused scroll matrix for the glint texturing shards. Each shard's setup→draw→clear is atomic
     *  per RenderType flush, so a single per-thread instance (reset via {@code translation(...)}) avoids
     *  allocating a fresh {@link Matrix4f} on every batch flush of every glinted surface. */
    private static final ThreadLocal<Matrix4f> TEX_MATRIX = ThreadLocal.withInitial(Matrix4f::new);

    // ── Scroll direction → UV drift ─────────────────────────────────────────────
    // The glint motif drifts along one of eight compass directions (or freezes when STATIC). The pattern
    // *appears* to move opposite to the UV drift, so these are negated from screen direction; texture-V runs
    // DOWN so North (pattern up) drifts +V. Shared immutable vectors avoid a per-flush float[] allocation.
    private static final float SQ = 0.70710677f; // 1/√2
    private static final float[] SU_E  = {-1f, 0f},  SU_NE = {-SQ, SQ}, SU_N  = {0f, 1f},  SU_NW = {SQ, SQ},
                                 SU_W  = { 1f, 0f},  SU_SW = { SQ,-SQ}, SU_S  = {0f,-1f},  SU_SE = {-SQ,-SQ},
                                 SU_STATIC = {0f, 0f};
    private static final ThreadLocal<float[]> SCROLL_BUF = ThreadLocal.withInitial(() -> new float[2]);

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
        Matrix4f m = TEX_MATRIX.get().translation(sc[0], sc[1], 0.0F);
        m.translate(0.5f, 0.5f, 0.0f);
        m.scale(scaleU * layer.patternScale(), scaleV * layer.patternScale(), 1.0f);
        m.translate(-0.5f, -0.5f, 0.0f);
        RenderSystem.setTextureMatrix(m);
    }

    /** Armor / horse / mount / entity directional-drift texture matrix (uniform model-UV scale). */
    private static void setModelScrollMatrix(Layer layer, int colorIdx) {
        float[] sc = scrollAmount(layer, colorIdx);
        Matrix4f m = TEX_MATRIX.get().translation(sc[0], sc[1], 0.0F);
        m.translate(0.5f, 0.5f, 0.0f);
        m.scale(layer.patternScale());
        m.translate(-0.5f, -0.5f, 0.0f);
        RenderSystem.setTextureMatrix(m);
    }

    /** Hard cap on each glint RenderType cache. A creative player cycling colours/designs on the wand
     *  generates a distinct key per config; without a bound these maps (and the native
     *  {@link ByteBufferBuilder} each cached RT pins in {@code fixedBuffers}) grow for the whole session.
     *  256 covers any realistic on-screen variety; the eldest entry is evicted (buffer closed) past it. */
    private static final int RT_CACHE_CAP = 256;

    /** Access-order LRU of glint RenderTypes. On eviction it closes the RT's native fixed buffer
     *  ({@link #evictRt}) and drops the paired colour holder so the two caches stay in lockstep. */
    private static final class RtCache extends LinkedHashMap<String, RenderType> {
        RtCache() { super(64, 0.75f, true); }
        @Override protected boolean removeEldestEntry(Map.Entry<String, RenderType> eldest) {
            if (size() > RT_CACHE_CAP) {
                evictRt(eldest.getValue());
                GLINT_COLORS.remove(eldest.getKey());
                return true;
            }
            return false;
        }
    }

    /** Item-glint cache entry: the cached RenderType plus its per-frame colour holder in one object, so a
     *  single map probe per draw yields both (the RT's setup closure reads {@code color[0..3]} at flush). */
    private static final class ItemGlintEntry { RenderType rt; final float[] color = new float[4]; }

    /** LRU of item-glint entries. Mirrors {@link RtCache}'s bound + eviction, but evicts the RenderType held
     *  inside the entry and needs no paired colour-map cleanup (the colour rides the entry). */
    private static final class ItemGlintCache extends LinkedHashMap<String, ItemGlintEntry> {
        ItemGlintCache() { super(64, 0.75f, true); }
        @Override protected boolean removeEldestEntry(Map.Entry<String, ItemGlintEntry> eldest) {
            if (size() > RT_CACHE_CAP) { evictRt(eldest.getValue().rt); return true; }
            return false;
        }
    }

    /** Per-key mutable float[4] holders; RenderType lambdas close over these references and read them each frame. */
    private static final Map<String, float[]>    GLINT_COLORS          = new HashMap<>();
    /** Item glint (forGlint) RenderType + colour holder, keyed together so the hot path probes one map. */
    private static final Map<String, ItemGlintEntry> BY_GLINT_ITEM     = new ItemGlintCache();
    private static final Map<String, RenderType> BY_ARMOR_GLINT        = new RtCache();
    private static final Map<String, RenderType> BY_HORSE_ARMOR_GLINT  = new RtCache();
    private static final Map<String, RenderType> BY_MOUNT_ARMOR_GLINT  = new RtCache();
    private static final Map<ResourceLocation, RenderType> BY_MOUNT_ARMOR_MASK = new HashMap<>();

    // ── Procedural chromatic glint ──────────────────────────────────────────────
    // The chromatic design has no PNG: a custom core shader (registered via RegisterShadersEvent)
    // synthesises an oil-slick from value-noise. Per-layer payload (seed / morph-speed / colour count)
    // rides spare TextureMat slots [2][0]/[2][1]/[2][3]; up to 8 colours ride a 1px palette strip on
    // Sampler1. One RenderType per (colours, speed, scale, seed, surface) — a single draw, no per-colour
    // fan-out. Caches mirror the texture-glint LRU caps + reload eviction.
    private static ShaderInstance chromaticShader;
    private static final RenderStateShard.ShaderStateShard CHROMATIC_SHADER_SHARD =
            new RenderStateShard.ShaderStateShard(() -> chromaticShader);
    private static final Map<String, RenderType> BY_CHROMATIC = new RtCache();

    /** 1px-tall palette strips (one RGBA texel per colour) bound to Sampler1, keyed by colour set. Bounded
     *  LRU: a creative player cycling colours would otherwise pin one DynamicTexture per distinct set. */
    private static final int PALETTE_CACHE_CAP = 256;
    private static final Map<String, ResourceLocation> paletteCache =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, ResourceLocation> eldest) {
                    if (size() <= PALETTE_CACHE_CAP) return false;
                    if (eldest.getValue() != null) {
                        try { Minecraft.getInstance().getTextureManager().release(eldest.getValue()); }
                        catch (Throwable ignored) {}
                    }
                    return true;
                }
            };
    private static ResourceLocation whiteTex; // 1×1 opaque-white dummy for Sampler0 (the shader never reads it)

    /** Mod-bus listener (hooked from {@link CustomGlintClientInit}): registers the procedural chromatic
     *  core shader. POSITION_TEX so it slots into the same fixed-buffer paths as the texture glints. */
    public static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            CustomGlint.res("chromatic"),
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

    private static String colorsKey(int[] colors) {
        StringBuilder sb = new StringBuilder(colors.length * 7);
        for (int c : colors) sb.append(Integer.toHexString(c)).append('_');
        return sb.length() == 0 ? "rainbow" : sb.toString();
    }

    /** Palette strip for Sampler1: width = max(1, colours), one opaque RGBA texel per colour (RGB only —
     *  the shader applies its own brightness). With no colours a 1px white keeps Sampler1 bound; the shader
     *  reads colour-count 0 and falls back to a full-spectrum rainbow, so the strip's contents go unused. */
    private static ResourceLocation getPaletteTexture(int[] colors) {
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

    private static float packSeed(int seed) {
        return (seed & 0xFFFF) / 256.0f; // 0..256 range; vsh forwards it, fsh derives a 2D offset from it
    }

    /** Render colour for an undyed (empty-palette) non-chromatic layer — white, so a blank trim's design
     *  stays visible without any dye being stored. Shared by the item/armor/elytra/entity glint loops. */
    public static final int[] WHITE_COLOR = { 0xFFFFFFFF };

    /** A colourless chromatic trim (the creative-tab template) renders this neutral white→grey→dark slick
     *  instead of the full-spectrum rainbow fallback — the recognisable "greyscale" look from 26.1.2. */
    private static final int[] CHROMATIC_EMPTY_PALETTE = { 0xFFFFFFFF, 0xFF8A8A8A, 0xFF3A3A3A };

    /** Resolve a chromatic layer's palette: its own colours, or the greyscale template when it has none. */
    public static int[] chromaticColors(int[] colors) {
        return colors.length == 0 ? CHROMATIC_EMPTY_PALETTE : colors;
    }

    /** Chromatic texture matrix: the 2D part scales the noise UV exactly like the texture glint (no scroll —
     *  the slick flows in-shader via GameTime); the spare column-2 slots carry the per-layer payload the
     *  immutable RenderType can't pass as a uniform. UV0.z is 0 so column 2 never affects {@code noiseCoord}. */
    private static void setChromaticMatrix(Layer layer, float scaleU, float scaleV, int colorCount) {
        Matrix4f m = TEX_MATRIX.get().translation(0.0f, 0.0f, 0.0f);
        m.translate(0.5f, 0.5f, 0.0f);
        m.scale(scaleU * layer.patternScale(), scaleV * layer.patternScale(), 1.0f);
        m.translate(-0.5f, -0.5f, 0.0f);
        m.m20((float) layer.speed());      // TextureMat[2][0] = morph speed
        m.m21((float) colorCount);         // TextureMat[2][1] = colour count (0 → rainbow)
        m.m23(packSeed(layer.seed()));     // TextureMat[2][3] = per-trim seed
        RenderSystem.setTextureMatrix(m);
    }

    /** Builds (or returns cached) a chromatic RenderType for one layer. {@code layering} matches the surface
     *  the glint draws on (NO_LAYERING for items / horse / entity, VIEW_OFFSET_Z_LAYERING for worn armor),
     *  exactly mirroring the texture-glint depth setup so the EQUAL_DEPTH_TEST pass lines up. */
    private static RenderType chromaticRT(Layer layer, String tag, float scaleU, float scaleV,
                                          RenderStateShard.LayeringStateShard layering) {
        int[] colors = chromaticColors(layer.colors());
        final int colorCount = Math.min(colors.length, 8);
        final ResourceLocation white = getWhiteTexture();
        final ResourceLocation palette = getPaletteTexture(colors);
        String key = tag + "|" + colorsKey(colors) + "|" + layer.speed() + "|" + layer.patternScale()
                + "|" + layer.seed() + "|" + scaleU + "|" + scaleV;
        RenderType cached = BY_CHROMATIC.computeIfAbsent(key, k -> {
            RenderType rt = RenderType.create(
                    MOD_ID + ":custom_chromatic|" + k.hashCode(),
                    DefaultVertexFormat.POSITION_TEX,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(CHROMATIC_SHADER_SHARD)
                            .setTextureState(new TextureStateShard(white, false, false) {
                                @Override public void setupRenderState() {
                                    RenderSystem.setShaderTexture(0, white);
                                    RenderSystem.setShaderTexture(1, palette);
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderTexture(1, 0);
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            .setDepthTestState(EQUAL_DEPTH_TEST)
                            .setLayeringState(layering)
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_chromatic_texturing|" + k.hashCode(),
                                    () -> setChromaticMatrix(layer, scaleU, scaleV, colorCount), RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            putCapturedFixedBuffer(rt);
            return rt;
        });
        registerLiveFixedBuffer(cached);
        return cached;
    }

    /** Flat item / 3D held-item chromatic glint (EQUAL depth, no polygon offset). isItem mirrors
     *  {@link #forGlint}'s atlas-calibrated scale so the slick density matches the texture glints. */
    /** Noise UV scale for armor / elytra / horse-armor / entity-body chromatic (26.1.2 CHROMATIC_MODEL_UV_SCALE). */
    private static final float CHROMATIC_MODEL_UV_SCALE = 8.0f;

    public static RenderType forChromaticGlint(Data glint, int layerIdx, boolean isItem) {
        if (chromaticShader == null) return null;
        // Flat item: uvScale = atlasW/16 (per-axis atlasH/16) cancels the block-atlas sprite compression so a
        // 16px sprite spans patternScale UV units → DENSITY(7)·patternScale cells per icon, GUI + world alike
        // (26.1.2's value). 3D items (trident) keep uvScale 1.0.
        float scaleU = 1.0f, scaleV = 1.0f;
        if (isItem) {
            ensureAtlasDims();
            scaleU = cachedAtlasW / 16.0f;
            scaleV = cachedAtlasH / 16.0f;
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

    /**
     * Removes {@code rt} from both the captured {@link #fixedBufferRegistry} and the live BufferSource's
     * {@code fixedBuffers}, closing each {@link ByteBufferBuilder} so its off-heap memory is freed.
     * Called on LRU eviction and resource reload — the old code discarded the {@code remove()} return
     * value, leaking one native buffer per cached RenderType on every reload. Public so compat modules
     * (e.g. {@code EpicKnightsGlintRT}) can release their own cached RTs the same way.
     */
    public static void evictRt(RenderType rt) {
        if (rt == null) return;
        if (fixedBufferRegistry != null) {
            ByteBufferBuilder b = fixedBufferRegistry.remove(rt);
            if (b != null) b.close();
        }
        try {
            SequencedMap<RenderType, ByteBufferBuilder> live =
                    Minecraft.getInstance().renderBuffers().bufferSource().fixedBuffers;
            if (live != null) {
                ByteBufferBuilder b2 = live.remove(rt);
                if (b2 != null) b2.close();
            }
        } catch (Throwable ignored) {
            // immutable fixedBuffers (Iris/Sodium) — never received our buffer to begin with
        }
    }

    /**
     * Registers {@code rt}'s persistent buffer into the live BufferSource's {@code fixedBuffers}
     * when absent. Iris and Sodium swap the vanilla BufferSource for one whose {@code fixedBuffers}
     * is an IMMUTABLE fastutil map — its {@code put} throws {@link UnsupportedOperationException}
     * (the inline {@code live.put(...)} this replaced crashed armor/entity/item/outline glint the
     * instant Iris was installed: {@code Object2ObjectFunction.put} → UOE). Swallow that: under an
     * active shader pack our RTs render through the forward/shader path, which never pulls from this
     * BufferSource's fixed buffers, so the registration isn't needed there anyway. The vanilla
     * {@code fixedBufferRegistry} (captured in RenderBuffersMixin) still holds the RT for the
     * no-shader path.
     */
    /** Inserts {@code rt}'s persistent buffer into the captured vanilla {@code fixedBufferRegistry}.
     *  That map is the vanilla (mutable) one captured at {@code RenderBuffers.<init>}, so this normally
     *  succeeds; the try/catch mirrors {@link #registerLiveFixedBuffer} purely as belt-and-suspenders, so
     *  that if it is ever an immutable Iris/Sodium map the put degrades to a no-op (the shader path doesn't
     *  pull from these buffers) instead of crashing the render thread. Closes the buffer on the immutable
     *  path so it isn't leaked. */
    public static void putCapturedFixedBuffer(RenderType rt) {
        if (rt == null || fixedBufferRegistry == null) return;
        ByteBufferBuilder buf = new ByteBufferBuilder(rt.bufferSize());
        try {
            fixedBufferRegistry.put(rt, buf);
        } catch (UnsupportedOperationException ignored) {
            buf.close(); // immutable fixedBuffers (Iris/Sodium) — forward/shader path handles these RTs
        }
    }

    public static void registerLiveFixedBuffer(RenderType rt) {
        if (rt == null) return;
        SequencedMap<RenderType, ByteBufferBuilder> live =
                Minecraft.getInstance().renderBuffers().bufferSource().fixedBuffers;
        if (live == null || live.containsKey(rt)) return;
        try {
            live.put(rt, new ByteBufferBuilder(rt.bufferSize()));
        } catch (UnsupportedOperationException ignored) {
            // immutable fixedBuffers (Iris/Sodium) — forward/shader path handles these RTs
        }
    }

    // Per-Layer memoized cache-key fragment. The costly part of every forX key is the
    // design.toString() + Arrays.toString(colors) + float/int concatenation, rebuilt every frame
    // even on a cache hit. Layer is an immutable data component that stays identity-stable across
    // frames for an unchanged stack, so memoize the fragment by identity. Render-thread only;
    // bounded (clears past 4096 distinct layers) and dropped on resource reload.
    private static final Map<Layer, String> LAYER_KEY_CACHE = new IdentityHashMap<>();

    private static String layerKey(Layer l) {
        String k = LAYER_KEY_CACHE.get(l);
        if (k == null) {
            if (LAYER_KEY_CACHE.size() > 4096) LAYER_KEY_CACHE.clear();
            k = l.design() + "|" + Arrays.toString(l.colors()) + "|" + l.speed()
                    + "|" + l.patternScale() + "|" + l.scrollDir() + "|" + l.scrollOffset();
            LAYER_KEY_CACHE.put(l, k);
        }
        return k;
    }

    public static RenderType forArmorGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        if (getTexture(layer.design()) == null) return null;
        String key = "armor|" + layerKey(layer) + "|" + colorIdx;
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
                            .setShaderState(RENDERTYPE_GLINT_SHADER)
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
                            // TRIED: EQUAL_DEPTH_TEST (attempt 1: shared buffer only, no fixedBufferRegistry;
                            // attempt 2: immediate BufferBuilder + bs.endBatch() pre-flush before glint render;
                            // attempt 3: rename type to "~customglint:..." so it sorts after
                            //   "minecraft:armor_cutout_no_cull" in fixedBuffers flush order, theory being
                            //   armor depth wasn't written yet — all three → glint completely invisible)
                            // LEQUAL required for visibility but bleeds through transparent cutout holes.
                            // Root cause of attempt 1-3 failure: armorCutoutNoCull itself uses
                            // VIEW_OFFSET_Z_LAYERING (polygonOffset -1,-10), writing depth as D-ε. All prior
                            // EQUAL attempts also removed VIEW_OFFSET_Z_LAYERING, so the glint tested at raw
                            // D while the buffer held D-ε — they never matched. Fix: EQUAL + keep
                            // VIEW_OFFSET_Z_LAYERING so the glint also tests at D-ε, matching exactly.
                            .setDepthTestState(EQUAL_DEPTH_TEST)
                            .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_armor_glint_texturing",
                                    () -> setModelScrollMatrix(layer, colorIdx), RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            putCapturedFixedBuffer(rt);
            return rt;
        });
        registerLiveFixedBuffer(cached);
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

    // Horse armor uses entityCutoutNoCull (no polygon offset / no VIEW_OFFSET_Z_LAYERING).
    // forArmorGlint uses EQUAL + VIEW_OFFSET_Z_LAYERING — wrong offset → invisible on horses.
    // This variant keeps EQUAL + NO_LAYERING so depth matches, and scale 1.0 matches forArmorGlint visually.
    public static RenderType forHorseArmorGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        if (getTexture(layer.design()) == null) return null;
        String key = "horse|" + layerKey(layer) + "|" + colorIdx + "|" + layerIdx;
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = BY_HORSE_ARMOR_GLINT.computeIfAbsent(key, k -> {
            ResourceLocation tex = layer.design();
            RenderType rt = RenderType.create(
                    MOD_ID + ":custom_horse_armor_glint|" + k.hashCode(),
                    DefaultVertexFormat.POSITION_TEX,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_GLINT_SHADER)
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
                            .setLayeringState(NO_LAYERING)
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_horse_armor_glint_texturing",
                                    () -> setModelScrollMatrix(layer, colorIdx), RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            putCapturedFixedBuffer(rt);
            return rt;
        });
        registerLiveFixedBuffer(cached);
        return cached;
    }

    /**
     * Stencil-mask write RT for IaF mount armor (dragon / hippogryph / hippocampus). The mount
     * body shares the same EntityModel as the armor layer, so an EQUAL_DEPTH glint RT (the
     * scheme {@link #forHorseArmorGlint} uses for vanilla horse armor) passes depth on every
     * face of the mount, not just the armor — vanilla horse armor avoids this because its
     * armor mesh is a separate model, but IaF reuses the parent mount model with an
     * alpha-cutout armor texture.
     *
     * This RT renders the parent model with the armor texture through entity-cutout's
     * alpha-discard shader and writes only stencil bit {@code 0x80} at opaque texels —
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
            putCapturedFixedBuffer(rt);
            return rt;
        });
        registerLiveFixedBuffer(cached);
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
     * EQUAL 1 instead of NO_LAYERING — only the texels marked by
     * {@link #forMountArmorStencilMask} draw.
     */
    public static RenderType forMountArmorGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        if (getTexture(layer.design()) == null) return null;
        String key = "mount|" + layerKey(layer) + "|" + colorIdx + "|" + layerIdx;
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
                            .setShaderState(RENDERTYPE_GLINT_SHADER)
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
                                    () -> setModelScrollMatrix(layer, colorIdx), RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            putCapturedFixedBuffer(rt);
            return rt;
        });
        registerLiveFixedBuffer(cached);
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
        // isItem=true → flat item model (sword, tool, etc.) → scale 8.0 matches vanilla glint().
        // isItem=false → 3D entity model (trident, etc.) → 1.0 gives visible pattern detail;
        // vanilla entityGlint() uses 0.16 but that tiles too infrequently for custom designs.
        ensureAtlasDims();
        float scaleU = isItem ? (8.0f * cachedAtlasW / 1024.0f) : 1.0f;
        float scaleV = isItem ? (8.0f * cachedAtlasH / 512.0f) : 1.0f;
        Layer layer = glint.layers()[layerIdx];
        if (getTexture(layer.design()) == null) return null;
        String key = layerKey(layer) + "|" + isItem + "|" + layer.interpolate() + "|" + colorIdx + "|" + layerIdx;
        // One map probe carries both the RenderType and the colour holder its setup closure reads each flush.
        ItemGlintEntry entry = BY_GLINT_ITEM.computeIfAbsent(key, k -> new ItemGlintEntry());
        System.arraycopy(frameColor, 0, entry.color, 0, 4);
        if (entry.rt == null) {
            ResourceLocation tex = layer.design();
            final float[] holder = entry.color;
            entry.rt = RenderType.create(
                    MOD_ID + ":custom_glint|" + key.hashCode(),
                    DefaultVertexFormat.POSITION_TEX,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_GLINT_SHADER)
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
                                    () -> setItemScrollMatrix(layer, colorIdx, scaleU, scaleV), RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            putCapturedFixedBuffer(entry.rt);
        }
        registerLiveFixedBuffer(entry.rt);
        return entry.rt;
    }

    public static int computeAnimatedColor(Data glint, int layerIdx) {
        return computeAnimatedColor(glint, layerIdx, 0.0f);
    }

    /** As {@link #computeAnimatedColor(Data, int)} but shifted by {@code phaseFraction} of a full colour loop.
     *  The glow OUTLINE ring passes {@link #GLOW_RING_PHASE_OFFSET} so it lands on a different point of the
     *  cycle than the item's own animated tint — you see two colours at once instead of one. */
    public static int computeAnimatedColor(Data glint, int layerIdx, float phaseFraction) {
        if (glint == null || layerIdx < 0 || layerIdx >= glint.layers().length) return 0xFFFFFFFF;
        Layer layer = glint.layers()[layerIdx];
        int[] colors = layer.colors();
        if (colors.length == 0) return 0xFFFFFFFF;
        if (colors.length == 1) return colors[0];
        // Wall-clock ticks, NOT game time: a screen pausing the game (singleplayer) freezes game time, which
        // sticks the colour on one point of the cycle. A two-colour glint with a near-invisible colour (the
        // layer tear's yellow/black) then freezes blank in a menu; wall-clock keeps it cycling like it does
        // in the running world.
        long gameTime = Util.getMillis() / 50L;
        float totalTicks = (20.0f * colors.length) / layer.speed();
        float t = (gameTime % Math.max(1L, (long) totalTicks)) / totalTicks * colors.length;
        t = ((t + phaseFraction * colors.length) % colors.length + colors.length) % colors.length;
        int idx = (int) t % colors.length;
        if (!layer.interpolate()) return colors[idx];
        float frac = t - (int) t;
        int c1 = colors[idx], c2 = colors[(idx + 1) % colors.length];
        int a = (int)(((c1 >> 24) & 0xFF) * (1 - frac) + ((c2 >> 24) & 0xFF) * frac);
        int r = (int)(((c1 >> 16) & 0xFF) * (1 - frac) + ((c2 >> 16) & 0xFF) * frac);
        int g = (int)(((c1 >>  8) & 0xFF) * (1 - frac) + ((c2 >>  8) & 0xFF) * frac);
        int b = (int)((c1         & 0xFF) * (1 - frac) + (c2         & 0xFF) * frac);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Phase offset (fraction of a full colour loop) applied to the glow OUTLINE ring so it never shows the
     *  same colour as the item's own animated tint at the same instant. Half a cycle = maximum contrast: when
     *  the inner tint is on one colour, the ring is on the opposite side of the loop, so a multi-colour glow
     *  shows two different colours at once instead of one. Mirrors the 1.20.1 GLOW_RING_PHASE_OFFSET. */
    public static final float GLOW_RING_PHASE_OFFSET = 0.5f;

    /** Animates through an int[] color array using game time. Default speed=1, interpolate=true. */
    public static int computeAnimatedGlowColor(int[] colors) {
        return computeAnimatedGlowColor(colors, 1.0f, true);
    }

    /** As above at a chosen {@code speed} (a higher speed cycles faster, mirroring the glint layer speed) and
     *  either blending between colors ({@code interpolate}) or stepping hard between them. Uses GAME time — the
     *  glow OUTLINE (ring/halo) animates on this so it matches the in-world clock. */
    public static int computeAnimatedGlowColor(int[] colors, float speed, boolean interpolate) {
        return computeAnimatedGlowColor(colors, speed, interpolate, 0.0f);
    }

    /** As above but shifted by {@code phaseFraction} of a full colour loop (see {@link #GLOW_RING_PHASE_OFFSET}). */
    public static int computeAnimatedGlowColor(int[] colors, float speed, boolean interpolate, float phaseFraction) {
        Minecraft mc = Minecraft.getInstance();
        long gameTime = mc.level != null ? mc.level.getGameTime() : 0;
        return computeAnimatedGlowColorAt(colors, gameTime, speed, interpolate, phaseFraction);
    }

    /** WALL-CLOCK variant of {@link #computeAnimatedGlowColor}, ticked off {@code Util.getMillis} instead of
     *  game time. The trim texture's tinted edge ("white × rgb") uses this so it runs on a DIFFERENT clock than
     *  the game-time glow outline — the edge and the ring/halo show different colours at the same moment. */
    public static int computeAnimatedGlowColorGui(int[] colors, float speed, boolean interpolate) {
        return computeAnimatedGlowColorAt(colors, Util.getMillis() / 50L, speed, interpolate, 0.0f);
    }

    private static int computeAnimatedGlowColorAt(int[] colors, long timeTicks, float speed, boolean interpolate, float phaseFraction) {
        if (colors.length == 0) return 0xFFFFFFFF;
        if (colors.length == 1) return colors[0];
        if (!Float.isFinite(speed) || speed <= 0) speed = 1.0f;
        float totalTicks = (20.0f * colors.length) / speed;
        float t = (timeTicks % Math.max(1L, (long) totalTicks)) / totalTicks * colors.length;
        t = ((t + phaseFraction * colors.length) % colors.length + colors.length) % colors.length;
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

    /** Resolves a glowing item's outline colour: its glow colours (animated) if set, else its glint
     *  layer-0 colour, else white. Shared by the flat-item and special-item glow-outline capture paths. */
    public static int resolveGlowColor(ItemStack stack) {
        int[] glowColors = CustomGlint.getGlowColors(stack);
        if (glowColors.length > 0)
            return computeAnimatedGlowColor(glowColors, CustomGlint.getGlowSpeed(stack), CustomGlint.getGlowInterpolate(stack), GLOW_RING_PHASE_OFFSET);
        CustomGlint.Data glint = CustomGlint.read(stack);
        return glint != null ? computeAnimatedColor(glint, 0, GLOW_RING_PHASE_OFFSET) : 0xFFFFFFFF;
    }

    /** Edge/inner-tint colour for a glowing item's trim texture ("white × rgb"). Same resolution as
     *  {@link #resolveGlowColor} but at phase 0, so the tinted edge sits exactly {@link #GLOW_RING_PHASE_OFFSET}
     *  behind the outline ring — a stable half-step (edge red while the ring is blue, and back). Critically it
     *  uses the SAME clock as the ring per branch (game-time for explicit glow colours, wall-clock for the glint
     *  layer-0 fallback), so the half-step never drifts the way a wall-vs-game-clock split would. */
    public static int resolveGlowColorTint(ItemStack stack) {
        int[] glowColors = CustomGlint.getGlowColors(stack);
        if (glowColors.length > 0)
            return computeAnimatedGlowColor(glowColors, CustomGlint.getGlowSpeed(stack), CustomGlint.getGlowInterpolate(stack));
        CustomGlint.Data glint = CustomGlint.read(stack);
        return glint != null ? computeAnimatedColor(glint, 0) : 0xFFFFFFFF;
    }

    /** Wall-clock (GUI) counterpart of {@link #resolveGlowColor}, for the trim TEXTURE tint. Animates the glow
     *  colours on wall-clock so the tinted edge ("white × rgb") desyncs from the game-time glow outline — the
     *  edge and the ring show different colours at once. Auto glow follows glint layer 0 (already wall-clock). */
    public static int resolveGlowColorGui(ItemStack stack) {
        int[] glowColors = CustomGlint.getGlowColors(stack);
        if (glowColors.length > 0)
            return computeAnimatedGlowColorGui(glowColors, CustomGlint.getGlowSpeed(stack), CustomGlint.getGlowInterpolate(stack));
        CustomGlint.Data glint = CustomGlint.read(stack);
        return glint != null ? computeAnimatedColor(glint, 0) : 0xFFFFFFFF;
    }

    // ── Glint stencil support (glow outline moved to GlowOutlineRenderer) ──────

    /** Guards re-entrance during glint passes that re-enter the item/model render. Kept as a
     *  recursion guard for the glint paths (item foil, mount-armor/EK glint stencil). */
    public static final ThreadLocal<Boolean> IN_OUTLINE = ThreadLocal.withInitial(() -> false);

    /**
     * Once-per-frame stencil clear gate, still used by the remaining stencil consumers — the IaF
     * mount-armor glint mask ({@link #forMountArmorStencilMask}) and the Epic Knights decoration
     * glint mask. Set true at frame start by CustomGlintClientInit's RenderFrameEvent.Pre; the first
     * stencil mask setup of the frame sees it true, clears the whole stencil buffer, and unsets it so
     * later mask setups only clear their own reserved bit. (The post-process glow outline does NOT use
     * the stencil buffer.)
     */
    public static volatile boolean pendingFrameStencilClear = true;

    /** Reload hooks appended by compat modules; invoked by {@link #clearTextures()} so each
     *  compat can release its own {@code DynamicTexture}s without {@code CustomGlintRenderer}
     *  needing to know about them. */
    public static final List<Runnable> additionalReloadCleanup = new CopyOnWriteArrayList<>();

    // ── Per-draw stencil-value isolation (slot pool) ───────────────────────────
    //
    // NOTE: the glow outline no longer uses the stencil buffer (it is a post-process mask +
    // composite — see GlowOutlineRenderer). This slot pool survives only for the Epic Knights
    // decoration GLINT mask, which still stencil-clips the scrolling glint to the decoration's
    // opaque texels. Each EK decoration reserves a unique slot value (1..255) so overlapping
    // decorations don't share a stencil value: the WRITE shard stamps stencil=V at the
    // decoration's texels, the glint shard tests stencil==V. Ceiling 255 per frame, then wraps.
    // Counter reset at frame start by CustomGlintClientInit's RenderFrameEvent.Pre.
    private static int stencilSlotCounter = 0;

    /** Reserve a unique stencil slot value (1..255) for this outline call. Wraps if exceeded. */
    public static int nextStencilSlot() {
        stencilSlotCounter++;
        if (stencilSlotCounter > 255) stencilSlotCounter = 1;
        return stencilSlotCounter;
    }

    /** Frame-start reset; called from CustomGlintClientInit. */
    public static void resetStencilSlots() { stencilSlotCounter = 0; }

    // Disables both color and depth writes — used by the IaF mount-armor stencil-mask RT.
    private static final RenderStateShard.WriteMaskStateShard NO_WRITE =
            new RenderStateShard.WriteMaskStateShard(false, false);

    // Force-binds the vanilla main render target (which has a stencil attachment via the shader
    // mod's stencil-enabling mixin) and restores the previously-bound FBO on clear.
    // Why: vanilla's MAIN_TARGET OutputStateShard is a no-op runnable that assumes the main FBO
    // is already bound. Under the shader mod's HAND_SOLID phase the gbuffer FBO is bound instead —
    // it has no stencil attachment, so stencil ops are silently dropped and our outline draws
    // the dilated mesh inside the silhouette (visible as a filled plane). Capturing the previous
    // FBO and restoring it on clear keeps the shader pipeline state intact.
    private static final int[] SAVED_FBO = new int[1];
    /** Exposed for compat code (EK decoration RTs) that needs the same Oculus-no-pack-safe
     *  FBO binding. */
    public static final RenderStateShard.OutputStateShard FORCE_MAIN_TARGET =
            new RenderStateShard.OutputStateShard("custom_glint_force_main_target",
                () -> {
                    SAVED_FBO[0] = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                    Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
                },
                () -> {
                    GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, SAVED_FBO[0]);
                });

    // The old stencil/shader glow-outline draw API (doModelOutline / doItemOutline /
    // doGuiItemOutline / doMultiModelOutline / doModelPartsOutline), the silhouette color helpers,
    // the RenderType texture reader, and FullColorOverrideConsumer were removed when the outline
    // was rebuilt as a post-process pass — see GlowOutlineRenderer. Glint rendering (forGlint /
    // forArmorGlint / forHorseArmorGlint / forMountArmorGlint / forEntityGlint) is unaffected.

    private CustomGlintRenderer() { super("", () -> {}, () -> {}); }

    // ── Shader-mod detection ──────────────────────────────────────────────────
    // Reflective so we don't need a compileOnly dep on the shader mod. Resolved once and
    // cached. Consumed by compat outline RTs to decide their shader-pack-safe routing.
    private static volatile boolean SHADER_LOOKUP_DONE = false;
    private static volatile Method SHADER_GET_INSTANCE = null;
    private static volatile Method SHADER_IS_IN_USE = null;

    public static boolean isShaderPackActive() {
        if (!SHADER_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!SHADER_LOOKUP_DONE) {
                    try {
                        // Iris exposes this same API package under NeoForge — do not change it.
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

    // Under shader mods, every RenderType is mixed in to implement BlendingStateHolder with a
    // TransparencyType field (default GENERAL_TRANSPARENT). The batched FullyBufferedMultiBuffer-
    // Source flushes by TransparencyType in enum order (OPAQUE → OPAQUE_DECAL → GENERAL_TRANSPARENT
    // → DECAL → WATER_MASK → LINES). Items use GENERAL_TRANSPARENT; if our outline RT also sits
    // there, the order between item and outline within the same bucket is undefined → outline can
    // flush before item → depth buffer empty when outline draws → polygon-offset/front-face-cull
    // can't reject the interior → outline reads as a filled silhouette. Tagging the outline RT as
    // LINES (last bucket) forces the shader mod to flush ALL item geometry first, then our outline — depth
    // ordering works in every camera context (1P / 3P / GROUND). Reflective to avoid compileOnly.
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


    // The shader mod's shadow pass re-invokes the full entity/item render pipeline to populate the
    // shadowmap. If we run our outline path during shadows, two things go wrong:
    //   1) The shadow pass writes into a separate framebuffer with its own format constraints,
    //      and getBuffer() returns a buffer with a different vertex format than we expect →
    //      "Not filled all elements of the vertex" crash on endVertex.
    //   2) Even if it didn't crash, outline geometry has no business depth-writing into the
    //      shadowmap — it would just produce wrong shadows for the outline shell.
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
}
