package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.tunamods.customglint.common.CustomGlint;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Photon shader-pack compatibility for the textured glint. Lives in {@code common.client} (the api jar) so it
 * covers embedders that bundle the api jar as well as the standalone mod.
 *
 * <p>Every textured glint RenderType draws through vanilla's {@code rendertype_glint} shader and carries its
 * colour on {@code ColorModulator} ({@code RenderSystem.setShaderColor}). Under a pack Iris swaps that program
 * for the pack's {@code gbuffers_armor_glint} and rewrites {@code gl_Color} in it to
 * {@code vec4(iris_ColorModulator.rgb, iris_ColorModulator.a * iris_GlintAlpha)}, so a pack that reads
 * {@code gl_Color} keeps our colour for free. Photon's glint program reads only {@code gtexture} and never
 * touches {@code gl_Color}, so the modulator is dropped on the floor and the design renders as the raw
 * grayscale it is stored as.
 *
 * <p>The fix is to move the colour out of the uniform and into the thing Photon does read: bind a tinted copy
 * of the design texture instead of the grayscale one, with the modulator left at white. Result is identical to
 * the no-pack path (the glint blend func is {@code SRC_COLOR, ONE}, so only RGB matters), which is why the same
 * tinted texture is safe to use for every draw once the pack is detected, GUI included.
 *
 * <p>Detection is behavioural, not by name: read the active pack's glint fragment source through Iris and check
 * whether it mentions {@code gl_Color}. A pack that doesn't gets the tinted path. If the source can't be read
 * (Iris internals moved), fall back to matching the pack name. All Iris access is reflective, per the project's
 * soft-compat rule; no Iris means no pack means no tinting.
 */
public final class PhotonCompat {
    private PhotonCompat() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    // ── Detection ─────────────────────────────────────────────────────────────

    /** Pack name the cached verdict belongs to; a different name re-runs the probe. The name can be null (Iris
     *  internals moved), so a separate flag says whether the probe has run at all. */
    private static String verdictPack = null;
    private static boolean probed = false;
    private static boolean verdict = false;

    /**
     * True when the active pack's glint program ignores the colour uniform, so callers must bake the glint
     * colour into the texture instead. False when no pack is active or the pack reads {@code gl_Color}.
     */
    public static boolean dropsGlintColor() {
        if (!CustomGlintRenderer.isShaderPackActive()) return false;
        String pack = currentPackName();
        if (!probed || !Objects.equals(pack, verdictPack)) {
            verdictPack = pack;
            probed = true;
            Boolean sniffed = glintProgramIgnoresColor();
            verdict = sniffed != null ? sniffed
                    : pack != null && pack.toLowerCase(Locale.ROOT).contains("photon");
            if (verdict) LOGGER.info("[customglint] shader pack '{}' drops the glint colour uniform; "
                    + "baking glint colour into the design texture", pack);
        }
        return verdict;
    }

    private static String currentPackName() {
        try {
            return (String) Class.forName("net.irisshaders.iris.Iris")
                    .getMethod("getCurrentPackName").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code TRUE} when the pack's glint fragment shader never reads {@code gl_Color}, {@code FALSE} when it
     *  does, {@code null} when the source can't be reached (caller falls back to the name match). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Boolean glintProgramIgnoresColor() {
        try {
            Class<?> irisCls = Class.forName("net.irisshaders.iris.Iris");
            Object pack = ((Optional<?>) irisCls.getMethod("getCurrentPack").invoke(null)).orElse(null);
            if (pack == null) return null;
            Object dim = irisCls.getMethod("getCurrentDimension").invoke(null);
            Class<?> nsIdCls = Class.forName("net.irisshaders.iris.shaderpack.materialmap.NamespacedId");
            Object programSet = pack.getClass().getMethod("getProgramSet", nsIdCls).invoke(pack, dim);
            if (programSet == null) return null;

            Class<?> progIdCls = Class.forName("net.irisshaders.iris.shaderpack.loading.ProgramId");
            Object armorGlint = Enum.valueOf((Class<? extends Enum>) (Class<?>) progIdCls, "ArmorGlint");
            Object source = ((Optional<?>) programSet.getClass().getMethod("get", progIdCls)
                    .invoke(programSet, armorGlint)).orElse(null);
            // No glint program at all: Iris falls back to a program that does read gl_Color, so colour survives.
            if (source == null) return Boolean.FALSE;

            String frag = (String) ((Optional<?>) source.getClass().getMethod("getFragmentSource")
                    .invoke(source)).orElse(null);
            if (frag == null) return null;
            return !frag.contains("gl_Color");
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Tinted design textures ────────────────────────────────────────────────

    /** One tinted texture per glint RenderType, keyed by the identity of that RenderType's colour holder (the
     *  float[4] its setup closure reads each flush), so an animated colour re-tints in place instead of
     *  churning texture objects. Render thread only; dropped with the RenderTypes on resource reload. */
    private static final Map<float[], Tint> TINTS = new IdentityHashMap<>();
    private static int tintSerial = 0;

    private static final class Tint {
        final ResourceLocation loc;
        final DynamicTexture texture;
        ResourceLocation design;   // design the current contents were tinted from
        int rgb = -1;              // packed colour the current contents were tinted with

        Tint(ResourceLocation loc, DynamicTexture texture) {
            this.loc = loc;
            this.texture = texture;
        }
    }

    /**
     * The design texture with {@code holder}'s premultiplied colour (times {@code brightness}) baked into its
     * RGB, for binding to Sampler0 in place of {@link CustomGlintRenderer#getTexture}. Null if the design has
     * no texture, in which case the caller keeps the grayscale + modulator path.
     */
    public static ResourceLocation tintedDesign(ResourceLocation design, float[] holder, float brightness) {
        ResourceLocation grayLoc = CustomGlintRenderer.getTexture(design);
        if (grayLoc == null) return null;
        NativeImage gray = pixelsOf(grayLoc);
        if (gray == null) return null;

        int rgb = packColor(holder, brightness);
        Tint tint = TINTS.get(holder);
        if (tint == null || tint.texture.getPixels() == null
                || tint.texture.getPixels().getWidth() != gray.getWidth()
                || tint.texture.getPixels().getHeight() != gray.getHeight()) {
            tint = create(gray.getWidth(), gray.getHeight());
            if (tint == null) return null;
            TINTS.put(holder, tint);
        }
        if (tint.rgb != rgb || !design.equals(tint.design)) {
            retint(tint, gray, rgb);
            tint.rgb = rgb;
            tint.design = design;
        }
        return tint.loc;
    }

    /** Release every tinted texture. Called from {@link CustomGlintRenderer#clearTextures()}, which also evicts
     *  the RenderTypes (and so the colour holders) these are keyed by. */
    public static void clear() {
        Minecraft mc = Minecraft.getInstance();
        for (Tint t : TINTS.values()) mc.getTextureManager().release(t.loc);
        TINTS.clear();
        verdictPack = null;
        probed = false;
        verdict = false;
    }

    /** The grayscale design texture's backing image, or null if it isn't one of ours / has been closed. */
    private static NativeImage pixelsOf(ResourceLocation loc) {
        AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(loc, null);
        return tex instanceof DynamicTexture dt ? dt.getPixels() : null;
    }

    private static Tint create(int width, int height) {
        NativeImage img = new NativeImage(width, height, false);
        DynamicTexture dt = new DynamicTexture(img);
        ResourceLocation loc = CustomGlint.res("glint_tinted/" + (tintSerial++));
        Minecraft.getInstance().getTextureManager().register(loc, dt);
        // The design tiles across the model UVs, so it needs the same REPEAT + NEAREST state the grayscale
        // texture is created with; DynamicTexture's defaults clamp and would smear the last row/column.
        dt.bind();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        return new Tint(loc, dt);
    }

    /** Multiply the grayscale design by {@code rgb} into {@code tint} and push it to the GPU. Alpha is copied
     *  through untouched: the glint blend func is {@code SRC_COLOR, ONE}, so the design's shape has always been
     *  carried by its RGB and folding alpha in here would darken it against the no-pack path. */
    private static void retint(Tint tint, NativeImage gray, int rgb) {
        NativeImage dst = tint.texture.getPixels();
        if (dst == null) return;
        int cr = (rgb >> 16) & 0xFF, cg = (rgb >> 8) & 0xFF, cb = rgb & 0xFF;
        for (int y = 0; y < gray.getHeight(); y++) {
            for (int x = 0; x < gray.getWidth(); x++) {
                // NativeImage pixel format is ABGR stored as int: (A<<24)|(B<<16)|(G<<8)|R
                int px = gray.getPixelRGBA(x, y);
                int lum = px & 0xFF;
                int a = (px >>> 24) & 0xFF;
                int r = lum * cr / 255;
                int g = lum * cg / 255;
                int b = lum * cb / 255;
                dst.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        tint.texture.upload();
    }

    /** The colour holder's premultiplied floats, scaled by {@code brightness}, as packed 0xRRGGBB. */
    private static int packColor(float[] holder, float brightness) {
        int r = channel(holder[0] * brightness);
        int g = channel(holder[1] * brightness);
        int b = channel(holder[2] * brightness);
        return (r << 16) | (g << 8) | b;
    }

    private static int channel(float v) {
        int i = Math.round(v * 255.0f);
        return i < 0 ? 0 : Math.min(i, 255);
    }
}
