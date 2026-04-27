package com.example.examplemod.glint;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Client-side cache that converts design PNG textures to grayscale {@link DynamicTexture}s
 * and registers them with the {@link net.minecraft.client.renderer.texture.TextureManager}.
 *
 * <h3>Why grayscale?</h3>
 * The glint color is applied at render time via {@code RenderSystem.setShaderColor()}, not baked
 * into the texture pixels. Storing the design as grayscale means the same cached texture can be
 * tinted to any color — including animated multi-color sequences — without re-uploading to the GPU.
 * If the source PNG were stored in color, the shader's multiplicative tint would produce unexpected
 * results when the source hue conflicts with the desired tint color.
 *
 * <h3>Lifecycle</h3>
 * Textures are generated lazily on first use and cached for the session. Call {@link #clear()} when
 * resource packs are reloaded (or when the client shuts down) to release GPU memory.
 *
 * <h3>Error handling</h3>
 * If the design ResourceLocation cannot be found or loaded, the original (un-converted) location is
 * returned as a fallback so the render path does not crash; the result will just look wrong.
 */
public final class GlintTextureCache {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Maps original design location → registered grayscale texture location.
     * Values are distinct from keys (a new "examplemod:glint/..." path is derived for each entry).
     */
    private static final Map<ResourceLocation, ResourceLocation> cache = new HashMap<>();

    private GlintTextureCache() {}

    /**
     * Returns the grayscale texture location for {@code design}, generating and caching it on first call.
     * Must only be called on the render thread (accesses {@link Minecraft} and {@link DynamicTexture}).
     */
    public static ResourceLocation get(ResourceLocation design) {
        return cache.computeIfAbsent(design, k -> generate(design));
    }

    /**
     * Releases all cached {@link DynamicTexture}s from the texture manager and clears the map.
     * Call this on resource pack reload to avoid GPU memory leaks.
     */
    public static void clear() {
        Minecraft mc = Minecraft.getInstance();
        cache.values().forEach(loc -> mc.getTextureManager().release(loc));
        cache.clear();
    }

    /**
     * Loads the source PNG at {@code design}, converts every pixel to average-luminance grayscale
     * (preserving alpha), registers it as a new {@link DynamicTexture}, and returns the registered location.
     *
     * <p>On any I/O error or missing resource, returns {@code design} unchanged as a fallback.
     */
    private static ResourceLocation generate(ResourceLocation design) {
        LOGGER.info("[CustomGlint] Generating grayscale texture: design={}", design);
        Minecraft mc = Minecraft.getInstance();
        NativeImage source;
        try {
            var resource = mc.getResourceManager().getResource(design);
            if (resource.isEmpty()) {
                LOGGER.warn("[CustomGlint] Design texture not found: {}", design);
                return design;
            }
            try (InputStream stream = resource.get().open()) {
                source = NativeImage.read(stream);
            }
        } catch (IOException e) {
            LOGGER.error("[CustomGlint] Failed to load design {}: {}", design, e.getMessage());
            return design;
        }

        NativeImage gray = new NativeImage(source.getWidth(), source.getHeight(), false);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                // NativeImage pixel format is ABGR stored as int: (A<<24)|(B<<16)|(G<<8)|R
                int pixel = source.getPixelRGBA(x, y);
                int r = pixel & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = (pixel >> 16) & 0xFF;
                int a = (pixel >> 24) & 0xFF;
                // Simple average-of-channels luminance (no perceptual weighting).
                int lum = (r + g + b) / 3;
                // Write back as ABGR with equal R=G=B=lum so the pixel is neutral gray.
                gray.setPixelRGBA(x, y, (a << 24) | (lum << 16) | (lum << 8) | lum);
            }
        }
        source.close();

        // Build a stable, unique path for the registered texture.
        // Slashes and dots in the original path are replaced so the name is a valid texture path.
        String safePath = design.getNamespace() + "/" + design.getPath().replace('/', '_').replace('.', '_');
        ResourceLocation loc = new ResourceLocation("examplemod", "glint/" + safePath);
        mc.getTextureManager().register(loc, new DynamicTexture(gray));
        return loc;
    }
}
