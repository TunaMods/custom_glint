package com.example.examplemod.glint;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;

public final class CustomGlint extends RenderStateShard {

    // ── Data ─────────────────────────────────────────────────────────────────

    public record Data(ResourceLocation design, int[] colors, float speed, boolean interpolate) {}

    // ── NBT ──────────────────────────────────────────────────────────────────

    private static final String TAG           = "custom_glint";
    private static final String DESIGN_KEY    = "design";
    private static final String COLORS_KEY    = "colors";
    private static final String SPEED_KEY     = "speed";
    private static final String INTERPOLATE_KEY = "interpolate";

    @Nullable
    public static Data read(ItemStack stack) {
        if (!stack.hasTag()) return null;
        CompoundTag root = stack.getTag();
        if (!root.contains(TAG)) return null;
        CompoundTag tag = root.getCompound(TAG);

        String design = tag.getString(DESIGN_KEY);
        if (design.isEmpty()) return null;

        if (!tag.contains(COLORS_KEY)) return null;
        int[] colors = tag.getIntArray(COLORS_KEY);
        if (colors.length == 0) return null;

        float speed = tag.contains(SPEED_KEY) ? tag.getFloat(SPEED_KEY) : 1.0f;
        if (speed <= 0) speed = 1.0f;

        boolean interpolate = !tag.contains(INTERPOLATE_KEY) || tag.getBoolean(INTERPOLATE_KEY);

        return new Data(new ResourceLocation(design), colors, speed, interpolate);
    }

    public static boolean has(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains(TAG);
    }

    public static void write(ItemStack stack, ResourceLocation design, int[] colors, float speed, boolean interpolate) {
        CompoundTag tag = new CompoundTag();
        tag.putString(DESIGN_KEY, design.toString());
        tag.putIntArray(COLORS_KEY, colors);
        tag.putFloat(SPEED_KEY, speed);
        tag.putBoolean(INTERPOLATE_KEY, interpolate);
        stack.getOrCreateTag().put(TAG, tag);
    }

    public static void remove(ItemStack stack) {
        if (stack.hasTag()) stack.getTag().remove(TAG);
    }

    // ── Texture cache ─────────────────────────────────────────────────────────

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceLocation, ResourceLocation> textureCache = new HashMap<>();

    public static ResourceLocation getTexture(ResourceLocation design) {
        return textureCache.computeIfAbsent(design, k -> generateTexture(k));
    }

    public static void clearTextures() {
        Minecraft mc = Minecraft.getInstance();
        textureCache.values().forEach(loc -> mc.getTextureManager().release(loc));
        textureCache.clear();
    }

    private static ResourceLocation generateTexture(ResourceLocation design) {
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
        ResourceLocation loc = new ResourceLocation("examplemod", "glint/" + safePath);
        mc.getTextureManager().register(loc, new DynamicTexture(gray));
        return loc;
    }

    // ── Render types ──────────────────────────────────────────────────────────

    public static SortedMap<RenderType, BufferBuilder> fixedBufferRegistry;

    private static final Map<String, float[]>    GLINT_COLORS = new HashMap<>();
    private static final Map<String, RenderType> BY_GLINT     = new HashMap<>();

    public static RenderType forGlint(Data glint, float[] frameColor) {
        String key = glint.design() + "|" + Arrays.toString(glint.colors()) + "|" + glint.speed() + "|" + glint.interpolate();
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        return BY_GLINT.computeIfAbsent(key, k -> {
            ResourceLocation tex = glint.design();
            RenderType rt = RenderType.create(
                "examplemod:custom_glint",
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
                    .setTexturingState(new TexturingStateShard("examplemod:custom_glint_texturing", () -> {
                            long t = (long)(Util.getMillis() * 8.0 * glint.speed());
                            float f  = (float)(t % 110000L) / 110000.0F;
                            float f1 = (float)(t % 30000L)  /  30000.0F;
                            Matrix4f m = new Matrix4f().translation(-f, 0.0F, 0.0F);
                            m.rotateZ((float)(Math.PI / 3.0));
                            m.translate(f, 0.0F, 0.0F);
                            m.rotateZ((float)(Math.PI / 3.0));
                            m.translate(-f, 0.0F, 0.0F);
                            m.rotateZ((float)(Math.PI / 3.0));
                            m.translate(f + f1, 0.0F, 0.0F);
                            m.scale(8.0f);
                            RenderSystem.setTextureMatrix(m);
                        }, RenderSystem::resetTextureMatrix))
                    .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            return rt;
        });
    }

    private CustomGlint() { super("", () -> {}, () -> {}); }
}
