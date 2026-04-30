// MIT License — Copyright (c) 2026 Likely Tuna | TunaMods — see LICENSE.txt
package net.tunamods.customglint.common;

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
import net.minecraft.world.item.Item;
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

import static net.tunamods.customglint.CustomGlintMod.MOD_ID;

public final class CustomGlint extends RenderStateShard {

    // ── Data ─────────────────────────────────────────────────────────────────

    public record Data(ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {}

    // ── Colors ────────────────────────────────────────────────────────────────

    public static int color(String hex) {
        return Integer.parseUnsignedInt(hex.startsWith("#") ? hex.substring(1) : hex, 16) | 0xFF000000;
    }

    public static final int RED        = color("FF0000");
    public static final int ORANGE     = color("FF6600");
    public static final int YELLOW     = color("FFFF00");
    public static final int LIME       = color("00FF00");
    public static final int GREEN      = color("008000");
    public static final int CYAN       = color("00FFFF");
    public static final int LIGHT_BLUE = color("00BFFF");
    public static final int BLUE       = color("0000FF");
    public static final int PURPLE     = color("8800FF");
    public static final int MAGENTA    = color("FF00FF");
    public static final int PINK       = color("FF69B4");
    public static final int BROWN      = color("8B4513");
    public static final int WHITE      = color("FFFFFF");
    public static final int LIGHT_GRAY = color("C0C0C0");
    public static final int GRAY       = color("808080");
    public static final int BLACK      = color("000000");

    // ── Designs ───────────────────────────────────────────────────────────────

    public static final ResourceLocation VANILLA    = new ResourceLocation("minecraft", "textures/misc/enchanted_glint_item.png");
    public static final ResourceLocation CHECKER    = new ResourceLocation(MOD_ID, "textures/glint/checker.png");
    public static final ResourceLocation CROSSHATCH = new ResourceLocation(MOD_ID, "textures/glint/crosshatch.png");
    public static final ResourceLocation DIAMONDS   = new ResourceLocation(MOD_ID, "textures/glint/diamonds.png");
    public static final ResourceLocation DOTS       = new ResourceLocation(MOD_ID, "textures/glint/dots.png");
    public static final ResourceLocation FIRE       = new ResourceLocation(MOD_ID, "textures/glint/fire.png");
    public static final ResourceLocation GRID       = new ResourceLocation(MOD_ID, "textures/glint/grid.png");
    public static final ResourceLocation HEXAGON    = new ResourceLocation(MOD_ID, "textures/glint/hexagon.png");
    public static final ResourceLocation PULSE      = new ResourceLocation(MOD_ID, "textures/glint/pulse.png");
    public static final ResourceLocation RIPPLE     = new ResourceLocation(MOD_ID, "textures/glint/ripple.png");
    public static final ResourceLocation SCALES     = new ResourceLocation(MOD_ID, "textures/glint/scales.png");
    public static final ResourceLocation SPARKLE    = new ResourceLocation(MOD_ID, "textures/glint/sparkle.png");
    public static final ResourceLocation STARS      = new ResourceLocation(MOD_ID, "textures/glint/stars.png");
    public static final ResourceLocation STRIPES    = new ResourceLocation(MOD_ID, "textures/glint/stripes.png");
    public static final ResourceLocation SWIRL      = new ResourceLocation(MOD_ID, "textures/glint/swirl.png");
    public static final ResourceLocation WAVE       = new ResourceLocation(MOD_ID, "textures/glint/wave.png");
    public static final ResourceLocation ZIGZAG      = new ResourceLocation(MOD_ID, "textures/glint/zigzag.png");
    public static final ResourceLocation CRYSTAL     = new ResourceLocation(MOD_ID, "textures/glint/crystal.png");
    public static final ResourceLocation EMBER       = new ResourceLocation(MOD_ID, "textures/glint/ember.png");
    public static final ResourceLocation VEIN        = new ResourceLocation(MOD_ID, "textures/glint/vein.png");

    // ── NBT ──────────────────────────────────────────────────────────────────

    private static final String TAG             = MOD_ID;
    private static final String DESIGN_KEY      = "design";
    private static final String COLORS_KEY      = "colors";
    private static final String SPEED_KEY       = "speed";
    private static final String INTERPOLATE_KEY = "interpolate";
    private static final String SCALE_KEY         = "scale";
    private static final String SIMULTANEOUS_KEY  = "simultaneous";

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

        float patternScale = tag.contains(SCALE_KEY) ? tag.getFloat(SCALE_KEY) : 1.0f;
        if (patternScale <= 0) patternScale = 1.0f;

        boolean simultaneous = !tag.contains(SIMULTANEOUS_KEY) || tag.getBoolean(SIMULTANEOUS_KEY);

        return new Data(new ResourceLocation(design), colors, speed, interpolate, patternScale, simultaneous);
    }

    public static boolean has(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains(TAG);
    }

    public static void write(ItemStack stack, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        CompoundTag tag = new CompoundTag();
        tag.putString(DESIGN_KEY, design.toString());
        tag.putIntArray(COLORS_KEY, colors);
        tag.putFloat(SPEED_KEY, speed);
        tag.putBoolean(INTERPOLATE_KEY, interpolate);
        tag.putFloat(SCALE_KEY, patternScale);
        tag.putBoolean(SIMULTANEOUS_KEY, simultaneous);
        stack.getOrCreateTag().put(TAG, tag);
    }

    public static void remove(ItemStack stack) {
        if (stack.hasTag()) stack.getTag().remove(TAG);
    }

    public static ItemStack glinted(Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        ItemStack stack = new ItemStack(item);
        write(stack, design, colors, speed, interpolate, patternScale, simultaneous);
        return stack;
    }

    public static void write(ItemStack stack, ResourceLocation design, int[] colors) {
        write(stack, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void write(ItemStack stack, ResourceLocation design, int color) {
        write(stack, design, new int[]{color}, 1.0f, true, 1.0f, true);
    }

    public static ItemStack glinted(Item item, ResourceLocation design, int[] colors) {
        return glinted(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static ItemStack glinted(Item item, ResourceLocation design, int color) {
        return glinted(item, design, new int[]{color}, 1.0f, true, 1.0f, true);
    }

    public static final Map<Item, Data> CRAFT_GLINTS = new HashMap<>();

    public static void registerCraftGlint(Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        CRAFT_GLINTS.put(item, new Data(design, colors, speed, interpolate, patternScale, simultaneous));
    }

    public static void registerCraftGlint(Item item, ResourceLocation design, int[] colors) {
        registerCraftGlint(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void applyCraftGlint(ItemStack stack) {
        Data data = CRAFT_GLINTS.get(stack.getItem());
        if (data == null) return;
        write(stack, data.design(), data.colors(), data.speed(), data.interpolate(), data.patternScale(), data.simultaneous());
    }

    public static final Map<Item, Data> FISHING_GLINTS = new HashMap<>();

    public static void registerFishingGlint(Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        FISHING_GLINTS.put(item, new Data(design, colors, speed, interpolate, patternScale, simultaneous));
    }

    public static void registerFishingGlint(Item item, ResourceLocation design, int[] colors) {
        registerFishingGlint(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void applyFishingGlint(ItemStack stack) {
        Data data = FISHING_GLINTS.get(stack.getItem());
        if (data == null) return;
        write(stack, data.design(), data.colors(), data.speed(), data.interpolate(), data.patternScale(), data.simultaneous());
    }

    public static final Map<Item, Data> MOB_DROP_GLINTS = new HashMap<>();

    public static void registerMobDropGlint(Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        MOB_DROP_GLINTS.put(item, new Data(design, colors, speed, interpolate, patternScale, simultaneous));
    }

    public static void registerMobDropGlint(Item item, ResourceLocation design, int[] colors) {
        registerMobDropGlint(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void applyMobDropGlint(ItemStack stack) {
        Data data = MOB_DROP_GLINTS.get(stack.getItem());
        if (data == null) return;
        write(stack, data.design(), data.colors(), data.speed(), data.interpolate(), data.patternScale(), data.simultaneous());
    }

    public static final Map<ResourceLocation, Map<Item, Data>> LOOT_GLINTS = new HashMap<>();

    public static void registerLootGlint(ResourceLocation lootTable, Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        LOOT_GLINTS.computeIfAbsent(lootTable, k -> new HashMap<>()).put(item, new Data(design, colors, speed, interpolate, patternScale, simultaneous));
    }

    public static void registerLootGlint(ResourceLocation lootTable, Item item, ResourceLocation design, int[] colors) {
        registerLootGlint(lootTable, item, design, colors, 1.0f, true, 1.0f, true);
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
        LOGGER.info("[{}/CustomGlint] Generating grayscale texture: design={}", MOD_ID, design);
        Minecraft mc = Minecraft.getInstance();
        NativeImage source;
        try {
            var resource = mc.getResourceManager().getResource(design);
            if (resource.isEmpty()) {
                LOGGER.warn("[{}/CustomGlint] Design texture not found: {}", MOD_ID, design);
                return design;
            }
            try (InputStream stream = resource.get().open()) {
                source = NativeImage.read(stream);
            }
        } catch (IOException e) {
            LOGGER.error("[{}/CustomGlint] Failed to load design {}: {}", MOD_ID, design, e.getMessage());
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
        ResourceLocation loc = new ResourceLocation(MOD_ID, "glint/" + safePath);
        mc.getTextureManager().register(loc, new DynamicTexture(gray));
        return loc;
    }

    // ── Render types ──────────────────────────────────────────────────────────

    public static SortedMap<RenderType, BufferBuilder> fixedBufferRegistry;

    private static final Map<String, float[]>    GLINT_COLORS     = new HashMap<>();
    private static final Map<String, RenderType> BY_GLINT         = new HashMap<>();
    private static final Map<String, RenderType> BY_ARMOR_GLINT   = new HashMap<>();

    public static RenderType forArmorGlint(Data glint, float[] frameColor, int colorIdx) {
        String key = "armor|" + glint.design() + "|" + Arrays.toString(glint.colors()) + "|" + glint.speed() + "|" + glint.patternScale() + "|" + colorIdx;
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        return BY_ARMOR_GLINT.computeIfAbsent(key, k -> {
            ResourceLocation tex = glint.design();
            RenderType rt = RenderType.create(
                MOD_ID + ":custom_armor_glint",
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
                    .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_armor_glint_texturing", () -> {
                            float phase = (float)colorIdx / Math.max(1, glint.colors().length);
                            long t = (long)(Util.getMillis() * 8.0 * glint.speed());
                            float f  = (float)(t % 110000L) / 110000.0F + phase;
                            float f1 = (float)(t % 30000L)  /  30000.0F;
                            Matrix4f m = new Matrix4f().translation(-f, f1, 0.0F);
                            m.rotateZ((float)(Math.PI / 3.0));
                            m.translate(f, -f1, 0.0F);
                            m.rotateZ((float)(Math.PI / 3.0));
                            m.translate(-f, f1, 0.0F);
                            m.rotateZ((float)(Math.PI / 3.0));
                            m.translate(f, f1, 0.0F);
                            m.scale(0.16f * glint.patternScale());
                            RenderSystem.setTextureMatrix(m);
                        }, RenderSystem::resetTextureMatrix))
                    .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            return rt;
        });
    }

    public static RenderType forGlint(Data glint, float[] frameColor, boolean isItem, int colorIdx) {
        // isItem=true → flat item model (sword, tool, etc.) → scale 8.0 matches vanilla glint().
        // isItem=false → 3D entity model (trident, etc.) → scale 0.16 matches vanilla entityGlint().
        // Trident issue: always using 8.0 caused tiny tiling on 3D model faces.
        float scale = isItem ? 8.0f : 0.16f;
        String key = glint.design() + "|" + Arrays.toString(glint.colors()) + "|" + glint.speed() + "|" + glint.interpolate() + "|" + isItem + "|" + glint.patternScale() + "|" + colorIdx;
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        return BY_GLINT.computeIfAbsent(key, k -> {
            ResourceLocation tex = glint.design();
            RenderType rt = RenderType.create(
                MOD_ID + ":custom_glint",
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
                    .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_glint_texturing", () -> {
                            float phase = (float)colorIdx / Math.max(1, glint.colors().length);
                            long t = (long)(Util.getMillis() * 8.0 * glint.speed());
                            float f  = (float)(t % 110000L) / 110000.0F + phase;
                            float f1 = (float)(t % 30000L)  /  30000.0F;
                            Matrix4f m = new Matrix4f().translation(-f, 0.0F, 0.0F);
                            m.rotateZ((float)(Math.PI / 3.0));
                            m.translate(f, 0.0F, 0.0F);
                            m.rotateZ((float)(Math.PI / 3.0));
                            m.translate(-f, 0.0F, 0.0F);
                            m.rotateZ((float)(Math.PI / 3.0));
                            m.translate(f + f1, 0.0F, 0.0F);
                            m.scale(scale * glint.patternScale());
                            RenderSystem.setTextureMatrix(m);
                        }, RenderSystem::resetTextureMatrix))
                    .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            return rt;
        });
    }

    public static int computeAnimatedColor(Data glint) {
        int[] colors = glint.colors();
        if (colors.length == 1) return colors[0];
        Minecraft mc = Minecraft.getInstance();
        long gameTime = mc.level != null ? mc.level.getGameTime() : 0;
        float totalTicks = (20.0f * colors.length) / glint.speed();
        float t = (gameTime % Math.max(1L, (long) totalTicks)) / totalTicks * colors.length;
        int idx = (int) t % colors.length;
        if (!glint.interpolate()) return colors[idx];
        float frac = t - (int) t;
        int c1 = colors[idx], c2 = colors[(idx + 1) % colors.length];
        int a = (int)(((c1 >> 24) & 0xFF) * (1 - frac) + ((c2 >> 24) & 0xFF) * frac);
        int r = (int)(((c1 >> 16) & 0xFF) * (1 - frac) + ((c2 >> 16) & 0xFF) * frac);
        int g = (int)(((c1 >>  8) & 0xFF) * (1 - frac) + ((c2 >>  8) & 0xFF) * frac);
        int b = (int)((c1         & 0xFF) * (1 - frac) + (c2         & 0xFF) * frac);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private CustomGlint() { super("", () -> {}, () -> {}); }
}
