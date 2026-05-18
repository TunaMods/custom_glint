package net.tunamods.customglint.common.client;

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
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.HorseModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.tunamods.customglint.common.CustomGlint;
import org.joml.Matrix4f;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentHashMap;

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
        if (fixedBufferRegistry != null) {
            for (RenderType rt : BY_GLINT.values())             fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_ARMOR_GLINT.values())       fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_HORSE_ARMOR_GLINT.values()) fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_OUTLINE_WRITE.values())     fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_OUTLINE_WRITE_ITEM.values()) fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_OUTLINE_TEST.values())      fixedBufferRegistry.remove(rt);
        }
        BY_GLINT.clear();
        BY_ARMOR_GLINT.clear();
        BY_HORSE_ARMOR_GLINT.clear();
        BY_OUTLINE_WRITE.clear();
        BY_OUTLINE_WRITE_ITEM.clear();
        BY_OUTLINE_TEST.clear();
        GLINT_COLORS.clear();
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

    /** Per-key mutable float[4] holders; RenderType lambdas close over these references and read them each frame. */
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
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(cached)) live.put(cached, new BufferBuilder(cached.bufferSize()));
        return cached;
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
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(cached)) live.put(cached, new BufferBuilder(cached.bufferSize()));
        return cached;
    }

    public static RenderType forGlint(Data glint, int layerIdx, float[] frameColor, boolean isItem, int colorIdx) {
        // isItem=true → flat item model (sword, tool, etc.) → scale 8.0 matches vanilla glint().
        // isItem=false → 3D entity model (trident, etc.) → 1.0 gives visible pattern detail;
        // vanilla entityGlint() uses 0.16 but that tiles too infrequently for custom designs.
        TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        int atlasW = atlas.width;
        int atlasH = atlas.height;
        float scaleU = isItem ? (8.0f * atlasW / 1024.0f) : 1.0f;
        float scaleV = isItem ? (8.0f * atlasH / 512.0f) : 1.0f;
        Layer layer = glint.layers()[layerIdx];
        if (getTexture(layer.design()) == null) return null;
        String key = layer.design() + "|" + Arrays.toString(layer.colors()) + "|" + layer.speed() + "|" + layer.interpolate() + "|" + isItem + "|" + layer.patternScale() + "|" + colorIdx + "|" + layerIdx;
        float[] holder = GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = BY_GLINT.computeIfAbsent(key, k -> {
            ResourceLocation tex = layer.design();
            RenderType rt = RenderType.create(
                    MOD_ID + ":custom_glint|" + k.hashCode(),
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
                                m.scale(scaleU * layer.patternScale(), scaleV * layer.patternScale(), 1.0f);
                                RenderSystem.setTextureMatrix(m);
                            }, RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            return rt;
        });
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(cached)) live.put(cached, new BufferBuilder(cached.bufferSize()));
        return cached;
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

    /** Animates through an int[] color array using game time. Default speed=1, interpolate=true. */
    public static int computeAnimatedGlowColor(int[] colors) {
        if (colors.length == 0) return 0xFFFFFFFF;
        if (colors.length == 1) return colors[0];
        Minecraft mc = Minecraft.getInstance();
        long gameTime = mc.level != null ? mc.level.getGameTime() : 0;
        float totalTicks = 20.0f * colors.length;
        float t = (gameTime % Math.max(1L, (long) totalTicks)) / totalTicks * colors.length;
        int idx = (int) t % colors.length;
        float frac = t - (int) t;
        int c1 = colors[idx], c2 = colors[(idx + 1) % colors.length];
        int a = (int)(((c1 >> 24) & 0xFF) * (1 - frac) + ((c2 >> 24) & 0xFF) * frac);
        int r = (int)(((c1 >> 16) & 0xFF) * (1 - frac) + ((c2 >> 16) & 0xFF) * frac);
        int g = (int)(((c1 >>  8) & 0xFF) * (1 - frac) + ((c2 >>  8) & 0xFF) * frac);
        int b = (int)((c1         & 0xFF) * (1 - frac) + (c2         & 0xFF) * frac);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Outline color for an item: prefers Glow Trim colors, falls back to glint layer 0, else white. */
    public static int outlineColor(ItemStack stack) {
        int[] glow = CustomGlint.getGlowColors(stack);
        if (glow.length > 0) return computeAnimatedGlowColor(glow);
        Data glint = CustomGlint.read(stack);
        if (glint != null) return computeAnimatedColor(glint, 0);
        return 0xFFFFFFFF;
    }

    // ── Outline rendering ─────────────────────────────────────────────────────

    /** Guards re-entrance: set true during outline stencil passes so applyGlint skips the item. */
    public static final ThreadLocal<Boolean> IN_OUTLINE = ThreadLocal.withInitial(() -> false);

    /**
     * Once-per-frame stencil clear gate. Set true at frame start by a RenderTickEvent.START
     * listener in CustomGlintClientInit; the first STENCIL_WRITE_LAYERING.setup of the frame
     * sees it true, calls glClear(STENCIL_BUFFER_BIT), and unsets it so later WRITE setups
     * (different texture → different cached RT) do NOT wipe earlier silhouettes from the
     * stencil buffer. Without this gate, under a shader mod's FullyBuffered drain (RT order is not
     * call order) a later WRITE.setup's glClear can erase an earlier item's stencil stamp,
     * causing its TEST pass (stencil EQUAL 0) to pass everywhere → filled-blob outline.
     * Reproducible in 3.5D FPM whenever a held outlined item shares the screen with a
     * different-texture outlined item (e.g. BEWLR + sprite).
     */
    public static volatile boolean pendingFrameStencilClear = true;

    /**
     * Optional gate consulted at the top of {@link #doModelOutline} and {@link #doItemOutline}.
     * Compat modules (e.g. First Person Mod's 3.5D view, where the player model is hidden/transformed
     * in ways the stencil pass can't track and the dilated outline geometry then renders unmasked)
     * install a supplier here to suppress outline passes for the current frame.
     */
    public static java.util.function.BooleanSupplier outlineSuppressor = () -> false;


    /** Separate from BY_GLINT: outline uses POSITION_COLOR_TEX + OUTLINE_SHADER, not POSITION_TEX + GLINT_SHADER. */
    private static final Map<ResourceLocation, RenderType> BY_OUTLINE_WRITE = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> BY_OUTLINE_WRITE_ITEM = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> BY_OUTLINE_TEST  = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> BY_OUTLINE_TEST_CULLED = new HashMap<>();

    // Disables both color and depth writes — for the stencil silhouette pass.
    private static final RenderStateShard.WriteMaskStateShard NO_WRITE =
            new RenderStateShard.WriteMaskStateShard(false, false);
    // Color writes on, depth writes off — for the shader-pack forward outline pass.
    // Want depth-test (so dilated mesh doesn't poke through walls) but no depth-write
    // (so the dilated mesh doesn't occlude other content drawn later in the same frame).
    private static final RenderStateShard.WriteMaskStateShard COLOR_ONLY_WRITE =
            new RenderStateShard.WriteMaskStateShard(true, false);

    /**
     * Forward-pass outline RenderType used under shader mods. Why this exists:
     * the shader mod's ShaderKey enum has no OUTLINE entry, so RENDERTYPE_OUTLINE_SHADER draws fall
     * through with no destination program under loaded packs. POSITION_COLOR_SHADER maps to
     * ShaderKey.BASIC_COLOR → gbuffers_basic, which is universal across shaderpacks. Format
     * is POSITION_COLOR only (no texture); the dilated mesh is rendered as a flat-colored
     * silhouette behind the actual item. Vertex color comes from the wrapping VertexConsumer
     * (PositionColorOnlyConsumer), so models that normally write entity-format attributes
     * still produce visible geometry — uv/overlay/uv2/normal calls are silently dropped.
     */
    // Toggles glCullFace at draw time so only the dilated mesh's BACK faces render.
    // The back faces sit behind the original mesh except at the silhouette edge, where
    // they extend past it — producing a clean outline ring without needing stencil.
    // This is the standard "shell outline" technique used in toon shading.
    private static final RenderStateShard.LayeringStateShard CULL_FRONT_LAYERING =
            new RenderStateShard.LayeringStateShard("custom_glint_cull_front",
                () -> {
                    GL11.glEnable(GL11.GL_CULL_FACE);
                    GL11.glCullFace(GL11.GL_FRONT);
                },
                () -> {
                    GL11.glCullFace(GL11.GL_BACK); // restore vanilla default
                });

    // Front-face cull + positive polygon offset. The polygon-offset push is what makes the
    // outline behave as a ring in all camera contexts. Without it, the "dilated mesh behind
    // original" depth ordering only happens when the model's local Z axis aligns with eye-
    // space depth (true for 1P held items but NOT for 3P / dropped / GUI-style contexts —
    // the AABB-centroid scale dilates outward in pose space which can be sideways to the
    // camera). glPolygonOffset works in screen-space depth after projection, so it pushes
    // the dilated shell backward regardless of pose orientation. LEQUAL then fails wherever
    // the shell overlaps the original front face → ring effect.
    private static final RenderStateShard.LayeringStateShard CULL_FRONT_PUSH_BACK_LAYERING =
            new RenderStateShard.LayeringStateShard("custom_glint_cull_front_push_back",
                () -> {
                    GL11.glEnable(GL11.GL_CULL_FACE);
                    GL11.glCullFace(GL11.GL_FRONT);
                    RenderSystem.polygonOffset(1.0f, 10.0f);
                    RenderSystem.enablePolygonOffset();
                },
                () -> {
                    GL11.glCullFace(GL11.GL_BACK);
                    RenderSystem.polygonOffset(0.0f, 0.0f);
                    RenderSystem.disablePolygonOffset();
                });

    // Positive polygon offset only (no face cull) — for 2D sprites. The 4 translated copies
    // get pushed slightly behind the original sprite's depth so LEQUAL fails over the
    // sprite's opaque pixels and passes only at the silhouette edges. Replaces the
    // pose-space czOff hack which only worked in 1P (where pose-Z happens to align with
    // eye-space depth) and failed in 3P / dropped contexts.
    private static final RenderStateShard.LayeringStateShard PUSH_BACK_LAYERING =
            new RenderStateShard.LayeringStateShard("custom_glint_push_back",
                () -> {
                    RenderSystem.polygonOffset(1.0f, 10.0f);
                    RenderSystem.enablePolygonOffset();
                },
                () -> {
                    RenderSystem.polygonOffset(0.0f, 0.0f);
                    RenderSystem.disablePolygonOffset();
                });

    /** Armor variant of forShaderOutline — adds front-face culling so the dilated mesh
     *  produces a ring rather than a solid silhouette when scaled around a translated
     *  pivot (humanoid pose origin is at feet, not at each armor piece's center). */
    private static RenderType SHADER_OUTLINE_ARMOR_TYPE;
    public static RenderType forShaderArmorOutline() {
        if (SHADER_OUTLINE_ARMOR_TYPE == null) {
            SHADER_OUTLINE_ARMOR_TYPE = RenderType.create(
                    MOD_ID + ":shader_outline_armor",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(POSITION_COLOR_SHADER)
                            .setCullState(CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            // LIGHTNING_TRANSPARENCY = additive (SrcAlpha + One). Color adds to
                            // background, making the outline read as a translucent bright glow
                            // rather than a solid colored ring. Vanilla glowing uses an
                            // edge-detect + additive composite to similar effect; we approximate
                            // it here with the dilated shell + additive blend.
                            .setTransparencyState(LIGHTNING_TRANSPARENCY)
                            .setWriteMaskState(COLOR_ONLY_WRITE)
                            .setLayeringState(CULL_FRONT_PUSH_BACK_LAYERING)
                            .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(SHADER_OUTLINE_ARMOR_TYPE, new BufferBuilder(SHADER_OUTLINE_ARMOR_TYPE.bufferSize()));
        }
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(SHADER_OUTLINE_ARMOR_TYPE))
            live.put(SHADER_OUTLINE_ARMOR_TYPE, new BufferBuilder(SHADER_OUTLINE_ARMOR_TYPE.bufferSize()));
        tagAsLateRenderForShaders(SHADER_OUTLINE_ARMOR_TYPE);
        return SHADER_OUTLINE_ARMOR_TYPE;
    }

    private static final Map<ResourceLocation, RenderType> BY_SHELL_OUTLINE_TEXTURED = new HashMap<>();
    /**
     * Textured shell-outline render type for the elytra cape. Same shell technique as
     * {@link #forShaderArmorOutline} (CULL_FRONT + polygon-offset push-back + additive blend),
     * but uses POSITION_COLOR_TEX + RENDERTYPE_OUTLINE_SHADER so the elytra texture's alpha
     * channel discards transparent texels — without that, the wing cubes draw their full
     * texture-space rectangle instead of the wing silhouette.
     */
    public static RenderType forShellOutlineTextured(ResourceLocation texture) {
        RenderType cached = BY_SHELL_OUTLINE_TEXTURED.computeIfAbsent(texture, tex -> {
            RenderType rt = RenderType.create(
                    MOD_ID + ":shell_outline_textured",
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_OUTLINE_SHADER)
                            .setTextureState(new TextureStateShard(tex, false, false))
                            .setCullState(CULL) // back-face cull → only camera-facing face draws
                            .setDepthTestState(EQUAL_DEPTH_TEST) // match cape's own depth (which armorCutoutNoCull wrote with -1,-10 offset)
                            .setTransparencyState(LIGHTNING_TRANSPARENCY)
                            .setWriteMaskState(COLOR_ONLY_WRITE)
                            .setOutputState(FORCE_MAIN_TARGET)
                            .setLayeringState(VIEW_OFFSET_Z_LAYERING) // matches armorCutoutNoCull's polygon offset so EQUAL passes on cape pixels
                            .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            return rt;
        });
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(cached)) live.put(cached, new BufferBuilder(cached.bufferSize()));
        tagAsLateRenderForShaders(cached);
        return cached;
    }

    private static RenderType SHADER_OUTLINE_TYPE;
    public static RenderType forShaderOutline() {
        if (SHADER_OUTLINE_TYPE == null) {
            SHADER_OUTLINE_TYPE = RenderType.create(
                    MOD_ID + ":shader_outline",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(POSITION_COLOR_SHADER)
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            // Additive blend — see forShaderArmorOutline for rationale.
                            .setTransparencyState(LIGHTNING_TRANSPARENCY)
                            .setWriteMaskState(COLOR_ONLY_WRITE)
                            .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(SHADER_OUTLINE_TYPE, new BufferBuilder(SHADER_OUTLINE_TYPE.bufferSize()));
        }
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(SHADER_OUTLINE_TYPE))
            live.put(SHADER_OUTLINE_TYPE, new BufferBuilder(SHADER_OUTLINE_TYPE.bufferSize()));
        tagAsLateRenderForShaders(SHADER_OUTLINE_TYPE);
        return SHADER_OUTLINE_TYPE;
    }

    /** Sprite (2D flat item) outline render type for the shader-pack path. Vanilla's
     *  RenderType.entityCutoutNoCull does standard alpha blending → outline draws as a solid
     *  larger copy of the sword, hiding the original at the center. This variant keeps the
     *  same shader (RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER, mapped to ENTITIES_CUTOUT
     *  so it actually renders under shaderpacks) and the same NEW_ENTITY vertex format
     *  (matches what ItemRenderer.renderQuadList writes), but swaps the transparency to
     *  additive (LIGHTNING_TRANSPARENCY = SrcAlpha + One) and disables depth-write. Effect:
     *  the 4 translated sprite copies add their color onto the background, producing a
     *  see-through bright glow rather than a colored slab. */
    private static final Map<ResourceLocation, RenderType> SHADER_SPRITE_OUTLINE_TYPES = new ConcurrentHashMap<>();
    public static RenderType forShaderSpriteOutline(ResourceLocation tex) {
        return SHADER_SPRITE_OUTLINE_TYPES.computeIfAbsent(tex, t -> {
            RenderType rt = RenderType.create(
                    MOD_ID + ":shader_sprite_outline_" + t.getNamespace() + "_" + t.getPath().replace('/', '_'),
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER)
                            .setTextureState(new TextureStateShard(t, false, false))
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setTransparencyState(LIGHTNING_TRANSPARENCY)
                            .setWriteMaskState(COLOR_ONLY_WRITE)
                            .setLayeringState(PUSH_BACK_LAYERING)
                            .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
            if (live != null && !live.containsKey(rt))
                live.put(rt, new BufferBuilder(rt.bufferSize()));
            tagAsLateRenderForShaders(rt);
            return rt;
        });
    }

    // Flat-color sprite outline RT for the shader-pack path. POSITION_COLOR (no texture sample)
    // so the shader's gbuffers_basic-style mapping emits pure outlineColor pixels instead of
    // outlineColor × spriteTexel. Avoids the "second copy of the textured sprite" artifact
    // that the textured variant (forShaderSpriteOutline) produces under shaders, where the
    // additive blend state is ignored by the deferred composite. Translated copies + LEQUAL +
    // PUSH_BACK_LAYERING reject inside the original sprite's depth → ring at silhouette edges.
    private static RenderType SHADER_SPRITE_OUTLINE_FLAT_TYPE;
    public static RenderType forShaderSpriteOutlineFlat() {
        if (SHADER_SPRITE_OUTLINE_FLAT_TYPE == null) {
            SHADER_SPRITE_OUTLINE_FLAT_TYPE = RenderType.create(
                    MOD_ID + ":shader_sprite_outline_flat",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(POSITION_COLOR_SHADER)
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setTransparencyState(LIGHTNING_TRANSPARENCY)
                            .setWriteMaskState(COLOR_ONLY_WRITE)
                            .setLayeringState(PUSH_BACK_LAYERING)
                            .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(SHADER_SPRITE_OUTLINE_FLAT_TYPE, new BufferBuilder(SHADER_SPRITE_OUTLINE_FLAT_TYPE.bufferSize()));
        }
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(SHADER_SPRITE_OUTLINE_FLAT_TYPE))
            live.put(SHADER_SPRITE_OUTLINE_FLAT_TYPE, new BufferBuilder(SHADER_SPRITE_OUTLINE_FLAT_TYPE.bufferSize()));
        tagAsLateRenderForShaders(SHADER_SPRITE_OUTLINE_FLAT_TYPE);
        return SHADER_SPRITE_OUTLINE_FLAT_TYPE;
    }

    /**
     * VertexConsumer wrapper for the shader-pack forward outline path. Underlying buffer is
     * POSITION_COLOR only; entity models call vertex().color().uv().overlayCoords().uv2().normal().endVertex().
     * We forward position + color + endVertex, override the color with a fixed RGBA (outline color),
     * and silently swallow uv/overlay/uv2/normal so the buffer's format constraints aren't violated.
     */
    /** VertexConsumer that swallows every call. Used to drive geometry through AABBTrackingConsumer
     *  without actually rendering anything (shader-pack outline first pass: just capture bounds). */
    private static final class NullConsumer implements VertexConsumer {
        @Override public VertexConsumer vertex(double x, double y, double z) { return this; }
        @Override public VertexConsumer vertex(Matrix4f m, float x, float y, float z) { return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer color(float r, float g, float b, float a) { return this; }
        @Override public VertexConsumer uv(float u, float v) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public void endVertex() {}
        @Override public void defaultColor(int r, int g, int b, int a) {}
        @Override public void unsetDefaultColor() {}
    }

    private static final class PositionColorOnlyConsumer implements VertexConsumer {
        private final VertexConsumer wrapped;
        private final int r, g, b, a;
        PositionColorOnlyConsumer(VertexConsumer wrapped, int r, int g, int b, int a) {
            this.wrapped = wrapped; this.r = r; this.g = g; this.b = b; this.a = a;
        }
        @Override public VertexConsumer vertex(double x, double y, double z) { wrapped.vertex(x, y, z); return this; }
        @Override public VertexConsumer vertex(Matrix4f m, float x, float y, float z) { wrapped.vertex(m, x, y, z); return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { wrapped.color(this.r, this.g, this.b, this.a); return this; }
        @Override public VertexConsumer color(float r, float g, float b, float a) { wrapped.color(this.r, this.g, this.b, this.a); return this; }
        @Override public VertexConsumer uv(float u, float v) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public void endVertex() { wrapped.endVertex(); }
        @Override public void defaultColor(int r, int g, int b, int a) {}
        @Override public void unsetDefaultColor() {}
    }

    // Force-binds the vanilla main render target (which has a stencil attachment via the shader
    // mod's stencil-enabling mixin) and restores the previously-bound FBO on clear.
    // Why: vanilla's MAIN_TARGET OutputStateShard is a no-op runnable that assumes the main FBO
    // is already bound. Under the shader mod's HAND_SOLID phase the gbuffer FBO is bound instead —
    // it has no stencil attachment, so stencil ops are silently dropped and our outline draws
    // the dilated mesh inside the silhouette (visible as a filled plane). Capturing the previous
    // FBO and restoring it on clear keeps the shader pipeline state intact.
    private static final int[] SAVED_FBO = new int[1];
    private static final RenderStateShard.OutputStateShard FORCE_MAIN_TARGET =
            new RenderStateShard.OutputStateShard("custom_glint_force_main_target",
                () -> {
                    SAVED_FBO[0] = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                    Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
                },
                () -> {
                    GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, SAVED_FBO[0]);
                });

    // Stencil setup baked into a LayeringStateShard so it runs at DRAW time, not submission time.
    // Why: under shader mods, RenderBuffers.bufferSource() is replaced with a FullyBufferedMultiBufferSource
    // that captures geometry into BufferSegments and replays them later via BufferSegmentRenderer.draw(), which
    // calls RenderType.setupRenderState() → shader → clearRenderState() at replay time. Raw GL11 calls made
    // between renderToBuffer() invocations execute at submission time and are gone before the actual draw —
    // the stencil pass would replay with full color writes (showing as a filled plane inside the outline) and
    // the test pass would replay without the EQUAL,0 stencil test. Putting the state into shards ties it to
    // the draw, so it's correct under both vanilla and shader-mod pipelines.
    // IMPORTANT shard contract:
    //   - WRITE.setup is the ONLY place that clears the stencil buffer (starts a new silhouette).
    //   - WRITE.clear disables stencil (cleanup if no test pass follows, e.g. minMax bounds empty).
    //   - TEST.setup re-enables stencil and sets EQUAL,0 (must re-enable: in vanilla immediate-mode
    //     flushing, a previous TEST.clear may have disabled stencil between submissions in the
    //     4-translation flat-sprite outline path — the stencil VALUES persist across disable/enable
    //     cycles, so successive test draws all see the silhouette stamped by WRITE).
    //   - TEST.clear disables stencil (cleanup) but does NOT clear the stencil buffer — that would
    //     destroy values needed by subsequent test draws in the multi-translation path.
    private static final RenderStateShard.LayeringStateShard STENCIL_WRITE_LAYERING =
            new RenderStateShard.LayeringStateShard("custom_glint_stencil_write",
                () -> {
                    Minecraft.getInstance().getMainRenderTarget().enableStencil();
                    GL11.glEnable(GL11.GL_STENCIL_TEST);
                    GL11.glStencilMask(0xFF);
                    // Only the FIRST outline of the frame clears the stencil buffer. See
                    // pendingFrameStencilClear's javadoc for why per-RT glClear breaks
                    // multi-outline scenes under the shader mod's FullyBuffered drain.
                    if (pendingFrameStencilClear) {
                        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
                        pendingFrameStencilClear = false;
                    }
                    GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_REPLACE, GL11.GL_REPLACE);
                    // Match armorCutoutNoCull's VIEW_OFFSET_Z_LAYERING (polygonOffset(-1,-10))
                    // so stencil-write depth == armor depth → LEQUAL depth-test reliably PASSES
                    // (via dppass=REPLACE) instead of relying on dpfail=REPLACE. Under shader mods
                    // the dpfail path is empirically not honored consistently — outline appears
                    // FILLED while standing, but visible behind the player while crouching (where
                    // polygon-offset slope differs between poses, making the dpfail vs dppass
                    // distinction flip). Matching the armor's polygon offset removes that variance.
                    GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
                    GL11.glPolygonOffset(-1.0f, -10.0f);
                },
                () -> {
                    GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
                    GL11.glDisable(GL11.GL_STENCIL_TEST);
                    GL11.glPolygonOffset(0.0f, 0.0f);
                    GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
                });

    /**
     * Item-stencil-write variant: identical to STENCIL_WRITE_LAYERING but WITHOUT polygon offset.
     * Armor's base draw (armorCutoutNoCull) uses VIEW_OFFSET_Z_LAYERING → polygonOffset(-1,-10),
     * so the armor stencil write must match that offset to land its silhouette on the same depth
     * as the armor mesh. Items' base draws (entityCutoutNoCull / sprite RTs) use NO polygon offset
     * — applying (-1,-10) to item silhouette quads can push them in front of the near plane in
     * 3.5D FPM (where held items sit very close to the camera and unit resolution is finest),
     * causing some camera angles to clip the silhouette → empty stencil inside the silhouette →
     * dilated TEST pass fills the entire interior. Separate shard so item write depth matches the
     * item's own draw depth at all camera angles and distances.
     */
    private static final RenderStateShard.LayeringStateShard STENCIL_WRITE_LAYERING_ITEM =
            new RenderStateShard.LayeringStateShard("custom_glint_stencil_write_item",
                () -> {
                    Minecraft.getInstance().getMainRenderTarget().enableStencil();
                    GL11.glEnable(GL11.GL_STENCIL_TEST);
                    GL11.glStencilMask(0xFF);
                    if (pendingFrameStencilClear) {
                        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
                        pendingFrameStencilClear = false;
                    }
                    GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_REPLACE, GL11.GL_REPLACE);
                },
                () -> {
                    GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
                    GL11.glDisable(GL11.GL_STENCIL_TEST);
                });

    private static final RenderStateShard.LayeringStateShard STENCIL_TEST_LAYERING =
            new RenderStateShard.LayeringStateShard("custom_glint_stencil_test",
                () -> {
                    GL11.glEnable(GL11.GL_STENCIL_TEST);
                    GL11.glStencilMask(0xFF);
                    GL11.glStencilFunc(GL11.GL_EQUAL, 0, 0xFF);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
                },
                () -> {
                    GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
                    GL11.glDisable(GL11.GL_STENCIL_TEST);
                });

    /** Stencil-test + front-face cull combined: for the elytra cape, where back-face cull keeps
     *  the wrong face (inner side facing player back) and produces a static inner-face outline. */
    private static final RenderStateShard.LayeringStateShard STENCIL_TEST_CULL_FRONT_LAYERING =
            new RenderStateShard.LayeringStateShard("custom_glint_stencil_test_cull_front",
                () -> {
                    GL11.glEnable(GL11.GL_STENCIL_TEST);
                    GL11.glStencilMask(0xFF);
                    GL11.glStencilFunc(GL11.GL_EQUAL, 0, 0xFF);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
                    GL11.glEnable(GL11.GL_CULL_FACE);
                    GL11.glCullFace(GL11.GL_FRONT);
                },
                () -> {
                    GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
                    GL11.glDisable(GL11.GL_STENCIL_TEST);
                    GL11.glCullFace(GL11.GL_BACK); // restore vanilla default
                });

    /** Stencil-write pass: same outline shader/texture, but color+depth writes off and stencil set up to stamp 1s. */
    public static RenderType forOutlineStencilWrite(ResourceLocation texture) {
        RenderType cached = BY_OUTLINE_WRITE.computeIfAbsent(texture, tex -> {
            RenderType rt = RenderType.create(
                    MOD_ID + ":glint_outline_write",
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_OUTLINE_SHADER)
                            .setTextureState(new TextureStateShard(tex, false, false))
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setOutputState(FORCE_MAIN_TARGET)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setWriteMaskState(NO_WRITE)
                            .setLayeringState(STENCIL_WRITE_LAYERING)
                            .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            return rt;
        });
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(cached)) live.put(cached, new BufferBuilder(cached.bufferSize()));
        return cached;
    }

    /**
     * Item-variant stencil-write pass: like {@link #forOutlineStencilWrite} but without the
     * armor-matching polygon offset baked in. Items have no polygon offset on their base draw,
     * and applying one to the stencil silhouette pushes it in front of the near plane in
     * 3.5D FPM at some camera angles → silhouette clipped → stencil empty → TEST fills the
     * entire interior. See STENCIL_WRITE_LAYERING_ITEM's javadoc.
     */
    public static RenderType forOutlineStencilWriteItem(ResourceLocation texture) {
        RenderType cached = BY_OUTLINE_WRITE_ITEM.computeIfAbsent(texture, tex -> {
            RenderType rt = RenderType.create(
                    MOD_ID + ":glint_outline_write_item",
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_OUTLINE_SHADER)
                            .setTextureState(new TextureStateShard(tex, false, false))
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setOutputState(FORCE_MAIN_TARGET)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setWriteMaskState(NO_WRITE)
                            .setLayeringState(STENCIL_WRITE_LAYERING_ITEM)
                            .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            return rt;
        });
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(cached)) live.put(cached, new BufferBuilder(cached.bufferSize()));
        return cached;
    }

    /** Stencil-test pass: same outline shader/texture, color+depth writes on, stencil test EQUAL 0 so only the ring draws. */
    public static RenderType forOutlineStencilTest(ResourceLocation texture) {
        RenderType cached = BY_OUTLINE_TEST.computeIfAbsent(texture, tex -> {
            RenderType rt = RenderType.create(
                    MOD_ID + ":glint_outline_test",
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_OUTLINE_SHADER)
                            .setTextureState(new TextureStateShard(tex, false, false))
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setOutputState(FORCE_MAIN_TARGET)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setLayeringState(STENCIL_TEST_LAYERING)
                            .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            return rt;
        });
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(cached)) live.put(cached, new BufferBuilder(cached.bufferSize()));
        // Force test-bucket drain AFTER all writes under shader-mod FullyBuffered. Write RTs
        // stay in default GENERAL_TRANSPARENT; tagging test as LINES (last bucket) guarantees
        // every WRITE.setup/draw/clear in the batched group completes before any TEST runs —
        // otherwise intra-bucket sort order is undefined and item 2's stencil stamp can land
        // AFTER its EQUAL-0 test, leaving stencil=0 inside the silhouette → filled-blob.
        // Reflective; no-op when no shader mod is present. Single shared test RT covers both
        // doItemOutline and doModelOutline → fixes 2-item, armor+item, and held+ground-drop
        // FPM 3.5D cases uniformly. ⚠ Does NOT affect normal 1P / 3P / ground (those don't
        // batch through FullyBuffered with multi-item entity groups).
        tagAsLateRenderForShaders(cached);
        return cached;
    }

    /**
     * Stencil-test variant with front-face culling for the elytra cape. Each wing is a 3D box
     * (CubeDeformation-inflated 12×22×4); the default NO_CULL test pass produces a static-looking
     * outline ring on the cape's inner face (the side facing the player's back). Front-face cull
     * drops the camera-facing dilated face and keeps only the back-facing dilated face — the
     * dilated shell's back face extends past the original silhouette only at its outer edge,
     * yielding one clean ring around the cape silhouette without the inner-face bleed.
     */
    public static RenderType forOutlineStencilTestCulled(ResourceLocation texture) {
        RenderType cached = BY_OUTLINE_TEST_CULLED.computeIfAbsent(texture, tex -> {
            RenderType rt = RenderType.create(
                    MOD_ID + ":glint_outline_test_culled",
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_OUTLINE_SHADER)
                            .setTextureState(new TextureStateShard(tex, false, false))
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setOutputState(FORCE_MAIN_TARGET)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setLayeringState(STENCIL_TEST_CULL_FRONT_LAYERING)
                            .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            return rt;
        });
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(cached)) live.put(cached, new BufferBuilder(cached.bufferSize()));
        tagAsLateRenderForShaders(cached);
        return cached;
    }

    /** Returns the current animated color (ARGB) from layer 0 of the glint, for use as outline color. */
    public static int glintOutlineColor(Data glint) {
        return computeAnimatedColor(glint, 0);
    }

    /**
     * Stack-aware outline color: prefers Glow Trim colors (glowColors NBT), falls back to glint
     * layer 0, else returns white. Use this for any outline rendered for an ItemStack so glow-only
     * items (with no glint Data) get the correct animated color.
     */
    public static int glintOutlineColor(ItemStack stack) {
        return outlineColor(stack);
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
     * the ring of pixels outside the original silhouette.2
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
        doModelOutline(poseStack, buffer, packedLight, model, texture, glintOutlineColor(glint), slot);
    }

    /** Stack-aware overload: pulls outline color from glowColors or glint layer 0 on the stack. */
    public static void doModelOutline(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, EntityModel<?> model, ResourceLocation texture, ItemStack stack, EquipmentSlot slot) {
        doModelOutline(poseStack, buffer, packedLight, model, texture, glintOutlineColor(stack), slot);
    }

    public static void doModelOutline(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, EntityModel<?> model, ResourceLocation texture, int color, EquipmentSlot slot) {
        // Don't run outline geometry during the shader mod's shadow pass — wrong buffer format → endVertex crash.
        if (isInShadowPass()) return;
        if (outlineSuppressor.getAsBoolean()) return;
        float oR = ((color >> 16) & 0xFF) / 255.0f;
        float oG = ((color >>  8) & 0xFF) / 255.0f;
        float oB = ( color        & 0xFF) / 255.0f;

        // Under an active shader pack: stencil path is dead (no OUTLINE entry in ShaderKey enum),
        // and entity-outline-target routing depends on shaderpack final composite sampling that
        // target — most don't. Forward-pass approach: render dilated model with POSITION_COLOR
        // shader (universal shader-mod mapping). Result is a flat-colored silhouette drawn 1.04× larger,
        // visible as a ring of pure color around the actual item. Shaderpack lighting still tints
        // the color since this goes through gbuffers_basic.
        //
        // ⚠ Gate is `isShaderPackActive()` only — NOT `|| isShaderModInstalled()`. Forward-pass uses
        // POSITION_COLOR with no texture, so the dilated silhouette traces the full HumanoidModel
        // bone geometry (head/body/arms/legs) instead of the armored coverage. Under
        // shader-mod-installed-no-pack the stencil path below works correctly because both passes
        // are driven through fresh local BufferBuilders + RenderType.end(...) — bypassing
        // FullyBufferedMultiBufferSource (whose endBatch(RenderType) is a no-op).
        // Items still gate on isShaderPackActive() only for a different reason —
        // see doItemOutline / doBewlrOutline.
        if (isShaderPackActive()) {
            int rByte = (color >> 16) & 0xFF;
            int gByte = (color >>  8) & 0xFF;
            int bByte =  color        & 0xFF;
            // Force-flush so the armor piece's depth is committed before our outline tests
            // against it — see doItemOutline for full rationale.
            if (buffer instanceof MultiBufferSource.BufferSource preBs) preBs.endBatch();
            // Armor uses front-face-culled render type so the dilated mesh forms a ring
            // rather than a filled silhouette. Scale around THIS piece's own AABB centroid
            // (captured on a first AABB-only pass) instead of a shared player-pose pivot —
            // otherwise a helmet's inflated mesh extends downward into the chest space and
            // the dilated back faces of one piece read through the front faces of neighbors,
            // producing the "plane bleeding through" artifact the user reported.
            RenderType outlineRT = asShaderOutline("custom_glint:shader_outline_armor_wrap", forShaderArmorOutline());
            float[] minMax = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                               Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
            poseStack.pushPose();
            model.renderToBuffer(poseStack, new AABBTrackingConsumer(new NullConsumer(), minMax),
                    packedLight, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);
            poseStack.popPose();
            if (!(minMax[3] > minMax[0])) return;
            float cx = (minMax[0] + minMax[3]) * 0.5f;
            float cy = (minMax[1] + minMax[4]) * 0.5f;
            float cz = (minMax[2] + minMax[5]) * 0.5f;
            float outlineScale = slot == EquipmentSlot.FEET ? 1.03f : 1.04f;
            VertexConsumer outlineBuf = new PositionColorOnlyConsumer(
                    buffer.getBuffer(outlineRT), rByte, gByte, bByte, 255);
            poseStack.pushPose();
            poseStack.last().pose().mulLocal(new Matrix4f()
                    .translate(cx, cy, cz)
                    .scale(outlineScale, outlineScale, outlineScale)
                    .translate(-cx, -cy, -cz));
            model.renderToBuffer(poseStack, outlineBuf, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, oR, oG, oB, 1.0f);
            if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch(outlineRT);
            poseStack.popPose();
            return;
        }

        // Elytra cape branch — bypass stencil entirely and use the shell-outline forward path
        // (same approach as the shaderpack branch above). Each wing is a 3D box but the cape sits
        // suspended in air with the player body right behind it, and the stencil approach always
        // produces a visible ring on the body-facing fringe regardless of cull direction. The
        // forward shell technique uses CULL_FRONT + polygon-offset push-back: only the dilated
        // shell's back-facing faces draw, pushed behind in screen depth so LEQUAL fails wherever
        // the original cape exists — the ring forms only where the dilated shell extends past
        // the original silhouette and into pure-air pixels.
        RenderType writeType = forOutlineStencilWrite(texture);
        RenderType testType  = forOutlineStencilTest(texture);
        Minecraft.getInstance().getMainRenderTarget().enableStencil();

        // Pre-flush the outer source before our stencil passes. Every other working stencil
        // outline path in this mod does this (doItemOutline → preBs.endBatch; EK's
        // applyDecorationGlint → bs.endLastBatch). Without it, the armor's own vertices —
        // just queued by HumanoidArmorLayer into a FullyBuffered SegmentedBufferBuilder —
        // are mixed into the same deferred flush as our stencil-write and stencil-test, and
        // the eventual batched draw fills the silhouette instead of forming a ring under
        // shader-mod-no-pack in 3P. Items work without this issue because doItemOutline already
        // pre-flushes; armor was the odd one out.
        if (buffer instanceof MultiBufferSource.BufferSource preBs) preBs.endBatch();

        // Route both stencil passes through the outer buffer source (NOT local BufferBuilders).
        // Why: under the shader mod's FullyBufferedMultiBufferSource, `endBatch(RenderType)` is a
        // no-op, so single-RT flushes don't draw immediately — but consecutive `getBuffer(rt)`
        // calls inside an entity render get an insertion-order EDGE added to
        // GraphTranslucencyRenderOrderManager's digraph (only when `inGroup`, set by the shader
        // mod's per-entity render hook). That edge forces WRITE→TEST order in the eventual no-arg
        // endBatch flush.
        //
        // Local-BufferBuilder + RenderType.end() (previous approach) bypasses the buffer source
        // entirely. Empirically that BREAKS armor outline in 3P under shader mods — even though the
        // two passes draw synchronously in order, injecting drawWithShader calls while the
        // FullyBuffered SegmentedBufferBuilders are mid-recording vertices for this same player
        // entity corrupts state (filled silhouette / Z artifacts / per-frame flicker). Hand
        // items rendered via HeldItemLayer (also inside an entity group) work fine with the
        // buffer.getBuffer path, so mirror that pattern for armor.
        model.renderToBuffer(poseStack, buffer.getBuffer(writeType), packedLight, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);
        if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch(writeType);

        // Pass 2 (dilated outline). Stencil EQUAL 0 test baked into testType's shards.
        float outlineScale = slot == EquipmentSlot.FEET ? 1.03f : 1.04f;
        poseStack.pushPose();
        if (model instanceof HorseModel) {
            // Horse model bones are positioned far from the pose origin used for humanoid armor;
            // the +0.9/-0.95 pivot for humanoid distorts the horse outline. Scale around the pose
            // origin (entity feet) — outline ring stays concentric with the horse silhouette.
            poseStack.scale(outlineScale, outlineScale, outlineScale);
        } else if (model instanceof ElytraModel) {
            // Each elytra wing is a 3D box. Uniform 1.04× scale dilates in Z (cube thickness) too,
            // which projects the front and back face silhouettes to slightly different screen
            // positions — visible as a ghost outline ring on the body-facing side of the elytra.
            // Fix: dilate ONLY in X/Y (silhouette plane), keep Z untouched so both faces project
            // to the same screen silhouette. Scale around the elytra's own AABB centroid (captured
            // via a NullConsumer pre-pass) so the dilated ring stays concentric with the wings in
            // both folded and spread poses.
            float[] minMax = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                               Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
            poseStack.pushPose();
            model.renderToBuffer(poseStack, new AABBTrackingConsumer(new NullConsumer(), minMax),
                    packedLight, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);
            poseStack.popPose();
            if (minMax[3] > minMax[0]) {
                float cx = (minMax[0] + minMax[3]) * 0.5f;
                float cy = (minMax[1] + minMax[4]) * 0.5f;
                poseStack.last().pose().mulLocal(new Matrix4f()
                        .translate(cx, cy, 0)
                        .scale(outlineScale, outlineScale, 1.0f)
                        .translate(-cx, -cy, 0));
            } else {
                poseStack.scale(outlineScale, outlineScale, 1.0f);
            }
        } else {
            poseStack.translate(0.0f, -0.95f, 0.0f); // -0.9 - 0.05 downward alignment correction
            poseStack.scale(outlineScale, outlineScale, outlineScale);
            poseStack.translate(0.0f, 0.9f, 0.0f);
        }
        model.renderToBuffer(poseStack, buffer.getBuffer(testType), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, oR, oG, oB, 1.0f);
        if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch(testType);
        poseStack.popPose();
    }

    /**
     * Compat hook: BEWLR classes registered here opt out of the generic doItemOutline path
     * and draw their own outline (typically via doBewlrOutline with the item's actual texture
     * so each variant's silhouette matches its opaque texels rather than the combined model AABB).
     */
    public static final Set<Class<?>> CUSTOM_OUTLINE_BEWLRS = ConcurrentHashMap.newKeySet();

    /**
     * Per-item-class outline offset overrides for BEWLR items that take the AABB scale-dilation
     * outline path. Key = item class FQN (string, so compat code can register modded items
     * without a compileOnly dep). Value = {cx1P, cy1P, cz1P, cx3P, cy3P, cz3P} — offsets added
     * to the AABB centroid before the 1.06× scale. Used by doItemOutline when the rendered
     * model's visual center sits off the AABB centroid (e.g. IaF tide trident).
     */
    public static final Map<String, float[]> BEWLR_OUTLINE_OFFSETS = new ConcurrentHashMap<>();

    /**
     * Item class FQNs whose BEWLR swaps to a flat 2D inventory sprite in GROUND/FIXED contexts
     * (vanilla trident does this internally; some modded "trident-style" items do too). Items
     * registered here take the flat-sprite outline path in GROUND/FIXED instead of the BEWLR
     * AABB scale-dilation path. Populated by compat code.
     */
    public static final Set<String> FLAT_ON_GROUND_ITEMS = ConcurrentHashMap.newKeySet();

    /**
     * Per-item override texture for the BEWLR AABB scale-dilation outline pass. Key = item class
     * FQN, value = texture ResourceLocation. Default is white.png (fills every model face
     * opaquely), which produces squared blocks of color on model cubes whose UVs cover
     * transparent texture areas. Registering a texture here makes the outline shader
     * alpha-discard against those texels, so the outline traces only opaque-pixel silhouettes.
     * Keyed by item (not BEWLR class) because the vanilla default BEWLR is shared across many
     * items (trident, shield, banner, skull, …) and needs per-item textures.
     */
    public static final Map<String, ResourceLocation> BEWLR_OUTLINE_TEXTURES = new ConcurrentHashMap<>();

    /**
     * Stencil-based colored outline for BEWLR (block-entity-without-level-renderer) items whose
     * geometry is a single combined model with per-variant texture (e.g. Ice & Fire troll weapons,
     * death worm gauntlet). Unlike doModelOutline, scales around the pose origin without any
     * humanoid pivot translate, and uses 1.06× to match the BEWLR scale used in doItemOutline.
     * Caller is responsible for pose translates that match the BEWLR's internal pose.
     */
    public static void doBewlrOutline(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, Model model, ResourceLocation texture, Data glint) {
        doBewlrOutline(poseStack, buffer, packedLight, model, texture, glintOutlineColor(glint));
    }

    /** Stack-aware overload: outline color resolved from glowColors / glint layer 0. */
    public static void doBewlrOutline(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, Model model, ResourceLocation texture, ItemStack stack) {
        doBewlrOutline(poseStack, buffer, packedLight, model, texture, glintOutlineColor(stack));
    }

    public static void doBewlrOutline(PoseStack poseStack, MultiBufferSource buffer,
            int packedLight, Model model, ResourceLocation texture, int color) {
        if (isInShadowPass()) return;
        float oR = ((color >> 16) & 0xFF) / 255.0f;
        float oG = ((color >>  8) & 0xFF) / 255.0f;
        float oB = ( color        & 0xFF) / 255.0f;

        // Shader-pack forward-pass path: see doModelOutline for rationale.
        //
        // ⚠ DO NOT widen this gate to `|| isShaderModInstalled()` to match doModelOutline. Items go
        // INVISIBLE under shader-mod-installed-no-pack on the forward-pass path: BEWLR/sprite RTs
        // use entity-shader-based render types, which the shader mod's FullyBufferedMultiBufferSource
        // batches and flushes at a phase where the depth target is wrong for the outline geometry.
        // Stencil path under shader-mod-no-pack is broken too (solid fill), but visible.
        // Visible-but-wrong > invisible.
        if (isShaderPackActive()) {
            int rByte = (color >> 16) & 0xFF;
            int gByte = (color >>  8) & 0xFF;
            int bByte =  color        & 0xFF;
            if (buffer instanceof MultiBufferSource.BufferSource preBs) preBs.endBatch();
            RenderType outlineRT = asShaderOutline("custom_glint:shader_outline_bewlr_wrap", forShaderOutline());
            VertexConsumer outlineBuf = new PositionColorOnlyConsumer(
                    buffer.getBuffer(outlineRT), rByte, gByte, bByte, 255);
            IN_OUTLINE.set(true);
            try {
                poseStack.pushPose();
                poseStack.scale(1.06f, 1.06f, 1.06f);
                model.renderToBuffer(poseStack, outlineBuf, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, oR, oG, oB, 1.0f);
                if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch(outlineRT);
                poseStack.popPose();
            } finally {
                IN_OUTLINE.set(false);
            }
            return;
        }

        // Item-variant write: no polygon offset. BEWLR items' base draws don't use polygon offset,
        // and the armor-matching offset can clip the silhouette in front of the near plane in 3.5D FPM.
        RenderType writeType = forOutlineStencilWriteItem(texture);
        RenderType testType  = forOutlineStencilTest(texture);
        if (buffer instanceof MultiBufferSource.BufferSource preBs) preBs.endBatch();
        Minecraft.getInstance().getMainRenderTarget().enableStencil();
        IN_OUTLINE.set(true);
        try {
            // Pass 1 (stencil silhouette) — masks + stencil setup baked into writeType shards (shader-mod-safe).
            model.renderToBuffer(poseStack, buffer.getBuffer(writeType), packedLight, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);
            if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch(writeType);

            // Pass 2 (dilated outline) — EQUAL,0 stencil test baked into testType shards.
            RenderSystem.setShaderColor(oR, oG, oB, 1.0f);
            poseStack.pushPose();
            poseStack.scale(1.06f, 1.06f, 1.06f);
            model.renderToBuffer(poseStack, buffer.getBuffer(testType), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, oR, oG, oB, 1.0f);
            if (buffer instanceof MultiBufferSource.BufferSource bs2) bs2.endBatch(testType);
            poseStack.popPose();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            // Trailing full drain — see doItemOutline for the rationale (atomic per-item stencil
            // pipeline under shader-mod FullyBuffered, otherwise the last item of the frame stays
            // queued and its stencil pass sees disrupted state at end-of-frame drain).
            if (buffer instanceof MultiBufferSource.BufferSource postBs) postBs.endBatch();
        } finally {
            IN_OUTLINE.set(false);
        }
    }

    /**
     * Stencil-based colored outline for item/weapon/tool renders.
     * Skips GUI context. Uses an isolated BufferSource for the stencil pass to avoid
     * flushing other items in the main batch while colorMask is disabled. Uses IN_OUTLINE
     * to prevent recursive glint application during the stencil/outline passes.
     *
     * ── OPEN BUG: FPM 3.5D + shader mod, two held items → 2nd item's outline FILLS ──
     * Reproduces only in FirstPersonMod 3.5D mode with a shader mod installed (no shader pack needed).
     * The FIRST-rendered held item outlines correctly; the SECOND fills entirely with the
     * outline color. 3P, vanilla 1P, ground items, and 1 held item all work flawlessly. Repros
     * with two swords (no BEWLR needed), so it's not a BEWLR-path interaction. Both items are
     * rendered inside FPM's player-as-entity render at HEAD of LevelRenderer.render (begins != 0
     * → the shader mod's FullyBufferedMultiBufferSource is in effect).
     *
     * Attempts that did NOT fix it (all reverted unless noted):
     *   1. Trailing `preBs.endBatch()` at the END of doItemOutline / doBewlrOutline to force
     *      each item's writeType+testType pair to drain atomically (so two items never share
     *      a FullyBuffered drain). Built fine. Did not fix the fill. Currently KEPT — benign
     *      and theoretically reduces drain-order risk.
     *   2. Setting `pendingFrameStencilClear = true` per-item at the top of doItemOutline /
     *      doBewlrOutline (re-arm the once-per-frame stencil clear so each item gets a fresh
     *      stencil baseline). REGRESSION: broke 3rd-person outlines. Reverted.
     *   3. Same per-item re-arm as #2 but gated on `FirstPersonAPI.isRenderingPlayer()` so it
     *      only fires inside the FPM 3.5D player render (leaves 3P/1P/ground untouched).
     *      Reverted at user request. Outcome on the 2-item fill: unconfirmed.
     *   4. Per-item re-arm of `pendingFrameStencilClear` in doItemOutline + doBewlrOutline +
     *      doModelOutline, gated on FPM-3.5D-active-in-first-person via a new
     *      `CustomGlintRenderer.perItemStencilClear` BooleanSupplier installed by
     *      FirstPersonCompat (FirstPersonAPI.isEnabled() && camera.isFirstPerson()).
     *      REGRESSION: under shader-mod-no-pack + FPM 3.5D, the 1-item case AND armor both started
     *      filling (worse than pre-fix). Reason: the WRITE shard's `setup()` only runs at drain
     *      time under FullyBuffered, not at recording time, so recording-side re-arm doesn't
     *      produce per-item glClear(STENCIL) — it just keeps the gate true long enough that the
     *      single glClear during drain fires at a moment that wipes a silhouette mid-batch
     *      between writeType segments, leaving stencil=0 inside silhouettes → EQUAL 0 passes →
     *      fills. The gate mechanism is timing-fragile and not the right lever here. Reverted.
     *   5. FPM-3.5D forward-pass fallback: added `heldItemForwardPassGate` BooleanSupplier OR'd
     *      into the `isShaderPackActive()` branches of doItemOutline / doBewlrOutline /
     *      doModelOutline, installed by FirstPersonCompat to `FirstPersonAPI.isRenderingPlayer()`
     *      so all three outline entry points took the existing POSITION_COLOR / 4-translate path
     *      whenever FPM was rendering the player. Intent: trade shape-perfect stencil for stability
     *      inside FPM's player-as-entity render. REGRESSION (significantly worse): in FPM 3.5D with
     *      shader-mod-installed-no-pack, items had NO outline at all, and armor's outline traced the
     *      full bone geometry (the "hole texture space" — bone rectangles where armor coverage is
     *      transparent) instead of the armor silhouette. Root cause: the shaderpack-active branch's
     *      RTs (`forShaderArmorOutline` / `forShaderOutline` / `forShaderSpriteOutline`) and the
     *      eye-space-Z trick rely on a real shaderpack composite to land their gbuffers_* output —
     *      with the shader mod loaded but no pack, those RTs route through entity-shader programs whose
     *      depth/color targets don't match the main FBO at draw time, so items go invisible. And
     *      the armor branch uses POSITION_COLOR with no texture sampling, so alpha-discard can't
     *      reject transparent armor texels → outline becomes the full HumanoidModel bone hull. The
     *      same warnings already in doBewlrOutline:1083 and doModelOutline:923 ("DO NOT widen to
     *      `|| isShaderModInstalled()`") apply equally to FPM-active-no-pack: shader-mod + no-pack
     *      is the poison context regardless of whether FPM is the trigger. Reverted entirely.
     *
     * Things to try next (notes from analysis, none implemented yet):
     *   - Log inside STENCIL_WRITE_LAYERING_ITEM.setup() / .clear() to confirm how many times
     *     each runs per frame in the 2-sword FPM 3.5D case, and whether glClear(STENCIL) fires
     *     at all for the second item. Speculation has run its course — runtime evidence first.
     *   - Inspect the shader mod's `vertices/MixinBufferBuilder` and `MixinGameRenderer` (not yet
     *     decompiled — context2 line 159/161). Either may explain why the 2nd item's stencil
     *     stamps don't land where expected under FullyBuffered drain.
     *   - Consider an FPM-3.5D-specific code path that bypasses stencil entirely for held items
     *     (e.g. POSITION_COLOR forward-pass outline like the shader-pack branch, but gated on
     *     `isRenderingPlayer()`). Last-resort: gives up shape-perfect outline for stability.
     *
     * Cross-references: context2.md (prior session handoff), `FirstPersonCompat.shouldSuppress`
     * (FPM detection wired but commented out), `pendingFrameStencilClear` javadoc (line ~395).
     */
    public static void doItemOutline(ItemStack stack, ItemDisplayContext displayContext,
            PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (displayContext == ItemDisplayContext.GUI) return;
        if (isInShadowPass()) return;
        // Compat: BEWLRs that paint a combined model via per-variant alpha-discarded textures
        // need their outline traced from the actual texture, not from a white.png AABB scale.
        // Those compats draw their own outline at the BEWLR's renderByItem RETURN.
        BlockEntityWithoutLevelRenderer customRendererInstance = IClientItemExtensions.of(stack).getCustomRenderer();
        if (customRendererInstance != null && CUSTOM_OUTLINE_BEWLRS.contains(customRendererInstance.getClass())) return;
        // Stack-aware color: pulls from glowColors (Glow Trim) or glint layer 0, else white.
        // Allows glow-only items (no glint Data) to still render the outline.
        int color = glintOutlineColor(stack);
        float oR = ((color >> 16) & 0xFF) / 255.0f;
        float oG = ((color >>  8) & 0xFF) / 255.0f;
        float oB = ( color        & 0xFF) / 255.0f;
        int rByte = (int)(oR * 255), gByte = (int)(oG * 255), bByte = (int)(oB * 255);

        Minecraft mc = Minecraft.getInstance();

        // Shader-pack forward-pass path: render dilated item geometry through POSITION_COLOR_SHADER
        // (universal shader-mod ShaderKey.BASIC_COLOR mapping). All item-render geometry passes through the
        // wrapping MultiBufferSource → PositionColorOnlyConsumer, producing a flat-colored dilated
        // silhouette. Scaled around the model AABB centroid (captured on the first pass) so the
        // outline ring stays concentric with the item.
        // Items only take forward-pass when a pack is ACTUALLY active — see doBewlrOutline for
        // the full rationale. ⚠ DO NOT widen this to `|| isShaderModInstalled()`.
        if (isShaderPackActive()) {
            // Under shader mods the stencil pipeline is dead (no OUTLINE entry in
            // ShaderKey enum). Split by item shape:
            //   - Custom-renderer (3D BEWLR) items: dilated POSITION_COLOR mesh around the AABB
            //     centroid via the universally-mapped BASIC_COLOR shader. Result is a flat-color
            //     silhouette slightly larger than the item — reads as an outline at the edges.
            //   - Flat sprite / BakedModel items (swords, tools, modded 3D-modeled items, ground
            //     drops): 4-direction translated pass using RenderType.entityCutoutNoCull(blocks
            //     atlas) which the shader mod maps to ENTITIES_CUTOUT (gbuffers_entities). The blocks atlas
            //     alpha-discard preserves the sprite silhouette; ColorOverrideConsumer forces the
            //     vertex color to the outline color so the shader emits ~ outlineColor × texColor.
            net.minecraft.client.player.LocalPlayer rp = mc.player;
            boolean lh = displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                      || displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
            boolean isGroundOrFixedS = displayContext == ItemDisplayContext.GROUND || displayContext == ItemDisplayContext.FIXED;
            boolean flatOnGroundS = isGroundOrFixedS
                    && (stack.getItem() == Items.TRIDENT
                        || stack.getItem() == Items.SPYGLASS
                        || FLAT_ON_GROUND_ITEMS.contains(stack.getItem().getClass().getName()));
            boolean customRendererS = mc.getItemRenderer().getModel(stack, mc.level, rp, 0).isCustomRenderer()
                    && !flatOnGroundS;

            // Flush the buffer source BEFORE drawing the outline. Under the shader mod's batched
            // pipeline the item's quads and our outline quads share the same BufferSource and
            // flush in render-type sort order, NOT call order — so the outline can end up
            // flushed before the item, meaning the depth buffer has nothing to test against
            // and the dilated/translated copies draw over the whole sword interior. Forcing
            // an endBatch() here commits the item's depth first; our outline then tests
            // against it correctly. The vanilla stencil path does the same.
            if (buffer instanceof MultiBufferSource.BufferSource preBs) preBs.endBatch();

            IN_OUTLINE.set(true);
            try {
                // First pass: capture AABB (used by both branches — 3D for scale pivot, 2D for translate step).
                float[] minMax = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                                   Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
                MultiBufferSource aabbSrc = rt -> new AABBTrackingConsumer(new NullConsumer(), minMax);
                poseStack.pushPose();
                mc.getItemRenderer().renderStatic(rp, stack, displayContext, lh, poseStack, aabbSrc, mc.level, packedLight, packedOverlay, 0);
                poseStack.popPose();
                if (!(minMax[3] > minMax[0])) return;

                if (customRendererS) {
                    // 3D BEWLR path: dilated mesh with front-face cull (same trick as armor).
                    // Only the dilated mesh's back faces draw; LEQUAL depth-test occludes them
                    // wherever they sit behind the original item's front face → ring effect,
                    // not a filled silhouette.
                    float cx = (minMax[0] + minMax[3]) * 0.5f;
                    float cy = (minMax[1] + minMax[4]) * 0.5f;
                    float cz = (minMax[2] + minMax[5]) * 0.5f;
                    RenderType outlineRT = asShaderOutline("custom_glint:shader_outline_3d_wrap", forShaderArmorOutline());
                    MultiBufferSource outSrc = rt -> new PositionColorOnlyConsumer(
                            buffer.getBuffer(outlineRT), rByte, gByte, bByte, 255);
                    poseStack.pushPose();
                    poseStack.last().pose().mulLocal(new Matrix4f()
                            .translate(cx, cy, cz)
                            .scale(1.06f, 1.06f, 1.06f)
                            .translate(-cx, -cy, -cz));
                    mc.getItemRenderer().renderStatic(rp, stack, displayContext, lh, poseStack, outSrc, mc.level, LightTexture.FULL_BRIGHT, packedOverlay, 0);
                    if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch(outlineRT);
                    poseStack.popPose();
                } else {
                    // 2D sprite / BakedModel path: 4-translation, entityCutoutNoCull on blocks atlas.
                    // ENTITIES_CUTOUT mapping is universal across shader mods; alpha-discard preserves the sprite
                    // silhouette so the dilated copies form a silhouette-shaped halo rather than a
                    // rectangle. ColorOverrideConsumer forces vertex color to the outline color so
                    // the shader emits ~ outlineColor × spriteTexel. The "extra textured copy" issue
                    // this used to cause under shaders is fixed by the eye-space Z push-back below,
                    // which guarantees the copies sit behind the item depth; only the fringe
                    // extending past the original silhouette survives LEQUAL and is visible.
                    RenderType outlineRT = asShaderOutline("custom_glint:shader_outline_sprite_wrap",
                            forShaderSpriteOutline(new ResourceLocation("minecraft", "textures/atlas/blocks.png")));
                    MultiBufferSource outSrc = rt -> new FullColorOverrideConsumer(
                            buffer.getBuffer(outlineRT), rByte, gByte, bByte, 255);
                    boolean is1P = displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                                || displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
                    boolean is3P = displayContext == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                                || displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
                    float stepScale = (is1P || is3P) ? 0.6f : 1.0f;
                    float dw = (minMax[3] - minMax[0]) / 16.0f * stepScale;
                    float dh = (minMax[4] - minMax[1]) / 16.0f * stepScale;
                    // Eye-space Z push-back. mulLocal(T) computes T * pose, so T is applied in
                    // eye-space — translate(_, _, -dz) shifts the dilated copies away from the
                    // camera (eye-space camera looks down -Z, so more-negative Z = further). This
                    // replaces PUSH_BACK_LAYERING's glPolygonOffset, which shader mods do not honor
                    // reliably in the outline phase under shader packs — without an actual depth
                    // separation, LEQUAL passes across the entire dilated rectangle and the outline
                    // fills the interior of the sprite. 0.03 in eye-space ≈ 3 cm; large enough to
                    // win LEQUAL at any reasonable view distance, small enough not to visibly
                    // detach the halo from the item. 1P uses a tighter hand projection (smaller
                    // near/far span), so the same eye-space delta covers less depth-buffer
                    // resolution — bump 1P specifically to clear residual z-fight there.
                    // 1P uses dz=0 — the eye-space translate works there too, but at any magnitude
                    // large enough to clear z-fight the perspective shrinkage projects the dilated
                    // copies toward screen-center (visible as the outline drifting toward the arm in
                    // 1P, because the held item sits off-center). The 1P hand pass renders
                    // sequentially (no shader-mod batched flushing), so the RT's baked PUSH_BACK_LAYERING
                    // polygon-offset stays alive and is sufficient to push the copies behind the
                    // sprite without any xy shift. 3P/ground keep the eye-Z translate because the shader mod
                    // drops the polygon-offset state during its batched outline phase there.
                    boolean isGroundOrFixedDz = displayContext == ItemDisplayContext.GROUND
                            || displayContext == ItemDisplayContext.FIXED;
                    // Ground/fixed items sit further from the camera, so 0.03 gets less depth-buffer
                    // resolution than the same value at 3P entity-render distance — bump it.
                    float dz = is1P ? 0.0f : (isGroundOrFixedDz ? 0.08f : 0.03f);
                    float[][] offsets = { { dw, 0 }, { -dw, 0 }, { 0, dh }, { 0, -dh } };
                    for (float[] off : offsets) {
                        poseStack.pushPose();
                        poseStack.last().pose().mulLocal(new Matrix4f().translate(off[0], off[1], -dz));
                        mc.getItemRenderer().renderStatic(rp, stack, displayContext, lh, poseStack, outSrc, mc.level, LightTexture.FULL_BRIGHT, packedOverlay, 0);
                        if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch(outlineRT);
                        poseStack.popPose();
                    }
                }
            } finally {
                IN_OUTLINE.set(false);
            }
            return;
        }
        // Pass mc.player so item-model overrides that depend on entity state (e.g. trident's
        // "throwing:1" predicate) resolve to the same model variant used in the original render.
        // With null, the throwing predicate always returns 0 → wrong display transforms → inverted outline.
        net.minecraft.client.player.LocalPlayer renderPlayer = mc.player;
        // BEWLR items (trident, shield, crossbow in 3D) use entity textures whose UVs don't map
        // to the blocks atlas. white.png (no alpha-discard) fills the full 3D model geometry so
        // the stencil and scale-based outline work correctly. Flat sprite items use the blocks
        // atlas for pixel-accurate silhouette outlines via translated passes.
        boolean isGroundOrFixed = displayContext == ItemDisplayContext.GROUND || displayContext == ItemDisplayContext.FIXED;
        boolean flatOnGround = isGroundOrFixed
                && (stack.getItem() == Items.TRIDENT
                    || stack.getItem() == Items.SPYGLASS
                    || FLAT_ON_GROUND_ITEMS.contains(stack.getItem().getClass().getName()));
        boolean customRenderer = mc.getItemRenderer().getModel(stack, mc.level, renderPlayer, 0).isCustomRenderer()
                && !flatOnGround;
        ResourceLocation outlineTex;
        if (customRenderer) {
            outlineTex = new ResourceLocation("textures/misc/white.png");
            ResourceLocation override = BEWLR_OUTLINE_TEXTURES.get(stack.getItem().getClass().getName());
            if (override != null) outlineTex = override;
        } else {
            outlineTex = new ResourceLocation("minecraft", "textures/atlas/blocks.png");
        }
        // Item-variant write: no polygon offset. Sprite/BEWLR base draws have no polygon offset,
        // and the armor-matching offset can push silhouette quads in front of the near plane in
        // 3.5D FPM at angles where the bone-rotated slope multiplies the units factor; result is
        // missing stencil stamps → filled-blob outline that shifts with camera angle.
        RenderType writeType = forOutlineStencilWriteItem(outlineTex);
        RenderType outlineType = forOutlineStencilTest(outlineTex);
        if (buffer instanceof MultiBufferSource.BufferSource preBs) preBs.endBatch();
        mc.getMainRenderTarget().enableStencil();
        IN_OUTLINE.set(true);
        // Pass the original displayContext to renderStatic so vanilla applies the correct
        // transform AND the context-dependent model swap (GROUND/FIXED swaps trident/spyglass
        // to the flat 2D icon). Using NONE here forced the 3D custom renderer for tridents on
        // the ground and bypassed flat-context transforms for sword previews.
        try {
            // Route the stencil pass through writeType (MAIN_TARGET + NO_WRITE writeMask + stencil-write
            // layering shard) so the stencil is written to the same FBO the outline pass reads from, and
            // so color/depth/stencil GL state is applied at DRAW time (shader mods replay buffered segments
            // long after submission — raw GL11 calls between submissions would be lost). Using native
            // item render types here would bind their own output FBO (ITEM_ENTITY_TARGET, etc.), leaving
            // the main FBO stencil all-zeros and causing the outline to fill the entire item silhouette.
            float[] minMax = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                               Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
            MultiBufferSource stencilSrc = rt -> new AABBTrackingConsumer(buffer.getBuffer(writeType), minMax);
            poseStack.pushPose();
            boolean leftHand = displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND || displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
            mc.getItemRenderer().renderStatic(renderPlayer, stack, displayContext, leftHand, poseStack, stencilSrc, mc.level, packedLight, packedOverlay, 0);
            poseStack.popPose();
            if (buffer instanceof MultiBufferSource.BufferSource bs2) bs2.endBatch(writeType);

            if (minMax[3] > minMax[0]) {
                MultiBufferSource outlineSrc = rt -> new ColorOverrideConsumer(buffer.getBuffer(outlineType), rByte, gByte, bByte, 255);
                RenderSystem.setShaderColor(oR, oG, oB, 1.0f);
                if (customRenderer) {
                    // BEWLR 3D model: scale-based outline around AABB center (white.png, no alpha-discard).
                    float cx = (minMax[0] + minMax[3]) * 0.5f;
                    float cy = (minMax[1] + minMax[4]) * 0.5f;
                    float cz = (minMax[2] + minMax[5]) * 0.5f;
                    poseStack.pushPose();
                    boolean is1P = displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                                || displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
                    boolean is3P = displayContext == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                                || displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
                    boolean isTrident1P = stack.getItem() == Items.TRIDENT && is1P;
                    boolean isTrident3P = stack.getItem() == Items.TRIDENT && is3P;
                    // ── 1st person vanilla trident outline offset (tune these) ──
                    float cxOff1P = 0.0f;   // X: right+, left-
                    float cyOff1P = -0.04f; // Y: up+,   down-
                    float czOff1P = 0.02f;  // Z: fwd+,  back-
                    // ── 3rd person vanilla trident outline offset (tune these) ──
                    float cxOff3P = 0.0f;   // X: right+, left-
                    float cyOff3P = 0.0f;   // Y: up+,   down-
                    float czOff3P = 0.0f;   // Z: fwd+,  back-
                    float cxOff = isTrident1P ? cxOff1P : isTrident3P ? cxOff3P : 0.0f;
                    float cyOff = isTrident1P ? cyOff1P : isTrident3P ? cyOff3P : 0.0f;
                    float czOff = isTrident1P ? czOff1P : isTrident3P ? czOff3P : 0.0f;
                    // Modded-item overrides via BEWLR_OUTLINE_OFFSETS (populated by compat code).
                    if (!isTrident1P && !isTrident3P && (is1P || is3P)) {
                        float[] o = BEWLR_OUTLINE_OFFSETS.get(stack.getItem().getClass().getName());
                        if (o != null && o.length >= 6) {
                            int base = is1P ? 0 : 3;
                            cxOff = o[base]; cyOff = o[base + 1]; czOff = o[base + 2];
                        }
                    }
                    poseStack.last().pose().mulLocal(new Matrix4f().translate(cx + cxOff, cy + cyOff, cz + czOff).scale(1.06f, 1.06f, 1.06f).translate(-cx, -cy, -cz));
                    mc.getItemRenderer().renderStatic(renderPlayer, stack, displayContext, leftHand, poseStack, outlineSrc, mc.level, LightTexture.FULL_BRIGHT, packedOverlay, 0);
                    if (buffer instanceof MultiBufferSource.BufferSource bsR) bsR.endBatch(outlineType);
                    poseStack.popPose();
                } else {
                    // Flat 2D sprite: translate ~1 pixel in 4 eye-space directions (blocks atlas, alpha-discard).
                    // Only opaque pixels shifted outside stencil=1 silhouette receive glow.
                    boolean is1P = displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                            || displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
                    boolean is3P = displayContext == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                            || displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
                    // Held contexts rotate the sprite, which inflates the eye-space AABB beyond
                    // the sprite's actual 16-pixel extent — making the 1/16-of-AABB step overshoot
                    // (visible as a too-thick outline on swords/tools). Shrink the step in 1P/3P;
                    // GROUND/FIXED render the sprite flat where the AABB matches and 1.0 is right.
                    float stepScale = (is1P || is3P) ? 0.6f : 1.0f;
                    // Eye-space Z push: mulLocal pre-multiplies translate * pose, applying T to
                    // already-eye-space vertices. OpenGL eye-space looks down -Z, so translate(_,_,-d)
                    // moves AWAY from camera. Pushing the dilated mesh slightly behind the item makes
                    // LEQUAL depth-test fail inside the silhouette → no draw, regardless of stencil
                    // state. Apply to BOTH 1P and 3P: 3.5D FPM renders the held item as 3P (attached
                    // to the player's hand bone), so without this it relied entirely on the stencil
                    // mask — which fails under shader-mod FullyBuffered drain when a second outlined
                    // item shares the screen. Depth-based masking is the reliable backstop.
                    float czOff    = (is1P || is3P) ? -0.01f : 0.0f;
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
            // Force a full drain so this item's silhouette+test pipeline executes atomically.
            // Under shader-mod FullyBuffered the per-RT endBatch(rt) calls above are no-ops; without
            // this trailing drain, the LAST held item of the frame stays queued until end-of-frame,
            // by which time stencil state from earlier items has been disrupted → silhouette stamp
            // misses → testType (EQUAL,0) passes everywhere → filled-blob. The first item already
            // gets drained when the NEXT item's preBs.endBatch() fires; this matches that.
            if (buffer instanceof MultiBufferSource.BufferSource postBs) postBs.endBatch();

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

    /** Same as ColorOverrideConsumer but forwards overlay/uv2/normal to the wrapped buffer.
     *  Needed when the underlying buffer uses the NEW_ENTITY vertex format
     *  (POSITION_COLOR_TEX_OVERLAY_LIGHTMAP_NORMAL) — dropping those fields leaves the
     *  BufferBuilder with unfilled elements and crashes on endVertex. The original
     *  ColorOverrideConsumer drops them on purpose because its only wrapped target is
     *  the stencil-test RenderType which uses POSITION_COLOR_TEX. */
    private static final class FullColorOverrideConsumer implements VertexConsumer {
        private final VertexConsumer wrapped;
        private final int r, g, b, a;
        FullColorOverrideConsumer(VertexConsumer wrapped, int r, int g, int b, int a) {
            this.wrapped = wrapped; this.r = r; this.g = g; this.b = b; this.a = a;
        }
        @Override public VertexConsumer vertex(double x, double y, double z) { wrapped.vertex(x, y, z); return this; }
        @Override public VertexConsumer vertex(Matrix4f m, float x, float y, float z) { wrapped.vertex(m, x, y, z); return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return wrapped.color(this.r, this.g, this.b, this.a); }
        @Override public VertexConsumer color(float r, float g, float b, float a) { return wrapped.color(this.r, this.g, this.b, this.a); }
        @Override public VertexConsumer uv(float u, float v) { return wrapped.uv(u, v); }
        @Override public VertexConsumer overlayCoords(int u, int v) { return wrapped.overlayCoords(u, v); }
        @Override public VertexConsumer uv2(int u, int v) { return wrapped.uv2(u, v); }
        @Override public VertexConsumer normal(float x, float y, float z) { return wrapped.normal(x, y, z); }
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

    private CustomGlintRenderer() { super("", () -> {}, () -> {}); }

    // ── Shader-mod detection ──────────────────────────────────────────────────
    // Reflective so we don't need a compileOnly dep on the shader mod. Common shader
    // mods expose the same public detection surface, resolved once and cached;
    // called every outline draw, so the Method is cached as a MethodHandle.
    //
    // Why this matters here: the shader mod's ShaderKey enum (the master list of every
    // RenderType→shader-program mapping under a loaded pack) has no `OUTLINE` entry.
    // That means draws using RENDERTYPE_OUTLINE_SHADER fall through to no destination
    // program under shaders — the stencil setup runs, but the visible color attachment
    // never receives our outline geometry. When a pack is loaded, we route the dilated
    // outline through vanilla's OutlineBufferSource instead — that pipeline (entity
    // outline target + EntityOutlineShader post-process composite) is one the shader
    // mod explicitly preserves to keep vanilla glowing working under shaders.
    private static volatile boolean SHADER_LOOKUP_DONE = false;
    private static volatile java.lang.reflect.Method SHADER_GET_INSTANCE = null;
    private static volatile java.lang.reflect.Method SHADER_IS_IN_USE = null;

    public static boolean isShaderPackActive() {
        if (!SHADER_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!SHADER_LOOKUP_DONE) {
                    try {
                        // FQN reads "iris" because Forge Oculas is a port of Fabric Iris
                        // one and kept the original `net.irisshaders.*` package paths verbatim —
                        // this same class is present on Forge. Do not "fix" it.
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
     * whenever it's loaded, which breaks our stencil-based outline path even with no pack —
     * so the forward-pass outline branch needs to trigger on presence, not just active-pack.
     */
    public static boolean isShaderModInstalled() {
        if (!SHADER_LOOKUP_DONE) isShaderPackActive();
        return SHADER_IS_IN_USE != null;
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
    private static volatile java.lang.reflect.Method SHADER_TT_SET = null;
    private static volatile Object SHADER_TT_LINES = null;
    private static final java.util.Set<RenderType> SHADER_TT_TAGGED =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

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

    // The shader mod's official extension point for tagging a RenderType as "outline geometry."
    // Wrapping a RT with IsOutlineRenderStateShard.INSTANCE via OuterWrappedRenderType causes
    // GbufferPrograms.beginOutline()/endOutline() to fire around the draw call, which is what
    // the shader mod itself uses to mark the vanilla block-selection outline. Without this tag,
    // our shader-path outline geometry gets routed through the shader mod's batched
    // FullyBufferedMultiBufferSource alongside the item geometry and replayed in render-type sort
    // order — the outline can flush before the item, leaving the depth buffer empty when LEQUAL
    // runs, so polygon-offset/front-face-cull can't reject the dilated copies over the interior
    // and the outline reads as a filled silhouette. In 1P this happened to work because the hand
    // pass renders sequentially; in 3P/GROUND the entity batch is what breaks ordering. Tagging
    // routes the outline through the shader mod's outline phase so it draws in the correct depth
    // context. Reflective lookup so no compileOnly dep.
    private static volatile boolean SHADER_OUTLINE_LOOKUP_DONE = false;
    private static volatile java.lang.reflect.Method SHADER_WRAP_OUTLINE_RT = null;
    private static volatile RenderStateShard SHADER_OUTLINE_SHARD = null;
    private static final Map<RenderType, RenderType> SHADER_OUTLINE_WRAP_CACHE = new ConcurrentHashMap<>();

    public static RenderType asShaderOutline(String name, RenderType rt) {
        if (!SHADER_OUTLINE_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!SHADER_OUTLINE_LOOKUP_DONE) {
                    try {
                        Class<?> wrapCls = Class.forName("net.irisshaders.iris.layer.OuterWrappedRenderType");
                        Class<?> shardCls = Class.forName("net.irisshaders.iris.layer.IsOutlineRenderStateShard");
                        SHADER_WRAP_OUTLINE_RT = wrapCls.getMethod("wrapExactlyOnce",
                                String.class, RenderType.class, RenderStateShard.class);
                        SHADER_OUTLINE_SHARD = (RenderStateShard) shardCls.getField("INSTANCE").get(null);
                    } catch (Throwable ignored) {
                        SHADER_WRAP_OUTLINE_RT = null;
                        SHADER_OUTLINE_SHARD = null;
                    }
                    SHADER_OUTLINE_LOOKUP_DONE = true;
                }
            }
        }
        if (SHADER_WRAP_OUTLINE_RT == null || SHADER_OUTLINE_SHARD == null) return rt;
        // Without an active shaderpack, the wrap actively harms entity-shader-based RTs: the
        // shader mod auto-injects EntityRenderStateShard onto RENDERTYPE_ENTITY_*_SHADER types,
        // so the resulting composite fires beginEntities() then beginOutline() — the second call
        // hits GbufferPrograms.checkReentrancy() with entities=true and throws, aborting the draw.
        // The shader mod's outline routing only matters during pack composite anyway, so skip it here.
        if (!isShaderPackActive()) return rt;
        return SHADER_OUTLINE_WRAP_CACHE.computeIfAbsent(rt, k -> {
            try {
                return (RenderType) SHADER_WRAP_OUTLINE_RT.invoke(null, name, k, SHADER_OUTLINE_SHARD);
            } catch (Throwable t) {
                return k;
            }
        });
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
    private static volatile java.lang.reflect.Method SHADOW_IS_RENDERING = null;

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
