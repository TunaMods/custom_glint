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
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
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

    // ── Layer ─────────────────────────────────────────────────────────────────

    public record Layer(ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {}

    // ── Data ─────────────────────────────────────────────────────────────────

    public record Data(Layer[] layers) {}

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
    public static final ResourceLocation ARCS      = new ResourceLocation(MOD_ID, "textures/glint/arcs.png");
    public static final ResourceLocation AURORA    = new ResourceLocation(MOD_ID, "textures/glint/aurora.png");
    public static final ResourceLocation BLOBS     = new ResourceLocation(MOD_ID, "textures/glint/blobs.png");
    public static final ResourceLocation CASCADE   = new ResourceLocation(MOD_ID, "textures/glint/cascade.png");
    public static final ResourceLocation CHECKER   = new ResourceLocation(MOD_ID, "textures/glint/checker.png");
    public static final ResourceLocation CHEVRON   = new ResourceLocation(MOD_ID, "textures/glint/chevron.png");
    public static final ResourceLocation CORAL     = new ResourceLocation(MOD_ID, "textures/glint/coral.png");
    public static final ResourceLocation CRACKS    = new ResourceLocation(MOD_ID, "textures/glint/cracks.png");
    public static final ResourceLocation CROSSHATCH = new ResourceLocation(MOD_ID, "textures/glint/crosshatch.png");
    public static final ResourceLocation CRYSTAL   = new ResourceLocation(MOD_ID, "textures/glint/crystal.png");
    public static final ResourceLocation DEBRIS    = new ResourceLocation(MOD_ID, "textures/glint/debris.png");
    public static final ResourceLocation DIAMONDS  = new ResourceLocation(MOD_ID, "textures/glint/diamonds.png");
    public static final ResourceLocation DUNES     = new ResourceLocation(MOD_ID, "textures/glint/dunes.png");
    public static final ResourceLocation EMBER     = new ResourceLocation(MOD_ID, "textures/glint/ember.png");
    public static final ResourceLocation FEATHER   = new ResourceLocation(MOD_ID, "textures/glint/feather.png");
    public static final ResourceLocation FIRE      = new ResourceLocation(MOD_ID, "textures/glint/fire.png");
    public static final ResourceLocation FROST     = new ResourceLocation(MOD_ID, "textures/glint/frost.png");
    public static final ResourceLocation GLITCH    = new ResourceLocation(MOD_ID, "textures/glint/glitch.png");
    public static final ResourceLocation GLOW      = new ResourceLocation(MOD_ID, "textures/glint/glow.png");
    public static final ResourceLocation GRID      = new ResourceLocation(MOD_ID, "textures/glint/grid.png");
    public static final ResourceLocation HALO      = new ResourceLocation(MOD_ID, "textures/glint/halo.png");
    public static final ResourceLocation HEXAGON   = new ResourceLocation(MOD_ID, "textures/glint/hexagon.png");
    public static final ResourceLocation LIGHTNING = new ResourceLocation(MOD_ID, "textures/glint/lightning.png");
    public static final ResourceLocation MARBLE    = new ResourceLocation(MOD_ID, "textures/glint/marble.png");
    public static final ResourceLocation MATRIX    = new ResourceLocation(MOD_ID, "textures/glint/matrix.png");
    public static final ResourceLocation MESH      = new ResourceLocation(MOD_ID, "textures/glint/mesh.png");
    public static final ResourceLocation MOSAIC    = new ResourceLocation(MOD_ID, "textures/glint/mosaic.png");
    public static final ResourceLocation NET       = new ResourceLocation(MOD_ID, "textures/glint/net.png");
    public static final ResourceLocation OIL       = new ResourceLocation(MOD_ID, "textures/glint/oil.png");
    public static final ResourceLocation PETAL     = new ResourceLocation(MOD_ID, "textures/glint/petal.png");
    public static final ResourceLocation PLASMA    = new ResourceLocation(MOD_ID, "textures/glint/plasma.png");
    public static final ResourceLocation PLATE     = new ResourceLocation(MOD_ID, "textures/glint/plate.png");
    public static final ResourceLocation PRISM     = new ResourceLocation(MOD_ID, "textures/glint/prism.png");
    public static final ResourceLocation PULSE     = new ResourceLocation(MOD_ID, "textures/glint/pulse.png");
    public static final ResourceLocation RIPPLE    = new ResourceLocation(MOD_ID, "textures/glint/ripple.png");
    public static final ResourceLocation SAND      = new ResourceLocation(MOD_ID, "textures/glint/sand.png");
    public static final ResourceLocation SCALES    = new ResourceLocation(MOD_ID, "textures/glint/scales.png");
    public static final ResourceLocation SHEEN     = new ResourceLocation(MOD_ID, "textures/glint/sheen.png");
    public static final ResourceLocation SHIMMER   = new ResourceLocation(MOD_ID, "textures/glint/shimmer.png");
    public static final ResourceLocation SILK      = new ResourceLocation(MOD_ID, "textures/glint/silk.png");
    public static final ResourceLocation SLASH     = new ResourceLocation(MOD_ID, "textures/glint/slash.png");
    public static final ResourceLocation SMOKE     = new ResourceLocation(MOD_ID, "textures/glint/smoke.png");
    public static final ResourceLocation SOLID     = new ResourceLocation(MOD_ID, "textures/glint/solid.png");
    public static final ResourceLocation SPARKLE   = new ResourceLocation(MOD_ID, "textures/glint/sparkle.png");
    public static final ResourceLocation STARS     = new ResourceLocation(MOD_ID, "textures/glint/stars.png");
    public static final ResourceLocation STATIC    = new ResourceLocation(MOD_ID, "textures/glint/static.png");
    public static final ResourceLocation STRIPES   = new ResourceLocation(MOD_ID, "textures/glint/stripes.png");
    public static final ResourceLocation SWIRL     = new ResourceLocation(MOD_ID, "textures/glint/swirl.png");
    public static final ResourceLocation TIDE      = new ResourceLocation(MOD_ID, "textures/glint/tide.png");
    public static final ResourceLocation TILE      = new ResourceLocation(MOD_ID, "textures/glint/tile.png");
    public static final ResourceLocation VEIN      = new ResourceLocation(MOD_ID, "textures/glint/vein.png");
    public static final ResourceLocation WAVE      = new ResourceLocation(MOD_ID, "textures/glint/wave.png");
    public static final ResourceLocation WEAVE     = new ResourceLocation(MOD_ID, "textures/glint/weave.png");
    public static final ResourceLocation ZIGZAG    = new ResourceLocation(MOD_ID, "textures/glint/zigzag.png");

    // ── NBT ──────────────────────────────────────────────────────────────────

    private static final String TAG             = MOD_ID;
    private static final String LAYERS_KEY      = "layers";
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

        float globalSpeed = tag.contains(SPEED_KEY) ? tag.getFloat(SPEED_KEY) : 1.0f;
        if (globalSpeed <= 0) globalSpeed = 1.0f;
        boolean globalInterpolate = !tag.contains(INTERPOLATE_KEY) || tag.getBoolean(INTERPOLATE_KEY);
        float globalScale = tag.contains(SCALE_KEY) ? tag.getFloat(SCALE_KEY) : 1.0f;
        if (globalScale <= 0) globalScale = 1.0f;
        boolean globalSimultaneous = !tag.contains(SIMULTANEOUS_KEY) || tag.getBoolean(SIMULTANEOUS_KEY);

        Layer[] layers;
        if (tag.contains(LAYERS_KEY)) {
            ListTag list = tag.getList(LAYERS_KEY, Tag.TAG_COMPOUND);
            if (list.isEmpty()) return null;
            layers = new Layer[list.size()];
            for (int i = 0; i < list.size(); i++) {
                CompoundTag lt = list.getCompound(i);
                String design = lt.getString(DESIGN_KEY);
                if (design.isEmpty()) return null;
                if (!lt.contains(COLORS_KEY)) return null;
                int[] colors = lt.getIntArray(COLORS_KEY);
                if (colors.length == 0) return null;
                float speed = lt.contains(SPEED_KEY) ? lt.getFloat(SPEED_KEY) : globalSpeed;
                if (speed <= 0) speed = 1.0f;
                boolean interpolate = lt.contains(INTERPOLATE_KEY) ? lt.getBoolean(INTERPOLATE_KEY) : globalInterpolate;
                float patternScale = lt.contains(SCALE_KEY) ? lt.getFloat(SCALE_KEY) : globalScale;
                if (patternScale <= 0) patternScale = 1.0f;
                boolean simultaneous = lt.contains(SIMULTANEOUS_KEY) ? lt.getBoolean(SIMULTANEOUS_KEY) : globalSimultaneous;
                layers[i] = new Layer(new ResourceLocation(design), colors, speed, interpolate, patternScale, simultaneous);
            }
        } else {
            // backward compat: old single-layer format
            String design = tag.getString(DESIGN_KEY);
            if (design.isEmpty()) return null;
            if (!tag.contains(COLORS_KEY)) return null;
            int[] colors = tag.getIntArray(COLORS_KEY);
            if (colors.length == 0) return null;
            layers = new Layer[]{ new Layer(new ResourceLocation(design), colors, globalSpeed, globalInterpolate, globalScale, globalSimultaneous) };
        }

        return new Data(layers);
    }

    public static boolean has(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains(TAG);
    }

    public static void write(ItemStack stack, Layer[] layers) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Layer layer : layers) {
            CompoundTag lt = new CompoundTag();
            lt.putString(DESIGN_KEY, layer.design().toString());
            lt.putIntArray(COLORS_KEY, layer.colors());
            lt.putFloat(SPEED_KEY, layer.speed());
            lt.putBoolean(INTERPOLATE_KEY, layer.interpolate());
            lt.putFloat(SCALE_KEY, layer.patternScale());
            lt.putBoolean(SIMULTANEOUS_KEY, layer.simultaneous());
            list.add(lt);
        }
        tag.put(LAYERS_KEY, list);
        stack.getOrCreateTag().put(TAG, tag);
    }

    public static void write(ItemStack stack, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        write(stack, new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous) });
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

    //

    public static final Map<Item, Data> CRAFT_GLINTS = new HashMap<>();

    public static void registerCraftGlint(Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        CRAFT_GLINTS.put(item, new Data(new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous) }));
    }

    public static void registerCraftGlint(Item item, ResourceLocation design, int[] colors) {
        registerCraftGlint(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void applyCraftGlint(ItemStack stack) {
        Data data = CRAFT_GLINTS.get(stack.getItem());
        if (data == null) return;
        write(stack, data.layers());
    }

    //

    public static final Map<Item, Data> FISHING_GLINTS = new HashMap<>();

    public static void registerFishingGlint(Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        FISHING_GLINTS.put(item, new Data(new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous) }));
    }

    public static void registerFishingGlint(Item item, ResourceLocation design, int[] colors) {
        registerFishingGlint(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void applyFishingGlint(ItemStack stack) {
        Data data = FISHING_GLINTS.get(stack.getItem());
        if (data == null) return;
        write(stack, data.layers());
    }

    //

    public static final Map<Item, Data> MOB_DROP_GLINTS = new HashMap<>();

    public static void registerMobDropGlint(Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        MOB_DROP_GLINTS.put(item, new Data(new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous) }));
    }

    public static void registerMobDropGlint(Item item, ResourceLocation design, int[] colors) {
        registerMobDropGlint(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void applyMobDropGlint(ItemStack stack) {
        Data data = MOB_DROP_GLINTS.get(stack.getItem());
        if (data == null) return;
        write(stack, data.layers());
    }

    //

    public static final Map<ResourceLocation, Map<Item, Data>> LOOT_GLINTS = new HashMap<>();

    public static void registerLootGlint(ResourceLocation lootTable, Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        LOOT_GLINTS.computeIfAbsent(lootTable, k -> new HashMap<>()).put(item, new Data(new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous) }));
    }

    public static void registerLootGlint(ResourceLocation lootTable, Item item, ResourceLocation design, int[] colors) {
        registerLootGlint(lootTable, item, design, colors, 1.0f, true, 1.0f, true);
    }

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
    }

    private static ResourceLocation generateTexture(ResourceLocation design) {
        LOGGER.info("[{}/CustomGlint] Generating grayscale texture: design={}", MOD_ID, design);
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
        ResourceLocation loc = new ResourceLocation(MOD_ID, "glint/" + safePath);
        mc.getTextureManager().register(loc, new DynamicTexture(gray));
        return loc;
    }

    // ── Render types ──────────────────────────────────────────────────────────

    public static SortedMap<RenderType, BufferBuilder> fixedBufferRegistry;

    private static final Map<String, float[]>    GLINT_COLORS     = new HashMap<>();
    private static final Map<String, RenderType> BY_GLINT         = new HashMap<>();
    private static final Map<String, RenderType> BY_ARMOR_GLINT   = new HashMap<>();

    public static RenderType forArmorGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        if (getTexture(layer.design()) == null) return null;
        String key = "armor|" + layer.design() + "|" + Arrays.toString(layer.colors()) + "|" + layer.speed() + "|" + layer.patternScale() + "|" + colorIdx;
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        return BY_ARMOR_GLINT.computeIfAbsent(key, k -> {
            ResourceLocation tex = layer.design();
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
                            float phase = (float)colorIdx / Math.max(1, layer.colors().length);
                            long t = (long)(Util.getMillis() * 8.0 * layer.speed());
                            float f  = (float)(t % 110000L) / 110000.0F + phase;
                            float f1 = (float)(t % 30000L)  /  30000.0F;
                            Matrix4f m = new Matrix4f().translation(-f, f1, 0.0F);
                            m.rotateZ((float)(Math.PI / 3.0));
                            m.translate(f, -f1, 0.0F);
                            m.rotateZ((float)(Math.PI / 3.0));
                            m.translate(-f, f1, 0.0F);
                            m.rotateZ((float)(Math.PI / 3.0));
                            m.translate(f, f1, 0.0F);
                            m.scale(1.0f * layer.patternScale());
                            RenderSystem.setTextureMatrix(m);
                        }, RenderSystem::resetTextureMatrix))
                    .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            return rt;
        });
    }

    public static RenderType forGlint(Data glint, int layerIdx, float[] frameColor, boolean isItem, int colorIdx) {
        // isItem=true → flat item model (sword, tool, etc.) → scale 8.0 matches vanilla glint().
        // isItem=false → 3D entity model (trident, etc.) → scale 0.16 matches vanilla entityGlint().
        // Trident issue: always using 8.0 caused tiny tiling on 3D model faces.
        float scale = isItem ? 8.0f : 0.16f;
        Layer layer = glint.layers()[layerIdx];
        if (getTexture(layer.design()) == null) return null;
        String key = layer.design() + "|" + Arrays.toString(layer.colors()) + "|" + layer.speed() + "|" + layer.interpolate() + "|" + isItem + "|" + layer.patternScale() + "|" + colorIdx + "|" + layerIdx;
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        return BY_GLINT.computeIfAbsent(key, k -> {
            ResourceLocation tex = layer.design();
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
                            float phase = (float)colorIdx / Math.max(1, layer.colors().length);
                            long t = (long)(Util.getMillis() * 8.0 * layer.speed());
                            float f  = (float)(t % 110000L) / 110000.0F + phase;
                            float f1 = (float)(t % 30000L)  /  30000.0F;
                            Matrix4f m = new Matrix4f().translation(-f, 0.0F, 0.0F);
                            m.rotateZ((float)(Math.PI / 3.0));
                            m.translate(f, 0.0F, 0.0F);
                            m.rotateZ((float)(Math.PI / 3.0));
                            m.translate(-f, 0.0F, 0.0F);
                            m.rotateZ((float)(Math.PI / 3.0));
                            m.translate(f + f1, 0.0F, 0.0F);
                            m.scale(scale * layer.patternScale());
                            RenderSystem.setTextureMatrix(m);
                        }, RenderSystem::resetTextureMatrix))
                    .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            return rt;
        });
    }

    public static int computeAnimatedColor(Data glint, int layerIdx) {
        Layer layer = glint.layers()[layerIdx];
        int[] colors = layer.colors();
        if (colors.length == 1) return colors[0];
        Minecraft mc = Minecraft.getInstance();
        long gameTime = mc.level != null ? mc.level.getGameTime() : 0;
        float totalTicks = (20.0f * colors.length) / layer.speed();
        float t = (gameTime % Math.max(1L, (long) totalTicks)) / totalTicks * colors.length;
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

    private CustomGlint() { super("", () -> {}, () -> {}); }
}
