// MIT License — Copyright (c) 2026 Likely Tuna | TunaMods — see LICENSE.txt
package net.tunamods.customglint.common;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HorseModel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
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

    public static final ResourceLocation[] PATTERNS = {
            VANILLA,
            ARCS, AURORA, BLOBS, CASCADE, CHECKER, CHEVRON, CORAL, CRACKS,
            CROSSHATCH, CRYSTAL, DEBRIS, DIAMONDS, DUNES, EMBER, FEATHER, FIRE,
            FROST, GLITCH, GLOW, GRID, HALO, HEXAGON, LIGHTNING, MARBLE,
            MATRIX, MESH, MOSAIC, NET, OIL, PETAL, PLASMA, PLATE,
            PRISM, PULSE, RIPPLE, SAND, SCALES, SHEEN, SHIMMER, SILK,
            SLASH, SMOKE, SOLID, SPARKLE, STARS, STATIC, STRIPES, SWIRL,
            TIDE, TILE, VEIN, WAVE, WEAVE, ZIGZAG
    };

    public static final int[] VIBRANT_COLORS = {
            RED, ORANGE, YELLOW, LIME, GREEN, CYAN, LIGHT_BLUE, BLUE, PURPLE, MAGENTA, PINK
    };

    public static final int[] ALL_COLORS = {
            RED, ORANGE, YELLOW, LIME, GREEN, CYAN, LIGHT_BLUE, BLUE, PURPLE, MAGENTA, PINK,
            BROWN, WHITE, LIGHT_GRAY, GRAY, BLACK
    };

    // ── NBT ──────────────────────────────────────────────────────────────────

    private static final String TAG              = MOD_ID;
    private static final String LAYERS_KEY       = "layers";
    private static final String GLOWING_KEY      = "glowing";
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
        CompoundTag existing = stack.hasTag() ? stack.getTag().getCompound(TAG) : null;
        if (existing != null && existing.contains(GLOWING_KEY))
            tag.putBoolean(GLOWING_KEY, existing.getBoolean(GLOWING_KEY));
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

    public static boolean isGlowing(ItemStack stack) {
        if (stack.isEmpty() || !stack.hasTag()) return false;
        CompoundTag root = stack.getTag();
        if (!root.contains(TAG)) return false;
        return root.getCompound(TAG).getBoolean(GLOWING_KEY);
    }

    public static void setGlowing(ItemStack stack, boolean glowing) {
        CompoundTag root = stack.getOrCreateTag();
        CompoundTag glintTag = root.contains(TAG) ? root.getCompound(TAG) : new CompoundTag();
        glintTag.putBoolean(GLOWING_KEY, glowing);
        root.put(TAG, glintTag);
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
    public static final ThreadLocal<ItemStack> CURRENT_ITEM_STACK = new ThreadLocal<>();
    public static final ThreadLocal<float[]> COLOR_BUF = ThreadLocal.withInitial(() -> new float[4]);

    private static final Map<String, float[]>    GLINT_COLORS          = new HashMap<>();
    private static final Map<String, RenderType> BY_GLINT              = new HashMap<>();
    private static final Map<String, RenderType> BY_ARMOR_GLINT        = new HashMap<>();
    private static final Map<String, RenderType> BY_HORSE_ARMOR_GLINT  = new HashMap<>();

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

    // Horse armor uses entityCutoutNoCull (no polygon offset / no VIEW_OFFSET_Z_LAYERING).
    // forArmorGlint uses EQUAL + VIEW_OFFSET_Z_LAYERING — wrong offset → invisible on horses.
    // This variant keeps EQUAL + NO_LAYERING so depth matches, and scale 1.0 matches forArmorGlint visually.
    public static RenderType forHorseArmorGlint(Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        Layer layer = glint.layers()[layerIdx];
        if (getTexture(layer.design()) == null) return null;
        String key = "horse|" + layer.design() + "|" + Arrays.toString(layer.colors()) + "|" + layer.speed() + "|" + layer.patternScale() + "|" + colorIdx + "|" + layerIdx;
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        return BY_HORSE_ARMOR_GLINT.computeIfAbsent(key, k -> {
            ResourceLocation tex = layer.design();
            RenderType rt = RenderType.create(
                    MOD_ID + ":custom_horse_armor_glint",
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
                            .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_horse_armor_glint_texturing", () -> {
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
                                m.scale(layer.patternScale());
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
        // isItem=false → 3D entity model (trident, etc.) → 1.0 gives visible pattern detail;
        // vanilla entityGlint() uses 0.16 but that tiles too infrequently for custom designs.
        float scale = isItem ? 8.0f : 1.0f;
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

    // ── Outline rendering ─────────────────────────────────────────────────────

    /** Guards re-entrance: set true during outline stencil passes so applyGlint skips the item. */
    public static final ThreadLocal<Boolean> IN_OUTLINE = ThreadLocal.withInitial(() -> false);

    private static final Map<ResourceLocation, RenderType> BY_OUTLINE = new HashMap<>();

    /** Outline RenderType: uses the vanilla outline shader with vertex colors, writes to the main framebuffer. */
    public static RenderType forOutline(ResourceLocation texture) {
        return BY_OUTLINE.computeIfAbsent(texture, tex -> {
            RenderType rt = RenderType.create(
                    MOD_ID + ":glint_outline",
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_OUTLINE_SHADER)
                            .setTextureState(new TextureStateShard(tex, false, false))
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setOutputState(MAIN_TARGET)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            return rt;
        });
    }

    /** Returns the current animated color (ARGB) from layer 0 of the glint, for use as outline color. */
    public static int glintOutlineColor(Data glint) {
        return computeAnimatedColor(glint, 0);
    }

    /**
     * Stencil-based colored outline for entity/armor models.
     *
     * Pass 1 (stencil write): render model with colorMask=false so only the stencil buffer is
     * written. Stencil func is ALWAYS so the stencil test never blocks a write. StencilOp uses
     * dpfail=REPLACE (not KEEP) — armor is rendered with VIEW_OFFSET_Z_LAYERING (polygonOffset
     * -1,-10), which shifts its depth slightly toward the camera. Our stencil pass renders the same
     * geometry without that offset, so its fragments sit slightly BEHIND the already-written armor
     * depth. That causes LEQUAL depth test to fail. With dpfail=KEEP the stencil would never be
     * written and the outline pass (stencil==0) would render everywhere → solid face. dpfail=REPLACE
     * writes stencil=1 regardless of depth test outcome, correctly marking the model's silhouette.
     *
     * Pass 2 (outline): renders scaled model (1.04× — 4% larger) only where stencil==0, i.e.
     * the ring of pixels outside the original silhouette.
     *
     * texture: caller should pass the actual armor layer texture (not SOLID) so the outline shader
     * discards transparent pixels (areas with no armor coverage) and the ring follows the real armor
     * shape instead of the full model-bone geometry.
     *
     * TRIED, did not work:
     * - SOLID texture: stencil/outline follows full bone geometry (arms extend past pauldrons).
     * - dpfail=KEEP: polygon-offset depth mismatch causes stencil pass to always fail → solid face.
     * - scale 1.06: outline too thick for armor; 1.025 too thin; 1.04 is the current sweet spot.
     */
    public static void doModelOutline(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, EntityModel<?> model, ResourceLocation texture, Data glint, EquipmentSlot slot) {
        int color = glintOutlineColor(glint);
        float oR = ((color >> 16) & 0xFF) / 255.0f;
        float oG = ((color >>  8) & 0xFF) / 255.0f;
        float oB = ( color        & 0xFF) / 255.0f;

        RenderType outlineType = forOutline(texture);
        Minecraft.getInstance().getMainRenderTarget().enableStencil();

        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilMask(0xFF);
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        GL11.glColorMask(false, false, false, false);
        GL11.glDepthMask(false);
        GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
        // dpfail=REPLACE so stencil writes even when depth test fails (armor polygon offset
        // makes the stencil-pass depth sit slightly behind the buffer — see note above).
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_REPLACE, GL11.GL_REPLACE);

        model.renderToBuffer(poseStack, buffer.getBuffer(outlineType), packedLight, OverlayTexture.NO_OVERLAY, 1, 1, 1, 0);
        if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch(outlineType);

        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);
        GL11.glStencilFunc(GL11.GL_EQUAL, 0, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

        float outlineScale = slot == EquipmentSlot.FEET ? 1.03f : 1.04f;
        poseStack.pushPose();
        if (model instanceof HorseModel) {
            // Horse model bones are positioned far from the pose origin used for humanoid armor;
            // the +0.9/-0.95 pivot for humanoid distorts the horse outline. Scale around the pose
            // origin (entity feet) — outline ring stays concentric with the horse silhouette.
            poseStack.scale(outlineScale, outlineScale, outlineScale);
        } else {
            poseStack.translate(0.0f, -0.95f, 0.0f); // -0.9 - 0.05 downward alignment correction
            poseStack.scale(outlineScale, outlineScale, outlineScale);
            poseStack.translate(0.0f, 0.9f, 0.0f);
        }
        model.renderToBuffer(poseStack, buffer.getBuffer(outlineType), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, oR, oG, oB, 1.0f);
        if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch(outlineType);
        poseStack.popPose();

        GL11.glDisable(GL11.GL_STENCIL_TEST);
    }

    /**
     * Stencil-based colored outline for item/weapon/tool renders.
     * Skips GUI context. Uses an isolated BufferSource for the stencil pass to avoid
     * flushing other items in the main batch while colorMask is disabled. Uses IN_OUTLINE
     * to prevent recursive glint application during the stencil/outline passes.
     */
    public static void doItemOutline(ItemStack stack, ItemDisplayContext displayContext,
            PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (displayContext == ItemDisplayContext.GUI) return;
        Data glint = read(stack);
        if (glint == null) return;

        int color = glintOutlineColor(glint);
        float oR = ((color >> 16) & 0xFF) / 255.0f;
        float oG = ((color >>  8) & 0xFF) / 255.0f;
        float oB = ( color        & 0xFF) / 255.0f;
        int rByte = (int)(oR * 255), gByte = (int)(oG * 255), bByte = (int)(oB * 255);

        Minecraft mc = Minecraft.getInstance();
        // Pass mc.player so item-model overrides that depend on entity state (e.g. trident's
        // "throwing:1" predicate) resolve to the same model variant used in the original render.
        // With null, the throwing predicate always returns 0 → wrong display transforms → inverted outline.
        net.minecraft.client.player.LocalPlayer renderPlayer = mc.player;
        // BEWLR items (trident, shield, crossbow in 3D) use entity textures whose UVs don't map
        // to the blocks atlas. white.png (no alpha-discard) fills the full 3D model geometry so
        // the stencil and scale-based outline work correctly. Flat sprite items use the blocks
        // atlas for pixel-accurate silhouette outlines via translated passes.
        boolean customRenderer = mc.getItemRenderer().getModel(stack, mc.level, renderPlayer, 0).isCustomRenderer()
                && !((stack.getItem() == Items.TRIDENT || stack.getItem() == Items.SPYGLASS)
                     && (displayContext == ItemDisplayContext.GROUND || displayContext == ItemDisplayContext.FIXED));
        RenderType outlineType = forOutline(customRenderer
                ? new ResourceLocation("textures/misc/white.png")
                : new ResourceLocation("minecraft", "textures/atlas/blocks.png"));
        RenderType stencilType = outlineType;
        if (buffer instanceof MultiBufferSource.BufferSource preBs) preBs.endBatch();
        mc.getMainRenderTarget().enableStencil();
        IN_OUTLINE.set(true);
        // Pass the original displayContext to renderStatic so vanilla applies the correct
        // transform AND the context-dependent model swap (GROUND/FIXED swaps trident/spyglass
        // to the flat 2D icon). Using NONE here forced the 3D custom renderer for tridents on
        // the ground and bypassed flat-context transforms for sword previews.
        try {
            GL11.glEnable(GL11.GL_STENCIL_TEST);
            GL11.glStencilMask(0xFF);
            GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
            GL11.glColorMask(false, false, false, false);
            GL11.glDepthMask(false);
            GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_REPLACE, GL11.GL_REPLACE);

            // Route the stencil pass through outlineType (MAIN_TARGET) so the stencil is written to
            // the same FBO the outline pass reads from. Using native item render types here would bind
            // their own output FBO (ITEM_ENTITY_TARGET, TRANSLUCENT_TARGET, etc.), leaving the main
            // FBO stencil all-zeros and causing the outline to fill the entire item silhouette.
            // alpha=0 via ColorOverrideConsumer makes the stencil geometry invisible (same as
            // doModelOutline passing alpha=0 to renderToBuffer), so there is no visual double-render.
            float[] minMax = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                               Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
            MultiBufferSource stencilSrc = rt -> new AABBTrackingConsumer(new ColorOverrideConsumer(buffer.getBuffer(stencilType), 0, 0, 0, 0), minMax);
            poseStack.pushPose();
            boolean leftHand = displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND || displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
            mc.getItemRenderer().renderStatic(renderPlayer, stack, displayContext, leftHand, poseStack, stencilSrc, mc.level, packedLight, packedOverlay, 0);
            poseStack.popPose();
            if (buffer instanceof MultiBufferSource.BufferSource bs2) bs2.endBatch(stencilType);

            GL11.glColorMask(true, true, true, true);
            // Re-enable depth writes for the outline pass. The ring pixels are in stencil==0
            // space (outside the item body), where the depth buffer holds background depth.
            // Writing the outline's depth here claims those pixels so a second glowing item
            // behind this one fails LEQUAL and cannot draw its outline ring over ours.
            // This is safe: endBatch() above already flushed the original geometry to the
            // depth buffer, so the outline (stencil==0, outside body) cannot occlude it.
            GL11.glDepthMask(true);
            GL11.glStencilFunc(GL11.GL_EQUAL, 0, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

            if (minMax[3] > minMax[0]) {
                MultiBufferSource outlineSrc = rt -> new ColorOverrideConsumer(buffer.getBuffer(outlineType), rByte, gByte, bByte, 255);
                RenderSystem.setShaderColor(oR, oG, oB, 1.0f);
                if (customRenderer) {
                    // BEWLR 3D model: scale-based outline around AABB center (white.png, no alpha-discard).
                    float cx = (minMax[0] + minMax[3]) * 0.5f;
                    float cy = (minMax[1] + minMax[4]) * 0.5f;
                    float cz = (minMax[2] + minMax[5]) * 0.5f;
                    poseStack.pushPose();
                    boolean isTrident1P = stack.getItem() == Items.TRIDENT
                            && (displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                                || displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND);
                    boolean isTrident3P = stack.getItem() == Items.TRIDENT
                            && (displayContext == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                                || displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND);
                    // ── 1st person trident outline offset (tune these) ──
                    float cxOff1P = 0.0f;   // X: right+, left-
                    float cyOff1P = -0.04f; // Y: up+,   down-
                    float czOff1P = 0.02f;  // Z: fwd+,  back-
                    // ── 3rd person trident outline offset (tune these) ──
                    float cxOff3P = 0.0f;   // X: right+, left-
                    float cyOff3P = 0.0f;   // Y: up+,   down-
                    float czOff3P = 0.0f;   // Z: fwd+,  back-
                    float cxOff = isTrident1P ? cxOff1P : isTrident3P ? cxOff3P : 0.0f;
                    float cyOff = isTrident1P ? cyOff1P : isTrident3P ? cyOff3P : 0.0f;
                    float czOff = isTrident1P ? czOff1P : isTrident3P ? czOff3P : 0.0f;
                    poseStack.last().pose().mulLocal(new Matrix4f().translate(cx + cxOff, cy + cyOff, cz + czOff).scale(1.06f, 1.06f, 1.06f).translate(-cx, -cy, -cz));
                    mc.getItemRenderer().renderStatic(renderPlayer, stack, displayContext, leftHand, poseStack, outlineSrc, mc.level, LightTexture.FULL_BRIGHT, packedOverlay, 0);
                    if (buffer instanceof MultiBufferSource.BufferSource bsR) bsR.endBatch(outlineType);
                    poseStack.popPose();
                } else {
                    // Flat 2D sprite: translate ~1 pixel in 4 eye-space directions (blocks atlas, alpha-discard).
                    // Only opaque pixels shifted outside stencil=1 silhouette receive glow.
                    boolean is1P = displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                            || displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
                    float stepScale = is1P ? 1.0f : 1.0f; // tune: 1P step vs 3P/ground step
                    float czOff    = is1P ? -0.01f : 0.0f; // tune: push outline toward camera in 1P (neg = forward)
                    float dw = (minMax[3] - minMax[0]) / 16.0f * stepScale;
                    float dh = (minMax[4] - minMax[1]) / 16.0f * stepScale;
                    poseStack.pushPose();
                    poseStack.last().pose().mulLocal(new Matrix4f().translate(dw, 0, czOff));
                    mc.getItemRenderer().renderStatic(renderPlayer, stack, displayContext, leftHand, poseStack, outlineSrc, mc.level, LightTexture.FULL_BRIGHT, packedOverlay, 0);
                    if (buffer instanceof MultiBufferSource.BufferSource bsE) bsE.endBatch(outlineType);
                    poseStack.popPose();
                    poseStack.pushPose();
                    poseStack.last().pose().mulLocal(new Matrix4f().translate(-dw, 0, czOff));
                    mc.getItemRenderer().renderStatic(renderPlayer, stack, displayContext, leftHand, poseStack, outlineSrc, mc.level, LightTexture.FULL_BRIGHT, packedOverlay, 0);
                    if (buffer instanceof MultiBufferSource.BufferSource bsW) bsW.endBatch(outlineType);
                    poseStack.popPose();
                    poseStack.pushPose();
                    poseStack.last().pose().mulLocal(new Matrix4f().translate(0, dh, czOff));
                    mc.getItemRenderer().renderStatic(renderPlayer, stack, displayContext, leftHand, poseStack, outlineSrc, mc.level, LightTexture.FULL_BRIGHT, packedOverlay, 0);
                    if (buffer instanceof MultiBufferSource.BufferSource bsN) bsN.endBatch(outlineType);
                    poseStack.popPose();
                    poseStack.pushPose();
                    poseStack.last().pose().mulLocal(new Matrix4f().translate(0, -dh, czOff));
                    mc.getItemRenderer().renderStatic(renderPlayer, stack, displayContext, leftHand, poseStack, outlineSrc, mc.level, LightTexture.FULL_BRIGHT, packedOverlay, 0);
                    if (buffer instanceof MultiBufferSource.BufferSource bsS) bsS.endBatch(outlineType);
                    poseStack.popPose();
                }
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            }

            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
        } finally {
            IN_OUTLINE.set(false);
        }
    }

    /** Wraps a VertexConsumer and overrides vertex colors with a fixed RGBA value. */
    private static final class ColorOverrideConsumer implements VertexConsumer {
        private final VertexConsumer wrapped;
        private final int r, g, b, a;
        ColorOverrideConsumer(VertexConsumer wrapped, int r, int g, int b, int a) {
            this.wrapped = wrapped; this.r = r; this.g = g; this.b = b; this.a = a;
        }
        @Override public VertexConsumer vertex(double x, double y, double z) { wrapped.vertex(x, y, z); return this; }
        @Override public VertexConsumer vertex(Matrix4f matrix, float x, float y, float z) { wrapped.vertex(matrix, x, y, z); return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return wrapped.color(this.r, this.g, this.b, this.a); }
        @Override public VertexConsumer color(float r, float g, float b, float a) { return wrapped.color(this.r, this.g, this.b, this.a); }
        @Override public VertexConsumer uv(float u, float v) { return wrapped.uv(u, v); }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public void endVertex() { wrapped.endVertex(); }
        @Override public void defaultColor(int r, int g, int b, int a) {}
        @Override public void unsetDefaultColor() {}
    }

    /** Wraps a VertexConsumer and records each vertex's eye-space position into a shared
     *  {minX,minY,minZ,maxX,maxY,maxZ} accumulator. Used during the outline stencil pass to
     *  derive an AABB-centered pivot for the dilation scale. */
    private static final class AABBTrackingConsumer implements VertexConsumer {
        private final VertexConsumer wrapped;
        private final float[] minMax;
        AABBTrackingConsumer(VertexConsumer wrapped, float[] minMax) {
            this.wrapped = wrapped; this.minMax = minMax;
        }
        private void track(float x, float y, float z) {
            if (x < minMax[0]) minMax[0] = x;
            if (y < minMax[1]) minMax[1] = y;
            if (z < minMax[2]) minMax[2] = z;
            if (x > minMax[3]) minMax[3] = x;
            if (y > minMax[4]) minMax[4] = y;
            if (z > minMax[5]) minMax[5] = z;
        }
        @Override public VertexConsumer vertex(double x, double y, double z) {
            track((float) x, (float) y, (float) z);
            wrapped.vertex(x, y, z);
            return this;
        }
        @Override public VertexConsumer vertex(Matrix4f m, float x, float y, float z) {
            float tx = m.m00() * x + m.m10() * y + m.m20() * z + m.m30();
            float ty = m.m01() * x + m.m11() * y + m.m21() * z + m.m31();
            float tz = m.m02() * x + m.m12() * y + m.m22() * z + m.m32();
            track(tx, ty, tz);
            wrapped.vertex(m, x, y, z);
            return this;
        }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return wrapped.color(r, g, b, a); }
        @Override public VertexConsumer color(float r, float g, float b, float a) { return wrapped.color(r, g, b, a); }
        @Override public VertexConsumer uv(float u, float v) { return wrapped.uv(u, v); }
        @Override public VertexConsumer overlayCoords(int u, int v) { return wrapped.overlayCoords(u, v); }
        @Override public VertexConsumer uv2(int u, int v) { return wrapped.uv2(u, v); }
        @Override public VertexConsumer normal(float x, float y, float z) { return wrapped.normal(x, y, z); }
        @Override public void endVertex() { wrapped.endVertex(); }
        @Override public void defaultColor(int r, int g, int b, int a) { wrapped.defaultColor(r, g, b, a); }
        @Override public void unsetDefaultColor() { wrapped.unsetDefaultColor(); }
    }

    private CustomGlint() { super("", () -> {}, () -> {}); }
}
