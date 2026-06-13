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
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.tunamods.customglint.common.CustomGlint;
import org.joml.Matrix4f;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

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

    /**
     * Lazily-built parallel of LOCATION_BLOCKS where every pixel has RGB=255 and the original
     * alpha. Bound as Sampler0 in the held-sprite outline RT under shader packs so the pack's
     * gbuffers_entities shader computes {@code white × vertexColor = vertexColor} — i.e. the
     * ring renders in the chosen outline color instead of being tinted by the underlying
     * item's texture. Built once on first use; cleared on resource reload via
     * {@link #clearTextures} so the next use rebuilds against the freshly stitched atlas.
     */
    public static final ResourceLocation BLOCKS_ALPHA_MASK_LOC =
            new ResourceLocation(MOD_ID, "textures/atlas/blocks_alpha_mask");
    private static boolean blocksAlphaMaskBuilt = false;

    /** Returns the alpha-mask atlas location, building it if needed. May return the location
     *  even if the build fails (atlas not yet stitched); callers will bind a missing texture
     *  and the pack will fall back to vanilla magenta — not catastrophic. The next call
     *  retries the build. */
    public static ResourceLocation getBlocksAlphaMask() {
        if (!blocksAlphaMaskBuilt) buildBlocksAlphaMask();
        return BLOCKS_ALPHA_MASK_LOC;
    }

    private static void buildBlocksAlphaMask() {
        Minecraft mc = Minecraft.getInstance();
        TextureAtlas atlas;
        try {
            atlas = mc.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        } catch (Throwable t) {
            LOGGER.warn("[{}/CustomGlint] buildBlocksAlphaMask: getAtlas threw", MOD_ID, t);
            return;
        }
        if (atlas == null) {
            LOGGER.warn("[{}/CustomGlint] buildBlocksAlphaMask: atlas is null", MOD_ID);
            return;
        }
        int w = atlas.width;
        int h = atlas.height;
        if (w <= 0 || h <= 0) {
            LOGGER.warn("[{}/CustomGlint] buildBlocksAlphaMask: bad dims, skipping", MOD_ID);
            return;
        }

        // Save current GL binding so we don't disturb whatever's in flight (no expected caller
        // since this runs lazily on the render thread between draws, but cheap to be safe).
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager._bindTexture(atlas.getId());
        NativeImage mask = new NativeImage(w, h, false);
        try {
            // downloadTexture reads bound GL texture into the NativeImage. removeAlpha=false
            // → keep the alpha channel; we'll then overwrite RGB to 255 per pixel.
            mask.downloadTexture(0, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    // NativeImage stores (A<<24)|(B<<16)|(G<<8)|R. Keep top 8 bits (alpha),
                    // set the rest to 0xFFFFFF → RGB=255.
                    int pixel = mask.getPixelRGBA(x, y);
                    mask.setPixelRGBA(x, y, (pixel & 0xFF000000) | 0x00FFFFFF);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[{}/CustomGlint] buildBlocksAlphaMask: download/rewrite threw", MOD_ID, t);
            mask.close();
            GlStateManager._bindTexture(prev);
            return;
        }
        GlStateManager._bindTexture(prev);

        // Release any previous registration before replacing (reload path).
        try { mc.getTextureManager().release(BLOCKS_ALPHA_MASK_LOC); } catch (Throwable ignored) {}
        DynamicTexture dt = new DynamicTexture(mask);
        mc.getTextureManager().register(BLOCKS_ALPHA_MASK_LOC, dt);
        dt.bind();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        blocksAlphaMaskBuilt = true;
    }

    /** Per-armor-texture alpha-mask cache. Parallel of the source armor PNG with RGB=255 and
     *  alpha preserved. Bound as Sampler0 in {@link #forShaderArmorOutlineTextured} under shader
     *  packs so the pack's gbuffers_entities shader computes {@code white × vertexColor =
     *  vertexColor} — outline ring renders in the chosen color instead of being tinted by the
     *  armor's albedo (worst case: white outline × red leather armor = red). Cleared on resource
     *  reload via {@link #clearTextures}. */
    private static final Map<ResourceLocation, ResourceLocation> ARMOR_ALPHA_MASKS = new HashMap<>();

    public static ResourceLocation getArmorAlphaMask(ResourceLocation original) {
        if (original == null) return null;
        ResourceLocation cached = ARMOR_ALPHA_MASKS.get(original);
        if (cached != null) return cached;
        Minecraft mc = Minecraft.getInstance();
        NativeImage src;
        boolean srcOwned;  // close src only if we read from a resource stream
        try {
            var res = mc.getResourceManager().getResource(original);
            if (res.isPresent()) {
                try (InputStream stream = res.get().open()) {
                    src = NativeImage.read(stream);
                }
                srcOwned = true;
            } else {
                // Resource not in the pack — might be a registered DynamicTexture (e.g. EK
                // WingedHussar's symmetrized chest variant from armorOutlineTextureRemap).
                // Falling back to `return original` here would bind the dynamic texture's
                // actual armor pixels for the outline, so gbuffers_entities multiplies
                // vertexColor × armor RGB and the outline picks up the armor's albedo
                // (red WingedHussar → purple-outline-tinted-red). Read the DynamicTexture's
                // backing NativeImage instead so the alpha mask is RGB=255 + binarized alpha.
                AbstractTexture tex =
                        mc.getTextureManager().getTexture(original);
                if (tex instanceof DynamicTexture dt && dt.getPixels() != null) {
                    src = dt.getPixels();
                    srcOwned = false;
                } else {
                    return original;
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[{}/CustomGlint] getArmorAlphaMask: read threw for {}", MOD_ID, original, t);
            return original;
        }
        NativeImage mask = new NativeImage(src.getWidth(), src.getHeight(), false);
        try {
            // Binarize alpha: any source alpha > 0 → 255 in the mask, else 0. ENTITY_CUTOUT_NO_CULL
            // (used by shader-pack outline RTs) discards at alpha < 0.1 (~25/255), while the
            // shaders-off OUTLINE_SHADER stencil path discards at alpha == 0. Textures with
            // anti-aliased edges (chainmail, lace, fine detail) have sub-threshold alpha at the
            // perimeter pixels — they outline correctly under stencil but vanish under shaders.
            // Binarizing here equalizes the two paths so the shader-pack outline covers every
            // pixel the stencil pipeline would have stamped. RGB stays 255 so the pack computes
            // {@code white × vertexColor = vertexColor} (outline color is preserved, not tinted
            // by the source albedo).
            for (int y = 0; y < src.getHeight(); y++) {
                for (int x = 0; x < src.getWidth(); x++) {
                    int alpha = (src.getPixelRGBA(x, y) >> 24) & 0xFF;
                    int outAlpha = alpha > 0 ? 0xFF : 0x00;
                    mask.setPixelRGBA(x, y, (outAlpha << 24) | 0x00FFFFFF);
                }
            }
        } finally {
            if (srcOwned) src.close();
        }
        String safePath = original.getNamespace() + "_" + original.getPath().replace('/', '_').replace('.', '_');
        ResourceLocation loc = new ResourceLocation(MOD_ID, "armor_alpha_mask/" + safePath);
        DynamicTexture dt = new DynamicTexture(mask);
        mc.getTextureManager().register(loc, dt);
        dt.bind();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        ARMOR_ALPHA_MASKS.put(original, loc);
        return loc;
    }

    public static void clearTextures() {
        Minecraft mc = Minecraft.getInstance();
        for (ResourceLocation loc : textureCache.values())
            if (loc != null) mc.getTextureManager().release(loc);
        textureCache.clear();
        if (blocksAlphaMaskBuilt) {
            try { mc.getTextureManager().release(BLOCKS_ALPHA_MASK_LOC); } catch (Throwable ignored) {}
            blocksAlphaMaskBuilt = false;
        }
        for (ResourceLocation loc : ARMOR_ALPHA_MASKS.values()) {
            try { mc.getTextureManager().release(loc); } catch (Throwable ignored) {}
        }
        ARMOR_ALPHA_MASKS.clear();
        for (Runnable r : additionalReloadCleanup) {
            try { r.run(); } catch (Throwable t) {
                LOGGER.warn("[{}/CustomGlint] additional reload cleanup threw", MOD_ID, t);
            }
        }
        if (fixedBufferRegistry != null) {
            for (RenderType rt : BY_GLINT.values())             fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_ARMOR_GLINT.values())       fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_HORSE_ARMOR_GLINT.values()) fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_MOUNT_ARMOR_GLINT.values()) fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_MOUNT_ARMOR_MASK.values())  fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_BODY_DEPTH_FILL.values())   fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_OUTLINE_WRITE.values())     fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_OUTLINE_WRITE_ITEM.values()) fixedBufferRegistry.remove(rt);
            for (RenderType rt : BY_OUTLINE_TEST.values())      fixedBufferRegistry.remove(rt);
            for (int i = 0; i < 256; i++) {
                if (SLOT_WRITE[i]       != null) fixedBufferRegistry.remove(SLOT_WRITE[i]);
                if (SLOT_WRITE_ITEM[i]  != null) fixedBufferRegistry.remove(SLOT_WRITE_ITEM[i]);
                if (SLOT_TEST[i]        != null) fixedBufferRegistry.remove(SLOT_TEST[i]);
                if (SLOT_TEST_CULLED[i] != null) fixedBufferRegistry.remove(SLOT_TEST_CULLED[i]);
            }
        }
        BY_GLINT.clear();
        BY_ARMOR_GLINT.clear();
        BY_HORSE_ARMOR_GLINT.clear();
        BY_MOUNT_ARMOR_GLINT.clear();
        BY_MOUNT_ARMOR_MASK.clear();
        BY_BODY_DEPTH_FILL.clear();
        BY_OUTLINE_WRITE.clear();
        BY_OUTLINE_WRITE_ITEM.clear();
        BY_OUTLINE_TEST.clear();
        GLINT_COLORS.clear();
        for (int i = 0; i < 256; i++) {
            SLOT_WRITE[i] = null;
            SLOT_WRITE_ITEM[i] = null;
            SLOT_TEST[i] = null;
            SLOT_TEST_CULLED[i] = null;
            SLOT_WRITE_TEX[i] = null;
            SLOT_WRITE_ITEM_TEX[i] = null;
            SLOT_TEST_TEX[i] = null;
            SLOT_TEST_CULLED_TEX[i] = null;
        }
        // TRIED (did not fix): after Iris/Oculus pack ON→OFF toggle, items in item frames develop
        // a "lens through walls" effect — looking through one outlined item shows other outlined
        // items behind walls. Hypothesis was that Iris's pipeline-destroy path leaves vanilla's
        // RenderTarget.useStencil = false even though the depth-stencil attachment is still there
        // (forced by iris's MixinRenderTarget_StencilBufferTest), making glStencilFunc undefined.
        // Tried: `Minecraft.getInstance().getMainRenderTarget().enableStencil()` here in
        // clearTextures (fires on resource reload = shader toggle) AND every frame in
        // CustomGlintClientInit's RenderTickEvent.START. Both no-ops in practice — symptom
        // persisted. Stencil-flag is NOT the cause. Real cause unknown; do not re-attempt this
        // angle without new evidence.
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
    private static final Map<String, RenderType> BY_MOUNT_ARMOR_GLINT  = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> BY_MOUNT_ARMOR_MASK = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> BY_BODY_DEPTH_FILL = new HashMap<>();

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
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            return rt;
        });
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(cached)) live.put(cached, new BufferBuilder(cached.bufferSize()));
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
        String key = "mount|" + layer.design() + "|" + Arrays.toString(layer.colors()) + "|" + layer.speed() + "|" + layer.patternScale() + "|" + colorIdx + "|" + layerIdx;
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
                            .setTexturingState(new TexturingStateShard(MOD_ID + ":custom_mount_armor_glint_texturing", () -> {
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

    /**
     * Depth-only fill of an entity model's full geometry silhouette, ignoring texture alpha.
     * Companion to {@link #doModelOutline} for IaF mount armor: IaF dragon / hippogryph /
     * hippocampus body textures have transparent regions (between scales, wing membranes,
     * feather gaps); the normal {@code entityCutoutNoCull} body render discards those fragments
     * so no depth is written for them. With nothing in the depth buffer at those pixels, the
     * outline TEST pass's LEQUAL test passes for back-side armor's dilated mesh — the player
     * sees the BACK armor outline through the FRONT of the mob. Running this RT against the
     * parent body model BEFORE doModelOutline writes depth at every geometry fragment so the
     * outline test is correctly occluded by the mob's full silhouette regardless of alpha.
     *
     * Uses RENDERTYPE_ENTITY_SOLID_SHADER (no alpha-discard) + DEPTH-only write mask + LEQUAL
     * depth. Bound texture is irrelevant since color isn't written; the shader still needs a
     * Sampler0 binding so pass any valid texture (callers pass the armor texture for simplicity).
     */
    public static RenderType forBodyDepthFill(ResourceLocation tex) {
        RenderType cached = BY_BODY_DEPTH_FILL.computeIfAbsent(tex, t -> {
            RenderType rt = RenderType.create(
                    MOD_ID + ":body_depth_fill|" + t.toString().hashCode(),
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    256, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_ENTITY_SOLID_SHADER)
                            .setTextureState(new TextureStateShard(t, false, false))
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(false, true))
                            .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            return rt;
        });
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(cached)) live.put(cached, new BufferBuilder(cached.bufferSize()));
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
     * Opt-in for a full-silhouette stencil WRITE in {@link #doModelOutline}. When set true around an
     * outline call, the stencil WRITE pass stamps the whole geometry silhouette (white.png, no
     * alpha-discard) instead of the texture's opaque texels, so the dilated back-side ring is
     * suppressed even across transparent body regions. Correct for mounts whose armor covers the
     * WHOLE body (IaF dragon barding — the bare wing membranes are transparent and would otherwise
     * leak the back-side ring). Leave false for partial-coverage armor so the ring hugs the armor
     * rather than wrapping the entire creature. Default false. Callers must reset it (try/finally).
     */
    public static final ThreadLocal<Boolean> OUTLINE_FULL_SILHOUETTE = ThreadLocal.withInitial(() -> false);

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
    public static BooleanSupplier outlineSuppressor = () -> false;

    /**
     * FPM 3.5D detection gate for the shader-pack item outline path. Installed by
     * {@code FirstPersonClientCompat} when FPM is present. Returns true while FPM is actively
     * rendering the local player in 3.5D view (camera at eye, player body in 3P).
     *
     * Used in {@link #doItemOutline}'s shader-pack sprite branch to switch from the standard
     * eye-space Z push ({@code dz = 0.03}) to {@code dz = 0.0}. At FPM's near-1P camera
     * distance (~0.7 eye-space Z vs. ~3.0 for vanilla 3P), the same {@code -dz} translate
     * causes ~4× more perspective shrinkage on screen, visibly shifting the 4 translated
     * sprite copies toward screen-center (i.e. "misaligned" outline). Setting dz=0 eliminates
     * the shift and relies on the RT's baked {@code PUSH_BACK_LAYERING} polygon offset for
     * depth separation, exactly as the 1P hand-render path does.
     */
    public static BooleanSupplier fpmRenderingPlayerGate = () -> false;

    /** True if FPM (First Person Mod by tr7zw) is loaded and wired. Set by {@code FirstPersonClientCompat}. */
    public static boolean fpmPresent = false;

    /**
     * Predicate consulted by {@code HumanoidArmorLayerMixin} for chest-slot outline calls.
     * Keyed on the resolved armor TEXTURE (not the item) so the gate can target specific
     * pieces by texture path without per-item class checks. When true for the given texture,
     * the mixin hides {@code HumanoidModel.rightArm} and {@code leftArm} for the single
     * {@link #doModelOutline} call, then restores them.
     *
     * Installed by {@code EpicKnightsClientCompat} when Epic Knights is loaded. Targets ONLY
     * EK's "halfarmor" chestplate (a vanilla-layout HumanoidModel where the arm cuboids
     * sample opaque pixels in the chest texture even though no arm armor is visually
     * intended). Other EK chest pieces have full-sleeve coverage and still get arm outlines.
     */
    public static Predicate<ResourceLocation>
            chestArmorHidesArmsInOutline = tex -> false;

    /**
     * Optional remap consulted by {@link #doModelOutline} for the WRITE/TEST stencil RTs
     * and the shader-pack outline RT. When set, the input armor texture is replaced by a
     * substitute texture (typically a pre-baked synthesized variant) for the outline pass
     * only — normal armor render and glint pass keep using the original texture.
     *
     * Installed by {@code EpicKnightsClientCompat} for EK's WingedHussar chestplate: its
     * wings are flat 0-width cuboids whose two visible faces sample two DIFFERENT UV
     * regions (cols 36–49 entirely transparent vs cols 50–63 containing the feather
     * shape). Without remap, the outline pass stamps stencil across the full cuboid
     * rectangle (UV-wrap / edge-interpolation artifacts on the asymmetric layout) and
     * the dilated test traces the rectangle instead of the feather. The remap returns
     * a synthesized texture where cols 36–49 mirror cols 50–63, so BOTH faces sample
     * the feather alpha and the stencil/outline correctly trace the feather shape.
     */
    public static Function<ResourceLocation, ResourceLocation>
            armorOutlineTextureRemap = tex -> tex;

    /** Reload hooks appended by compat modules; invoked by {@link #clearTextures()} so each
     *  compat can release its own {@code DynamicTexture}s without {@code CustomGlintRenderer}
     *  needing to know about them. */
    public static final List<Runnable> additionalReloadCleanup = new CopyOnWriteArrayList<>();

    /**
     * Resolves child ModelParts that should be HIDDEN during the standard chest outline pass
     * (and not re-outlined by anything else — they simply get no outline). Intended for thin /
     * flat / far-from-pivot decorations whose silhouette can't be cleanly outlined by either
     * the dilation-based path (1.04× around the chest pivot ghosts vertices by many pixels for
     * geometry far from the pivot) or a pixel-translate path (overlapping coplanar parts can't
     * be properly depth-occluded against each other).
     *
     * Returns {@code null} when the model/texture has no parts needing special handling.
     * Installed by {@code EpicKnightsClientCompat} for EK's WingedHussar wings.
     */
    public static BiFunction<HumanoidModel<?>, ResourceLocation, ModelPart[]>
            armorExtraOutlineParts = (m, tex) -> null;

    /**
     * Frame-scoped identity set for shader-mode outline deduplication when FPM is active.
     * FPM renders the local player's held items through multiple paths (entity pass + arm/hand
     * pass); the forward-pass outline path is additive, so the second render stacks a visible
     * second ring on top of the first. We deduplicate by {@link ItemStack} reference: the same
     * instance is passed through all render paths for a given inventory slot, so reference
     * equality correctly identifies "same item, same frame, different path."
     * Reset at frame start by {@code CustomGlintClientInit}.
     */
    public static final Set<Object> shaderOutlinedThisFrame =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /** Separate from BY_GLINT: outline uses POSITION_COLOR_TEX + OUTLINE_SHADER, not POSITION_TEX + GLINT_SHADER. */
    private static final Map<ResourceLocation, RenderType> BY_SHADER_ARMOR_OUTLINE_TEX = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, RenderType> BY_OUTLINE_WRITE = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> BY_OUTLINE_WRITE_ITEM = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> BY_OUTLINE_TEST  = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> BY_OUTLINE_TEST_CULLED = new HashMap<>();

    // ── Per-outline stencil-value isolation (slot pool) ────────────────────────
    //
    // The 8-bit stencil buffer holds values 0..255. We reserve 0 for "no silhouette"
    // and allocate values 1..255 as unique "slots" — one per outline call per frame.
    //   • WRITE shard for slot V: stamp stencil=V at this object's silhouette.
    //   • TEST  shard for slot V: draw the dilated ring where stencil != V.
    // Other objects' silhouettes (stencil=V_other) PASS the != V test, so the dilation
    // is allowed to draw on top of them — depth-test still gates per-pixel, so the ring
    // is hidden where it's physically occluded.
    //
    // Each slot has its own RenderType instance. Iris/Oculus's
    // FullyBufferedMultiBufferSource queues draws and at endBatch() drains them in
    // GraphTranslucencyRenderOrderManager order: WRITEs in GENERAL_TRANSPARENT bucket
    // (per-slot call order via digraph edges within the active entity group), TESTs in
    // the LINES bucket (tagAsLateRenderForShaders) so all writes complete first. Each RT
    // drains independently with its own setup() → stencilFunc(V) → draw → clear(). Two
    // identical swords get different slots → different RTs → independent stencils.
    //
    // Ceiling = 255 isolated outlines per frame. Beyond that, slot wraps to 1, sharing
    // a stencil value (= cross-contamination with the colliding earlier outline only).
    //
    // Counter is reset to 0 at frame start by CustomGlintClientInit's RenderTickEvent.START.
    private static int stencilSlotCounter = 0;

    /** Reserve a unique stencil slot value (1..255) for this outline call. Wraps if exceeded. */
    public static int nextStencilSlot() {
        stencilSlotCounter++;
        if (stencilSlotCounter > 255) stencilSlotCounter = 1;
        return stencilSlotCounter;
    }

    /** Frame-start reset; called from CustomGlintClientInit. */
    public static void resetStencilSlots() { stencilSlotCounter = 0; }

    private static final RenderType[]       SLOT_WRITE        = new RenderType[256];
    private static final RenderType[]       SLOT_WRITE_ITEM   = new RenderType[256];
    private static final RenderType[]       SLOT_TEST         = new RenderType[256];
    private static final RenderType[]       SLOT_TEST_CULLED  = new RenderType[256];
    private static final ResourceLocation[] SLOT_WRITE_TEX       = new ResourceLocation[256];
    private static final ResourceLocation[] SLOT_WRITE_ITEM_TEX  = new ResourceLocation[256];
    private static final ResourceLocation[] SLOT_TEST_TEX        = new ResourceLocation[256];
    private static final ResourceLocation[] SLOT_TEST_CULLED_TEX = new ResourceLocation[256];

    /** Per-slot stencil-write layering shard. {@code withPolyOffset=true} matches armor's
     *  VIEW_OFFSET_Z_LAYERING(-1,-10); items use {@code false}. dpfail=REPLACE: stamp the
     *  slot value at every projected silhouette pixel even when the depth test fails. The
     *  alternative (dpfail=KEEP) leaves precision-edge pixels unstamped — then the TEST
     *  pass's stencil NOT_EQUAL V passes inside the silhouette where stamps are missing,
     *  producing z-fighting bleed-through (dilated mesh fills the interior). Side effect:
     *  occluded objects can stamp their slot value through occluders — see TEST shard for
     *  why that's acceptable (depth-test in the TEST pass still hides incorrect rings). */
    private static RenderStateShard.LayeringStateShard stencilWriteLayeringSlot(final int v, final boolean withPolyOffset) {
        return new RenderStateShard.LayeringStateShard(
                "custom_glint_stencil_write_slot_v" + v + (withPolyOffset ? "_po" : ""),
                () -> {
                    Minecraft.getInstance().getMainRenderTarget().enableStencil();
                    GL11.glEnable(GL11.GL_STENCIL_TEST);
                    GL11.glStencilMask(0xFF);
                    if (pendingFrameStencilClear) {
                        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
                        pendingFrameStencilClear = false;
                    }
                    GL11.glStencilFunc(GL11.GL_ALWAYS, v, 0xFF);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_REPLACE, GL11.GL_REPLACE);
                    if (withPolyOffset) {
                        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
                        GL11.glPolygonOffset(-1.0f, -10.0f);
                    }
                },
                () -> {
                    GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
                    GL11.glDisable(GL11.GL_STENCIL_TEST);
                    if (withPolyOffset) {
                        GL11.glPolygonOffset(0.0f, 0.0f);
                        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
                    }
                });
    }

    /** Per-slot stencil-test layering shard. Tests stencil != V so the dilated ring draws
     *  everywhere except where this slot stamped its silhouette. Depth-test (LEQUAL on the
     *  RT) still hides parts that are occluded by closer geometry. */
    private static RenderStateShard.LayeringStateShard stencilTestLayeringSlot(final int v, final boolean cullFront) {
        return new RenderStateShard.LayeringStateShard(
                "custom_glint_stencil_test_slot_v" + v + (cullFront ? "_cf" : ""),
                () -> {
                    GL11.glEnable(GL11.GL_STENCIL_TEST);
                    GL11.glStencilMask(0xFF);
                    GL11.glStencilFunc(GL11.GL_NOTEQUAL, v, 0xFF);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
                    if (cullFront) {
                        GL11.glEnable(GL11.GL_CULL_FACE);
                        GL11.glCullFace(GL11.GL_FRONT);
                    }
                },
                () -> {
                    GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
                    GL11.glDisable(GL11.GL_STENCIL_TEST);
                    if (cullFront) GL11.glCullFace(GL11.GL_BACK);
                });
    }

    // TRIED (do not re-attempt) on all four slot RTs (SLOT_WRITE / SLOT_WRITE_ITEM /
    // SLOT_TEST / SLOT_TEST_CULLED): swap shader state from RENDERTYPE_OUTLINE_SHADER to
    // POSITION_COLOR_TEX_SHADER, hypothesising that the shader-toggle "lens through walls"
    // bleed-through was caused by our test draws leaking into vanilla's outline FBO because
    // FORCE_MAIN_TARGET stops working after an Iris pack toggle.
    //   RESULT: broke many outlines (visible silhouettes vanished / went wrong on most paths
    //   that previously worked). The shader-pack-active path's existing TRIED log around
    //   forShaderArmorOutlineTextured already documents the same dead end from the other side:
    //   Iris's EntityRenderStateShard wrap substitutes POSITION_COLOR_TEX → invisible even with
    //   FORCE_MAIN_TARGET. RENDERTYPE_OUTLINE_SHADER is the only shader state that survives
    //   Iris's swaps and still draws to the main target via FORCE_MAIN_TARGET on the
    //   pre-toggle pipeline. The shader-toggle bleed-through must be addressed without
    //   touching the shader state of these RTs.

    /** Slot-based WRITE RT for armor (polygon-offset variant). Texture is rebound at drain
     *  via the per-slot mutable holder, so each slot can serve any texture per frame. */
    public static RenderType forOutlineStencilWrite(int v, ResourceLocation texture) {
        SLOT_WRITE_TEX[v] = texture;
        if (SLOT_WRITE[v] == null) {
            final int slot = v;
            RenderType rt = RenderType.create(
                    MOD_ID + ":glint_outline_write_v" + v,
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_OUTLINE_SHADER)
                            .setTextureState(new TextureStateShard(
                                    new ResourceLocation(MOD_ID, "stencil_slot_w_" + v), false, false) {
                                @Override public void setupRenderState() {
                                    RenderSystem.setShaderTexture(0, SLOT_WRITE_TEX[slot]);
                                }
                            })
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setOutputState(FORCE_MAIN_TARGET)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setWriteMaskState(NO_WRITE)
                            .setLayeringState(stencilWriteLayeringSlot(v, true))
                            .createCompositeState(false));
            SLOT_WRITE[v] = rt;
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
        }
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(SLOT_WRITE[v]))
            live.put(SLOT_WRITE[v], new BufferBuilder(SLOT_WRITE[v].bufferSize()));
        return SLOT_WRITE[v];
    }

    /** Slot-based WRITE RT for items (no polygon offset). */
    public static RenderType forOutlineStencilWriteItem(int v, ResourceLocation texture) {
        SLOT_WRITE_ITEM_TEX[v] = texture;
        if (SLOT_WRITE_ITEM[v] == null) {
            final int slot = v;
            RenderType rt = RenderType.create(
                    MOD_ID + ":glint_outline_write_item_v" + v,
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_OUTLINE_SHADER)
                            .setTextureState(new TextureStateShard(
                                    new ResourceLocation(MOD_ID, "stencil_slot_wi_" + v), false, false) {
                                @Override public void setupRenderState() {
                                    RenderSystem.setShaderTexture(0, SLOT_WRITE_ITEM_TEX[slot]);
                                }
                            })
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setOutputState(FORCE_MAIN_TARGET)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setWriteMaskState(NO_WRITE)
                            .setLayeringState(stencilWriteLayeringSlot(v, false))
                            .createCompositeState(false));
            SLOT_WRITE_ITEM[v] = rt;
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
        }
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(SLOT_WRITE_ITEM[v]))
            live.put(SLOT_WRITE_ITEM[v], new BufferBuilder(SLOT_WRITE_ITEM[v].bufferSize()));
        return SLOT_WRITE_ITEM[v];
    }

    // TRIED (did not fix) on both SLOT_TEST and SLOT_TEST_CULLED:
    //   .setWriteMaskState(COLOR_WRITE)   // color-only, no depth-write
    // Intent was to kill the armour-neighbour z-fight that appears after a resource reload
    // (two armour pieces on the same entity flicker against each other's outline rings). Theory:
    // both dilated TEST meshes write near-equal depth values at their overlap → z-fight strobe;
    // depth-write OFF would remove the contention while LEQUAL depth-TEST still gates occluders.
    // In practice the flicker persisted. The z-fight isn't caused by depth-write contention on
    // the TEST RT — likely something earlier in the pipeline (WRITE pass's polygon-offset
    // interaction with the slot-stamp REPLACE behaviour, or stencil-stamp coverage gaps at
    // coplanar piece boundaries). Do not re-attempt the COLOR_WRITE mask without new evidence.
    /** Slot-based TEST RT. */
    public static RenderType forOutlineStencilTest(int v, ResourceLocation texture) {
        SLOT_TEST_TEX[v] = texture;
        if (SLOT_TEST[v] == null) {
            final int slot = v;
            RenderType rt = RenderType.create(
                    MOD_ID + ":glint_outline_test_v" + v,
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_OUTLINE_SHADER)
                            .setTextureState(new TextureStateShard(
                                    new ResourceLocation(MOD_ID, "stencil_slot_t_" + v), false, false) {
                                @Override public void setupRenderState() {
                                    RenderSystem.setShaderTexture(0, SLOT_TEST_TEX[slot]);
                                }
                            })
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setOutputState(FORCE_MAIN_TARGET)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setLayeringState(stencilTestLayeringSlot(v, false))
                            .createCompositeState(false));
            SLOT_TEST[v] = rt;
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
        }
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(SLOT_TEST[v]))
            live.put(SLOT_TEST[v], new BufferBuilder(SLOT_TEST[v].bufferSize()));
        // LINES bucket → drains AFTER all GENERAL_TRANSPARENT writes under FullyBuffered.
        tagAsLateRenderForShaders(SLOT_TEST[v]);
        return SLOT_TEST[v];
    }

    /** Slot-based TEST RT with front-face culling (elytra cape). */
    public static RenderType forOutlineStencilTestCulled(int v, ResourceLocation texture) {
        SLOT_TEST_CULLED_TEX[v] = texture;
        if (SLOT_TEST_CULLED[v] == null) {
            final int slot = v;
            RenderType rt = RenderType.create(
                    MOD_ID + ":glint_outline_test_culled_v" + v,
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_OUTLINE_SHADER)
                            .setTextureState(new TextureStateShard(
                                    new ResourceLocation(MOD_ID, "stencil_slot_tc_" + v), false, false) {
                                @Override public void setupRenderState() {
                                    RenderSystem.setShaderTexture(0, SLOT_TEST_CULLED_TEX[slot]);
                                }
                            })
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setOutputState(FORCE_MAIN_TARGET)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setLayeringState(stencilTestLayeringSlot(v, true))
                            .createCompositeState(false));
            SLOT_TEST_CULLED[v] = rt;
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
        }
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(SLOT_TEST_CULLED[v]))
            live.put(SLOT_TEST_CULLED[v], new BufferBuilder(SLOT_TEST_CULLED[v].bufferSize()));
        tagAsLateRenderForShaders(SLOT_TEST_CULLED[v]);
        return SLOT_TEST_CULLED[v];
    }


    // Per-RT flush that survives Iris's post-shader-toggle buffer-source wrapper. Vanilla
    // BufferSource and FullyBufferedMultiBufferSource both extend MultiBufferSource$BufferSource,
    // so the direct cast works for the working states. AFTER an Iris pack toggle (activate then
    // deactivate), EntityRenderDispatcher receives a synthetic lambda wrapper that does NOT
    // extend BufferSource — `if (buffer instanceof BufferSource bs) bs.endBatch(rt)` silently
    // skipped at every per-slot flush, leaving WRITE/TEST geometry queued without per-slot
    // ordering → stencil collisions → "lens through walls" outlines. Fall back to the global
    // bufferSource (the canonical singleton the wrapper delegates to under the hood) so the
    // underlying BufferBuilders the geometry was written to actually drain.
    private static void flushRT(MultiBufferSource buffer, RenderType rt) {
        if (buffer instanceof MultiBufferSource.BufferSource bs) { bs.endBatch(rt); return; }
        try {
            MultiBufferSource.BufferSource g = Minecraft.getInstance().renderBuffers().bufferSource();
            if (g != null) g.endBatch(rt);
        } catch (Throwable ignored) {}
    }
    private static void flushAll(MultiBufferSource buffer) {
        if (buffer instanceof MultiBufferSource.BufferSource bs) { bs.endBatch(); return; }
        try {
            MultiBufferSource.BufferSource g = Minecraft.getInstance().renderBuffers().bufferSource();
            if (g != null) g.endBatch();
        } catch (Throwable ignored) {}
    }

    // Disables both color and depth writes — for the stencil silhouette pass.
    private static final RenderStateShard.WriteMaskStateShard NO_WRITE =
            new RenderStateShard.WriteMaskStateShard(false, false);
    // Color + depth writes on — for the shader-pack forward outline pass.
    // Depth write is required so the ring pixels write D+ε at their screen positions. Without it,
    // cloud geometry (gbuffers_clouds runs after gbuffers_entities) finds background depth at the
    // ring's air pixels, passes LEQUAL, and paints over the outline — making it appear behind clouds.
    // Depth write at D+ε means anything rendered later at those pixels with depth > D+ε (i.e., more
    // distant) fails LEQUAL and the ring is preserved. Closer geometry (D' ≤ D+ε) still passes and
    // renders in front of the ring, so intra-entity ordering is unaffected.
    // forShellOutlineTextured (elytra, EQUAL depth test) retains COLOR_ONLY_WRITE — EQUAL means
    // clouds can't overwrite it anyway since D_cloud ≠ D_cape.
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
    // sprite's opaque pixels and passes only at the silhouette edges. Sufficient in vanilla
    // and FPM-no-shaders; the shader-active+FPM case bypasses polygonOffset and is handled
    // separately by geometric eye-space Z translates in doItemOutline.
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

    // Same intent as PUSH_BACK_LAYERING but uses glDepthRange instead of glPolygonOffset.
    // Iris/Oculus drop glPolygonOffset in the HAND phase (vanilla 1P held items via
    // HandRenderer.renderSolid) — confirmed by repeated empirical attempts (see doItemOutline
    // TRIED comments). glDepthRange is NOT intercepted by Iris (confirmed by source inspection
    // of oculus-extract), so the bias works uniformly across ENTITIES (ground/3P), BLOCK_ENTITIES
    // (item frames), and HAND (vanilla 1P) phases. This unifies the depth-push mechanism so the
    // same model-space 4-translate code path used successfully for ground/fixed items also works
    // for in-hand items without per-context dz tuning. eps = 0.0001 in clip space → ~7e-5 at
    // item depth, well above f32 depth precision noise, small enough not to detach the halo.
    private static final RenderStateShard.LayeringStateShard PUSH_BACK_DEPTH_RANGE_LAYERING =
            new RenderStateShard.LayeringStateShard("custom_glint_push_back_depth_range",
                () -> GL11.glDepthRange(0.005, 1.0),
                () -> GL11.glDepthRange(0.0, 1.0));

    /** Strict LESS depth test (vanilla only ships LEQUAL/EQUAL/GREATER/NO). At a pixel where
     *  outline depth equals what's already in the buffer, LEQUAL passes (outline overwrites
     *  the item) but LESS does not. Used by the FPM+shaders dilation variant below to
     *  reduce wrap-over from depth ties when shader packs drop glPolygonOffset. */
    private static final RenderStateShard.DepthTestStateShard LESS_DEPTH_TEST =
            new RenderStateShard.DepthTestStateShard("<", GL11.GL_LESS);

    // Front-face cull + glDepthRange bias for the FPM/1P held-item outline under shader packs.
    // glPolygonOffset(1,10) is unreliable for held items: the slope factor collapses to near-zero
    // for nearly screen-parallel faces at 0.5–0.7m FPM camera distance, providing insufficient
    // push to separate the dilated back face from the item front face on non-convex models
    // (crossbow bow arms, long spears) at steep camera tilts. Oculus/Iris does not intercept
    // glDepthRange (confirmed by source inspection — no matches in the extracted jar), so it
    // works reliably regardless of shader pack. glDepthRange(0.001, 1.0) shifts every depth
    // written by the dilated shell by eps*(1 − geom_depth): at item depth ≈ 0.93 that is ~7e-5,
    // well above f32 depth precision noise and camera-angle-independent. The ring edge at typical
    // distances (0.1 m+ behind item) stays within LESS range; only geometry within ~1 cm behind
    // the item's silhouette can occlude the ring, which is acceptable and correct behaviour.
    private static final RenderStateShard.LayeringStateShard CULL_FRONT_DEPTH_RANGE_BACK_LAYERING =
            new RenderStateShard.LayeringStateShard("custom_glint_cull_front_depth_range_back",
                () -> {
                    GL11.glEnable(GL11.GL_CULL_FACE);
                    GL11.glCullFace(GL11.GL_FRONT);
                    GL11.glDepthRange(0.001, 1.0);
                },
                () -> {
                    GL11.glCullFace(GL11.GL_BACK);
                    GL11.glDepthRange(0.0, 1.0);
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

    /**
     * Normal-push outline for FPM/1P items under shader packs. Replaces the centroid-scale
     * dilation approach ({@link #forShaderItemOutlineFpm}) for the {@code useStrictDilation} branch.
     *
     * The centroid-scale approach fails on non-convex models (crossbow bow arms, bow limbs): parts
     * closer to the camera than the AABB centroid have their back faces pushed *toward* the camera
     * by the scale, and the world-Z push (dilationDz) collapses at steep camera tilts. The result
     * is z-fighting inside the item silhouette that flickers with the idle animation.
     *
     * Normal-push sends each vertex {@code push} blocks along its own world-space normal. Back faces
     * (normals pointing away from camera) always move further from camera regardless of camera angle
     * or model geometry — geometrically correct for non-convex models. LEQUAL depth test (not LESS):
     * side faces (normals perpendicular to camera) are pushed sideways, preserve their depth, and
     * pass LEQUAL → ring appears at the silhouette edge. No polygon offset needed; the geometry
     * separation is built in. Paired with {@link NormalPushConsumer}.
     */
    private static RenderType SHADER_OUTLINE_ITEM_NP_TYPE;
    public static RenderType forShaderItemOutlineNormalPush() {
        if (SHADER_OUTLINE_ITEM_NP_TYPE == null) {
            SHADER_OUTLINE_ITEM_NP_TYPE = RenderType.create(
                    MOD_ID + ":shader_outline_item_np",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(POSITION_COLOR_SHADER)
                            .setCullState(CULL)
                            // LESS not LEQUAL: silhouette faces pushed tangentially land at exactly
                            // item depth — LEQUAL would let them draw over the item interior at
                            // certain camera angles. The ring is still visible because pushed
                            // silhouette faces projecting *outside* the item hit background depth,
                            // which is strictly greater than the outline depth, so LESS passes there.
                            .setDepthTestState(LESS_DEPTH_TEST)
                            .setTransparencyState(LIGHTNING_TRANSPARENCY)
                            .setLayeringState(CULL_FRONT_LAYERING)
                            .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(SHADER_OUTLINE_ITEM_NP_TYPE, new BufferBuilder(SHADER_OUTLINE_ITEM_NP_TYPE.bufferSize()));
        }
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(SHADER_OUTLINE_ITEM_NP_TYPE))
            live.put(SHADER_OUTLINE_ITEM_NP_TYPE, new BufferBuilder(SHADER_OUTLINE_ITEM_NP_TYPE.bufferSize()));
        tagAsLateRenderForShaders(SHADER_OUTLINE_ITEM_NP_TYPE);
        return SHADER_OUTLINE_ITEM_NP_TYPE;
    }

    /**
     * Textured normal-push outline RT for flat 2D sprites under shader packs. Companion to
     * {@link #forShaderItemOutlineNormalPush} (POSITION_COLOR, used for 3D models) — same
     * geometric separation strategy, but with texture + alpha discard so the silhouette is
     * preserved instead of filling the voxel bounding mesh.
     *
     * Strategy: vanilla voxelizes flat sprites into 16 layered cubes with alpha-discard. Each
     * voxel cube has 6 face normals. CULL_FRONT drops front-faces; back-face normal-push moves
     * them further from the camera (LESS depth-test rejects them inside the silhouette where
     * item depth wins). Side-face normals (perpendicular to camera at silhouette edges) push
     * sideways in screen-space — that's where the ring becomes visible.
     *
     * Replaces the 4-translate eye-space push for flat sprites under FPM/shaders: the old
     * approach needed a global dz that worked across all sprite tilts (impossible — wraps at
     * one angle, drifts at another). Normal-push handles per-vertex displacement so depth
     * separation is geometrically guaranteed at every camera angle.
     */
    private static final Map<ResourceLocation, RenderType> BY_SHADER_SPRITE_NP = new HashMap<>();
    public static RenderType forShaderSpriteOutlineNormalPush(ResourceLocation tex) {
        return BY_SHADER_SPRITE_NP.computeIfAbsent(tex, t -> {
            RenderType rt = RenderType.create(
                    MOD_ID + ":shader_sprite_outline_np_" + t.getNamespace() + "_" + t.getPath().replace('/', '_'),
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER)
                            .setTextureState(new TextureStateShard(t, false, false))
                            .setCullState(CULL)
                            .setDepthTestState(LESS_DEPTH_TEST)
                            .setTransparencyState(LIGHTNING_TRANSPARENCY)
                            .setLayeringState(CULL_FRONT_LAYERING)
                            .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
            if (live != null && !live.containsKey(rt)) live.put(rt, new BufferBuilder(rt.bufferSize()));
            tagAsLateRenderForShaders(rt);
            return rt;
        });
    }

    /** Superseded by {@link #forShaderItemOutlineNormalPush} for the item dilation path. Kept for
     *  reference — the glDepthRange(0.001) bias was camera-angle-independent but too small (~7e-5
     *  clip units at FPM depth) to cover the centroid-scale displacement on non-convex models. */
    private static RenderType SHADER_OUTLINE_ITEM_FPM_TYPE;
    public static RenderType forShaderItemOutlineFpm() {
        if (SHADER_OUTLINE_ITEM_FPM_TYPE == null) {
            SHADER_OUTLINE_ITEM_FPM_TYPE = RenderType.create(
                    MOD_ID + ":shader_outline_item_fpm",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(POSITION_COLOR_SHADER)
                            .setCullState(CULL)
                            .setDepthTestState(LESS_DEPTH_TEST)
                            .setTransparencyState(LIGHTNING_TRANSPARENCY)
                            .setLayeringState(CULL_FRONT_DEPTH_RANGE_BACK_LAYERING)
                            .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(SHADER_OUTLINE_ITEM_FPM_TYPE, new BufferBuilder(SHADER_OUTLINE_ITEM_FPM_TYPE.bufferSize()));
        }
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(SHADER_OUTLINE_ITEM_FPM_TYPE))
            live.put(SHADER_OUTLINE_ITEM_FPM_TYPE, new BufferBuilder(SHADER_OUTLINE_ITEM_FPM_TYPE.bufferSize()));
        tagAsLateRenderForShaders(SHADER_OUTLINE_ITEM_FPM_TYPE);
        return SHADER_OUTLINE_ITEM_FPM_TYPE;
    }

    /**
     * Textured variant of {@link #forShaderArmorOutline} for the shader-pack path.
     * Uses NEW_ENTITY + RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER so the armor texture's alpha
     * channel discards transparent texels. Without this, the dilated shell fills the entire
     * bone hull (rectangles for head/chest/arms including transparent regions) instead of
     * tracing only the visible opaque surface — the "whole texture space" artifact.
     * ENTITY_CUTOUT maps to gbuffers_entities under shader mods (universal across packs).
     * Caller must use FullColorOverrideConsumer (not PositionColorOnlyConsumer) so UV is
     * forwarded to the buffer for alpha-discard; color is still overridden to outline color.
     */
    public static RenderType forShaderArmorOutlineTextured(ResourceLocation texture) {
        return BY_SHADER_ARMOR_OUTLINE_TEX.computeIfAbsent(texture, tex -> {
            RenderType rt = RenderType.create(
                    MOD_ID + ":shader_outline_armor_tex_" + tex.getNamespace() + "_" + tex.getPath().replace('/', '_'),
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER)
                            .setTextureState(new TextureStateShard(tex, false, false))
                            .setCullState(CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setTransparencyState(LIGHTNING_TRANSPARENCY)
                            .setLayeringState(CULL_FRONT_PUSH_BACK_LAYERING)
                            .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
            if (live != null && !live.containsKey(rt)) live.put(rt, new BufferBuilder(rt.bufferSize()));
            tagAsLateRenderForShaders(rt);
            return rt;
        });
    }

    /**
     * Held-sprite outline RT that uses {@code RENDERTYPE_OUTLINE_SHADER} — the vanilla outline
     * shader samples the texture's ALPHA only (for the cutout discard) and outputs the vertex
     * color as the fragment color. The {@link #forShaderArmorOutlineTextured} variant uses
     * {@code RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER} which multiplies vertex color by texture
     * RGB — that tints the ring with the underlying sprite's color (purple outline on a red
     * apple → red-tinted purple). This variant returns the chosen outline color unmodified.
     * Caller must use {@link ColorOverrideConsumer} (POSITION_COLOR_TEX format — overlay/uv2/
     * normal are swallowed).
     */
    private static final Map<ResourceLocation, RenderType> BY_SHADER_HELD_SPRITE_OUTLINE = new ConcurrentHashMap<>();
    public static RenderType forShaderHeldSpriteOutline(ResourceLocation texture) {
        return BY_SHADER_HELD_SPRITE_OUTLINE.computeIfAbsent(texture, tex -> {
            RenderType rt = RenderType.create(
                    MOD_ID + ":shader_held_sprite_outline_" + tex.getNamespace() + "_" + tex.getPath().replace('/', '_'),
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_OUTLINE_SHADER)
                            .setTextureState(new TextureStateShard(tex, false, false))
                            .setCullState(CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setTransparencyState(LIGHTNING_TRANSPARENCY)
                            .setOutputState(FORCE_MAIN_TARGET)
                            .setLayeringState(CULL_FRONT_PUSH_BACK_LAYERING)
                            .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
            if (live != null && !live.containsKey(rt)) live.put(rt, new BufferBuilder(rt.bufferSize()));
            tagAsLateRenderForShaders(rt);
            return rt;
        });
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
                            // glDepthRange-based push instead of glPolygonOffset so the depth bias
                            // is honored in the HAND phase too (vanilla 1P held items via
                            // HandRenderer). Lets the in-hand outline use the same model-space
                            // 4-translate code path as ground/fixed items without manual dz.
                            .setLayeringState(PUSH_BACK_DEPTH_RANGE_LAYERING)
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
     * GUI flat-item outline RT. Uses {@code RENDERTYPE_OUTLINE_SHADER} which outputs
     * {@code vertexColor.rgb} with {@code texture.alpha} (discards on {@code alpha == 0}) — the
     * items atlas is sampled ONLY as an alpha mask, its RGB is ignored. Paired with the existing
     * {@link ColorOverrideConsumer} (drops overlay/uv2/normal so the POSITION_COLOR_TEX-format
     * BufferBuilder isn't asked to write fields it doesn't have), this produces a pure
     * glow-color silhouette of the item sprite — no texture tinting. Same proven recipe used by
     * {@link #forOutlineStencilTest} for the world-space stencil outline, minus the stencil
     * layering and depth-bias state that don't apply in GUI rendering.
     */
    private static RenderType GUI_ITEM_OUTLINE_TYPE;
    public static RenderType forGuiItemOutline() {
        if (GUI_ITEM_OUTLINE_TYPE == null) {
            GUI_ITEM_OUTLINE_TYPE = RenderType.create(
                    MOD_ID + ":gui_item_outline",
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_OUTLINE_SHADER)
                            .setTextureState(new TextureStateShard(TextureAtlas.LOCATION_BLOCKS, false, false))
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setOutputState(MAIN_TARGET)
                            .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(GUI_ITEM_OUTLINE_TYPE, new BufferBuilder(GUI_ITEM_OUTLINE_TYPE.bufferSize()));
        }
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(GUI_ITEM_OUTLINE_TYPE))
            live.put(GUI_ITEM_OUTLINE_TYPE, new BufferBuilder(GUI_ITEM_OUTLINE_TYPE.bufferSize()));
        return GUI_ITEM_OUTLINE_TYPE;
    }

    /**
     * GUI outline RT for 3D BEWLR items (shields, backpacks, troll weapons, …). Unlike
     * {@link #forGuiItemOutline} — which masks against the BLOCKS atlas alpha and only fits flat
     * sprite icons — this binds white.png so {@code RENDERTYPE_OUTLINE_SHADER} sees {@code alpha == 1}
     * across the whole 3D model and emits a SOLID glow-color silhouette. (A BEWLR's geometry samples
     * its own model textures, so the blocks-atlas alpha mask reads garbage and paints the whole model
     * — the "glow covers the item" bug.) COLOR_WRITE (no depth write) so the enlarged silhouette never
     * occludes the real item drawn on top; the halo is produced purely by draw order + the
     * screen-space scale-from-center in {@link #doGuiItemOutline}.
     */
    private static RenderType GUI_BEWLR_OUTLINE_TYPE;
    public static RenderType forGuiBewlrOutline() {
        if (GUI_BEWLR_OUTLINE_TYPE == null) {
            GUI_BEWLR_OUTLINE_TYPE = RenderType.create(
                    MOD_ID + ":gui_bewlr_outline",
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_OUTLINE_SHADER)
                            .setTextureState(new TextureStateShard(
                                    new ResourceLocation("textures/misc/white.png"), false, false))
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(COLOR_WRITE)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setOutputState(MAIN_TARGET)
                            .createCompositeState(false));
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(GUI_BEWLR_OUTLINE_TYPE, new BufferBuilder(GUI_BEWLR_OUTLINE_TYPE.bufferSize()));
        }
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(GUI_BEWLR_OUTLINE_TYPE))
            live.put(GUI_BEWLR_OUTLINE_TYPE, new BufferBuilder(GUI_BEWLR_OUTLINE_TYPE.bufferSize()));
        return GUI_BEWLR_OUTLINE_TYPE;
    }

    /**
     * Per-texture variant of {@link #forGuiBewlrOutline} for BEWLRs whose visual is a single shared
     * model painted by per-variant alpha-discarded textures (Ice &amp; Fire troll weapons, death worm
     * gauntlet). Binds the given texture instead of white.png so {@code RENDERTYPE_OUTLINE_SHADER}
     * alpha-discards against its texels — the GUI halo then traces just that variant's silhouette
     * instead of the full shared model. Cached per texture; same COLOR_WRITE / LEQUAL state.
     */
    private static final Map<ResourceLocation, RenderType> GUI_BEWLR_OUTLINE_TEXTURED = new ConcurrentHashMap<>();
    public static RenderType forGuiBewlrOutlineTextured(ResourceLocation texture) {
        RenderType cached = GUI_BEWLR_OUTLINE_TEXTURED.computeIfAbsent(texture, tex -> {
            RenderType rt = RenderType.create(
                    MOD_ID + ":gui_bewlr_outline_tex",
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_OUTLINE_SHADER)
                            .setTextureState(new TextureStateShard(tex, false, false))
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(COLOR_WRITE)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setOutputState(MAIN_TARGET)
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
     * VertexConsumer wrapper for the shader-pack forward outline path. Underlying buffer is
     * POSITION_COLOR only; entity models call vertex().color().uv().overlayCoords().uv2().normal().endVertex().
     * We forward position + color + endVertex, override the color with a fixed RGBA (outline color),
     * and silently swallow uv/overlay/uv2/normal so the buffer's format constraints aren't violated.
     */
    /** VertexConsumer that swallows every call. Used to drive geometry through AABBTrackingConsumer
     *  without actually rendering anything (shader-pack outline first pass: just capture bounds). */
    public static final class NullConsumer implements VertexConsumer {
        public NullConsumer() {}
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

    /**
     * VertexConsumer that pushes each vertex {@code push} blocks along its world-space normal before
     * emitting POSITION_COLOR output. Used by {@link #forShaderItemOutlineNormalPush} for the
     * FPM/1P shader-pack item outline. Unlike the centroid-scale approach, each face's vertices
     * move along their own normals — back faces (normals away from camera) always increase in depth
     * regardless of camera angle or model shape, eliminating z-fighting on non-convex items.
     *
     * Expects vertices in world/render space (after pose matrix applied) and world-space normals
     * (after normal matrix applied) — exactly what item rendering provides via
     * {@code vertex(Matrix4f pose, x,y,z)} and {@code normal(Matrix3f normalMat, nx,ny,nz)}.
     */
    private static final class NormalPushConsumer implements VertexConsumer {
        private final VertexConsumer wrapped;
        private final int r, g, b, a;
        private final float push;
        private float vx, vy, vz;
        private float nx = 0, ny = 0, nz = 1;

        NormalPushConsumer(VertexConsumer wrapped, int r, int g, int b, int a, float push) {
            this.wrapped = wrapped; this.r = r; this.g = g; this.b = b; this.a = a; this.push = push;
        }

        @Override public VertexConsumer vertex(double x, double y, double z) {
            vx = (float)x; vy = (float)y; vz = (float)z; return this;
        }
        @Override public VertexConsumer vertex(Matrix4f m, float x, float y, float z) {
            Vector4f v = m.transform(new Vector4f(x, y, z, 1.0f));
            vx = v.x; vy = v.y; vz = v.z; return this;
        }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer color(float r, float g, float b, float a) { return this; }
        @Override public VertexConsumer uv(float u, float v) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) {
            nx = x; ny = y; nz = z; return this;
        }
        @Override public void endVertex() {
            float len = (float) Math.sqrt(nx*nx + ny*ny + nz*nz);
            float inv = len > 1e-4f ? push / len : 0f;
            wrapped.vertex(vx + nx*inv, vy + ny*inv, vz + nz*inv);
            wrapped.color(r, g, b, a);
            wrapped.endVertex();
            nx = ny = nz = 0f;
        }
        @Override public void defaultColor(int r, int g, int b, int a) {}
        @Override public void unsetDefaultColor() {}
    }

    /**
     * Textured companion to {@link NormalPushConsumer}. NEW_ENTITY vertex format requires
     * position, color, uv, overlay, uv2, normal — all forwarded except color (overridden).
     * UV forwarding is mandatory so the texture's alpha-discard preserves the sprite silhouette;
     * without it, the voxel mesh fills its entire bounding rectangle.
     */
    private static class NormalPushTexturedConsumer implements VertexConsumer {
        private final VertexConsumer wrapped;
        private final int r, g, b, a;
        private final float push;
        private float vx, vy, vz;
        private float nx = 0, ny = 0, nz = 1;
        private float u, v;
        private int ox, oy, lu, lv;

        NormalPushTexturedConsumer(VertexConsumer wrapped, int r, int g, int b, int a, float push) {
            this.wrapped = wrapped; this.r = r; this.g = g; this.b = b; this.a = a; this.push = push;
        }
        @Override public VertexConsumer vertex(double x, double y, double z) {
            vx = (float)x; vy = (float)y; vz = (float)z; return this;
        }
        @Override public VertexConsumer vertex(Matrix4f m, float x, float y, float z) {
            Vector4f t = m.transform(new Vector4f(x, y, z, 1.0f));
            vx = t.x; vy = t.y; vz = t.z; return this;
        }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer color(float r, float g, float b, float a) { return this; }
        @Override public VertexConsumer uv(float uu, float vv) { this.u = uu; this.v = vv; return this; }
        @Override public VertexConsumer overlayCoords(int x, int y) { this.ox = x; this.oy = y; return this; }
        @Override public VertexConsumer uv2(int x, int y) { this.lu = x; this.lv = y; return this; }
        @Override public VertexConsumer normal(float x, float y, float z) {
            nx = x; ny = y; nz = z; return this;
        }
        @Override public void endVertex() {
            float len = (float) Math.sqrt(nx*nx + ny*ny + nz*nz);
            float inv = len > 1e-4f ? push / len : 0f;
            wrapped.vertex(vx + nx*inv, vy + ny*inv, vz + nz*inv);
            wrapped.color(r, g, b, a);
            wrapped.uv(u, v);
            wrapped.overlayCoords(ox, oy);
            wrapped.uv2(lu, lv);
            wrapped.normal(nx, ny, nz);
            wrapped.endVertex();
            nx = ny = 0f; nz = 1f;
        }
        @Override public void defaultColor(int r, int g, int b, int a) {}
        @Override public void unsetDefaultColor() {}
    }

    /**
     * Textured frontface filter. Reads each vertex's eye-space normal; if |nz| (the
     * camera-axis component) is below the threshold the vertex belongs to a side-face
     * quad of the voxelization extrusion and gets collapsed to the AABB centroid so its
     * quad degenerates to a point and emits zero fragments. Front-face vertices pass
     * through unchanged.
     *
     * Used together with a pose-stack scale-around-centroid dilation applied to the caller's
     * poseStack BEFORE renderStatic: the surviving front-face quads draw at 1.10× their
     * original eye-space extent, producing a clean outline ring around the projected sprite
     * silhouette. Side faces — which on a tilted FPM held item would otherwise visually
     * dominate as a "standing up" outline of the slab's thin extrusion edge — contribute
     * nothing.
     */
    /**
     * Keeps only the EXTRUDED side faces of a voxelized 2D sprite; drops the front/back 2D face
     * quads. Decision is based on the quad's MODEL-SPACE Z extent (which is pose-invariant)
     * rather than the surface normal (which is eye-space after {@code pose.normal()} is applied
     * by {@code VertexConsumer.putBulkData}, so unusable for this purpose — 1P transforms like
     * the sword's {@code -90° Y, +25° Z} rotate model Z entirely out of eye Z).
     *
     * Why: vanilla voxelizes flat sprite items (swords, tools, apples, ingots) by extruding the
     * sprite along the model Z axis — front face at {@code mz≈0}, back face at {@code mz≈1/16},
     * side faces span both. Under the model-space 4-translate outline path, the front/back face
     * quads are full sprite-shaped, and a 1-pixel additive translate of them overlays the item's
     * interior with outline color (the "wrapped" look). The side faces form a thin perimeter
     * band around the silhouette; translating only those by 1 pixel produces a clean ring.
     *
     * Mechanism: buffer all 4 vertices of a quad, check {@code maxMz − minMz}. If small (< eps),
     * the quad is a 2D face → collapse all 4 vertices to the origin → degenerate quad → zero
     * fragments. If large, it's a side face → forward the 4 vertices unchanged.
     */
    private static class SideFaceOnlyConsumer implements VertexConsumer {
        private final VertexConsumer wrapped;
        private final int r, g, b, a;
        private final float zExtentThreshold;
        // true → keep side-face (extruded) quads, drop 2D face quads.
        // false → keep 2D face quads, drop side-face quads.
        private final boolean keepSides;

        // Buffered per-vertex data for the in-flight quad.
        private final float[] mx = new float[4], my = new float[4], mz = new float[4];
        private final float[] tx = new float[4], ty = new float[4], tz = new float[4];
        private final float[] u = new float[4], v = new float[4];
        private final int[] ox = new int[4], oy = new int[4], lu = new int[4], lv = new int[4];
        private final float[] nx = new float[4], ny = new float[4], nz = new float[4];
        private int idx = 0;

        // In-flight vertex (between vertex() and endVertex()).
        private float cMx, cMy, cMz, cTx, cTy, cTz;
        private float cU, cV;
        private int cOx, cOy, cLu, cLv;
        private float cNx = 0, cNy = 0, cNz = 1;

        SideFaceOnlyConsumer(VertexConsumer wrapped, int r, int g, int b, int a, float zExtentThreshold, boolean keepSides) {
            this.wrapped = wrapped; this.r = r; this.g = g; this.b = b; this.a = a;
            this.zExtentThreshold = zExtentThreshold;
            this.keepSides = keepSides;
        }
        @Override public VertexConsumer vertex(double x, double y, double z) {
            cMx = (float)x; cMy = (float)y; cMz = (float)z;
            cTx = cMx; cTy = cMy; cTz = cMz;
            return this;
        }
        @Override public VertexConsumer vertex(Matrix4f m, float x, float y, float z) {
            cMx = x; cMy = y; cMz = z;
            Vector4f t = m.transform(new Vector4f(x, y, z, 1.0f));
            cTx = t.x; cTy = t.y; cTz = t.z;
            return this;
        }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer color(float r, float g, float b, float a) { return this; }
        @Override public VertexConsumer uv(float uu, float vv) { this.cU = uu; this.cV = vv; return this; }
        @Override public VertexConsumer overlayCoords(int x, int y) { this.cOx = x; this.cOy = y; return this; }
        @Override public VertexConsumer uv2(int x, int y) { this.cLu = x; this.cLv = y; return this; }
        @Override public VertexConsumer normal(float x, float y, float z) {
            cNx = x; cNy = y; cNz = z; return this;
        }
        @Override public void endVertex() {
            mx[idx] = cMx; my[idx] = cMy; mz[idx] = cMz;
            tx[idx] = cTx; ty[idx] = cTy; tz[idx] = cTz;
            u[idx] = cU; v[idx] = cV;
            ox[idx] = cOx; oy[idx] = cOy; lu[idx] = cLu; lv[idx] = cLv;
            nx[idx] = cNx; ny[idx] = cNy; nz[idx] = cNz;
            idx++;
            // reset in-flight normal for next vertex
            cNx = cNy = 0; cNz = 1;

            if (idx == 4) {
                float minMz = Math.min(Math.min(mz[0], mz[1]), Math.min(mz[2], mz[3]));
                float maxMz = Math.max(Math.max(mz[0], mz[1]), Math.max(mz[2], mz[3]));
                boolean isSideFace = (maxMz - minMz) > zExtentThreshold;
                boolean keep = keepSides ? isSideFace : !isSideFace;
                for (int i = 0; i < 4; i++) {
                    if (keep) {
                        wrapped.vertex(tx[i], ty[i], tz[i]);
                    } else {
                        // Collapse to a single point → degenerate quad → zero fragments.
                        wrapped.vertex(0f, 0f, 0f);
                    }
                    wrapped.color(r, g, b, a);
                    wrapped.uv(u[i], v[i]);
                    wrapped.overlayCoords(ox[i], oy[i]);
                    wrapped.uv2(lu[i], lv[i]);
                    wrapped.normal(nx[i], ny[i], nz[i]);
                    wrapped.endVertex();
                }
                idx = 0;
            }
        }
        @Override public void defaultColor(int r, int g, int b, int a) {}
        @Override public void unsetDefaultColor() {}
    }

    private static class FrontFaceFilterConsumer implements VertexConsumer {
        private final VertexConsumer wrapped;
        private final int r, g, b, a;
        private final float cx, cy, cz;
        private final float normalThreshold;
        private float vx, vy, vz;
        private float nx = 0, ny = 0, nz = 1;
        private float u, v;
        private int ox, oy, lu, lv;

        FrontFaceFilterConsumer(VertexConsumer wrapped, int r, int g, int b, int a,
                                float cx, float cy, float cz, float normalThreshold) {
            this.wrapped = wrapped; this.r = r; this.g = g; this.b = b; this.a = a;
            this.cx = cx; this.cy = cy; this.cz = cz; this.normalThreshold = normalThreshold;
        }
        @Override public VertexConsumer vertex(double x, double y, double z) {
            vx = (float)x; vy = (float)y; vz = (float)z; return this;
        }
        @Override public VertexConsumer vertex(Matrix4f m, float x, float y, float z) {
            Vector4f t = m.transform(new Vector4f(x, y, z, 1.0f));
            vx = t.x; vy = t.y; vz = t.z; return this;
        }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer color(float r, float g, float b, float a) { return this; }
        @Override public VertexConsumer uv(float uu, float vv) { this.u = uu; this.v = vv; return this; }
        @Override public VertexConsumer overlayCoords(int x, int y) { this.ox = x; this.oy = y; return this; }
        @Override public VertexConsumer uv2(int x, int y) { this.lu = x; this.lv = y; return this; }
        @Override public VertexConsumer normal(float x, float y, float z) {
            nx = x; ny = y; nz = z; return this;
        }
        @Override public void endVertex() {
            if (Math.abs(nz) >= normalThreshold) {
                wrapped.vertex(vx, vy, vz);
            } else {
                // All 4 vertices of a side-face quad share the same normal so all 4
                // collapse to (cx,cy,cz) → degenerate quad, zero fragments.
                wrapped.vertex(cx, cy, cz);
            }
            wrapped.color(r, g, b, a);
            wrapped.uv(u, v);
            wrapped.overlayCoords(ox, oy);
            wrapped.uv2(lu, lv);
            wrapped.normal(nx, ny, nz);
            wrapped.endVertex();
            nx = ny = 0f; nz = 1f;
        }
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
        // Compat-installed substitute texture used by WRITE/TEST and forward-pass outline RTs.
        // Default identity; EK compat remaps WingedHussar chest texture to a synthesized variant
        // where the wings' two asymmetric-UV faces both sample the feather alpha.
        texture = armorOutlineTextureRemap.apply(texture);
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
            // Textured RT: ENTITY_CUTOUT_NO_CULL_SHADER + armor texture → alpha-discard on
            // transparent texels. Fixes the "whole texture space" artifact where the untextured
            // POSITION_COLOR path drew the full bone hull (including transparent holes in chainmail,
            // boots, etc.) as a solid colored ring instead of tracing only opaque pixel coverage.
            // NOT wrapped with asShaderOutline: IsOutlineRenderStateShard routes to a separate
            // outline framebuffer that is composited before clouds, so depth-writing there has no
            // effect on the cloud composite's scene-depth read. Using the plain RT keeps the outline
            // in the main scene depth buffer, which cloud composites DO sample to mask geometry.
            // Bind the alpha-mask parallel of this armor texture (RGB=255, alpha preserved) so
            // the pack's gbuffers_entities does `vertexColor × white = vertexColor` and the ring
            // renders in the chosen outline color rather than picking up the armor's albedo.
            // Same fix as held sprites — see getBlocksAlphaMask / getArmorAlphaMask.
            RenderType outlineRT = forShaderArmorOutlineTextured(getArmorAlphaMask(texture));
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
            // FullColorOverrideConsumer (not PositionColorOnlyConsumer): forwards UV so the
            // entity cutout shader can sample the texture for alpha-discard.
            VertexConsumer outlineBuf = new FullColorOverrideConsumer(
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
        // Per-outline stencil-value isolation: each call gets a unique slot V (1..255).
        // WRITE stamps V; TEST tests stencil != V. Two outlined objects no longer share a
        // single stencil bit, so a sword overlapping armor no longer wipes/blocks the
        // armor's outline (and vice versa).
        int stencilSlot = nextStencilSlot();
        // When OUTLINE_FULL_SILHOUETTE is set (IaF dragon: full-body barding over transparent wings),
        // stamp the FULL geometry silhouette via white.png so the dilated back-side ring is suppressed
        // across the transparent gaps — without it the back armor outline leaks through the wing
        // membranes. The WRITE pass writes only stencil (no depth/color), so nothing occludes the
        // world. Default (false) stamps the armor texture so the ring hugs the armor coverage.
        ResourceLocation writeTex = OUTLINE_FULL_SILHOUETTE.get()
                ? new ResourceLocation("textures/misc/white.png")
                : texture;
        RenderType writeType = forOutlineStencilWrite(stencilSlot, writeTex);
        RenderType testType  = forOutlineStencilTest(stencilSlot, texture);
        Minecraft.getInstance().getMainRenderTarget().enableStencil();

        // Pre-flush the outer source before our stencil passes. Every other working stencil
        // outline path in this mod does this (doItemOutline → preBs.endBatch; EK's
        // applyDecorationGlint → bs.endLastBatch). Without it, the armor's own vertices —
        // just queued by HumanoidArmorLayer into a FullyBuffered SegmentedBufferBuilder —
        // are mixed into the same deferred flush as our stencil-write and stencil-test, and
        // the eventual batched draw fills the silhouette instead of forming a ring under
        // shader-mod-no-pack in 3P. Items work without this issue because doItemOutline already
        // pre-flushes; armor was the odd one out.
        flushAll(buffer);

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
        flushRT(buffer, writeType);

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
        } else if (slot == null) {
            // Non-humanoid pivot: entity body outlines from EntityGlintRender AND IaF mount armor
            // (dragon, hippogryph, hippocampus) — anything where the model is not a HumanoidModel
            // and the chest-height humanoid pivot below would displace geometry incorrectly.
            // The humanoid pivot (y≈0.9 in entity-local) is correct for a player but sits far from
            // a 5m dragon's centroid; scaling 1.04× around that misplaced pivot shifts back-side
            // geometry several units in Z, enough that the dilated outline mesh for back-side
            // armor passes LEQUAL against the body depth and draws visibly on the front — symptom:
            // looking at one side of the mob shows the outline from the opposite side through it.
            // Scale around the model's own 3D AABB centroid (NullConsumer pre-pass) so dilation
            // stays concentric with whatever silhouette the model actually has. IaF mount armor
            // mixins explicitly pass slot=null to reach this branch.
            float[] minMax = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                               Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
            poseStack.pushPose();
            model.renderToBuffer(poseStack, new AABBTrackingConsumer(new NullConsumer(), minMax),
                    packedLight, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);
            poseStack.popPose();
            if (minMax[3] > minMax[0]) {
                float cx = (minMax[0] + minMax[3]) * 0.5f;
                float cy = (minMax[1] + minMax[4]) * 0.5f;
                float cz = (minMax[2] + minMax[5]) * 0.5f;
                poseStack.last().pose().mulLocal(new Matrix4f()
                        .translate(cx, cy, cz)
                        .scale(outlineScale, outlineScale, outlineScale)
                        .translate(-cx, -cy, -cz));
            } else {
                poseStack.scale(outlineScale, outlineScale, outlineScale);
            }
        } else {
            poseStack.translate(0.0f, -0.95f, 0.0f); // -0.9 - 0.05 downward alignment correction
            poseStack.scale(outlineScale, outlineScale, outlineScale);
            poseStack.translate(0.0f, 0.9f, 0.0f);
        }
        model.renderToBuffer(poseStack, buffer.getBuffer(testType), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, oR, oG, oB, 1.0f);
        flushRT(buffer, testType);
        poseStack.popPose();
    }

    /**
     * Multi-model entity outline: stamp every entry into ONE shared stencil slot, then dilate-
     * test each entry's model individually. Resolves the "stray clothing covers torso but base
     * skeleton outline still draws under it" duplication: each TEST pass only draws ring pixels
     * where stencil != V, and since every body+overlay silhouette stamped V before any TEST
     * fired, the ring only forms outside the union of all surfaces. The per-entry dilation
     * pivot is still computed per model (AABB centroid) so each surface gets a tight 1.04× ring.
     *
     * Entries are processed in order. The shader-pack path falls back to per-entry forward
     * outline (no stencil available) — that path already accepts visible duplication across
     * overlays because the dilation depth ordering hides most of the conflict.
     */
    public static void doMultiModelOutline(PoseStack poseStack, MultiBufferSource buffer,
            int color, List<EntityGlintRender.PendingOutline> entries) {
        if (entries == null || entries.isEmpty()) return;
        if (isInShadowPass()) return;
        if (outlineSuppressor.getAsBoolean()) return;

        // Shader-pack: stencil path is dead, dispatch each entry through doModelOutline which
        // takes its own forward-pass branch. Each entry uses the running ColorModulator color so
        // they all match. Duplicated rings across overlaps are accepted in this branch; the
        // shader-pack outline is already imprecise.
        if (isShaderPackActive()) {
            for (var e : entries) {
                poseStack.pushPose();
                poseStack.last().pose().set(e.pose);
                poseStack.last().normal().set(e.normal);
                doModelOutline(poseStack, buffer, e.light, e.model, e.texture, color, null);
                poseStack.popPose();
            }
            return;
        }

        float oR = ((color >> 16) & 0xFF) / 255.0f;
        float oG = ((color >>  8) & 0xFF) / 255.0f;
        float oB = ( color        & 0xFF) / 255.0f;
        float outlineScale = 1.04f;

        int stencilSlot = nextStencilSlot();
        Minecraft.getInstance().getMainRenderTarget().enableStencil();
        // Force-flush queued body / glint / layer drawcalls before the stencil passes so the
        // stencil-write geometry only contributes stencil bits, not commingled colour vertices.
        flushAll(buffer);

        // PHASE 1: WRITE every entry's silhouette into the shared slot. REPLACE on pass/dpfail
        // means later WRITEs on overlapping pixels still leave stencil = V (idempotent).
        for (var e : entries) {
            RenderType writeType = forOutlineStencilWrite(stencilSlot, e.texture);
            poseStack.pushPose();
            poseStack.last().pose().set(e.pose);
            poseStack.last().normal().set(e.normal);
            e.model.renderToBuffer(poseStack, buffer.getBuffer(writeType), e.light,
                    OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);
            flushRT(buffer, writeType);
            poseStack.popPose();
        }

        // PHASE 2: TEST every entry dilated around its own AABB centroid. stencilFunc NOTEQUAL V
        // on the shared slot — since every body+overlay stamped V in phase 1, the dilated ring
        // is suppressed everywhere any surface exists, leaving the outline only on the union's
        // outer perimeter.
        for (var e : entries) {
            RenderType testType = forOutlineStencilTest(stencilSlot, e.texture);
            poseStack.pushPose();
            poseStack.last().pose().set(e.pose);
            poseStack.last().normal().set(e.normal);

            float[] minMax = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                               Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
            poseStack.pushPose();
            e.model.renderToBuffer(poseStack, new AABBTrackingConsumer(new NullConsumer(), minMax),
                    e.light, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);
            poseStack.popPose();
            if (minMax[3] > minMax[0]) {
                float cx = (minMax[0] + minMax[3]) * 0.5f;
                float cy = (minMax[1] + minMax[4]) * 0.5f;
                float cz = (minMax[2] + minMax[5]) * 0.5f;
                poseStack.last().pose().mulLocal(new Matrix4f()
                        .translate(cx, cy, cz)
                        .scale(outlineScale, outlineScale, outlineScale)
                        .translate(-cx, -cy, -cz));
            } else {
                poseStack.scale(outlineScale, outlineScale, outlineScale);
            }
            e.model.renderToBuffer(poseStack, buffer.getBuffer(testType), LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY, oR, oG, oB, 1.0f);
            flushRT(buffer, testType);
            poseStack.popPose();
        }
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
     * Per-STACK BEWLR outline texture resolver. Key = item class FQN, value = a function from the
     * stack to its outline texture (or null to fall through). Needed when one item class renders many
     * visual variants from a single shared model with per-variant textures, so a static per-class
     * entry can't tell them apart: Ice &amp; Fire troll weapons (one {@code ItemTrollWeapon}, one shared
     * {@code ModelTrollWeapon}, a different {@code EnumTroll.Weapon.TEXTURE} per weapon) and death worm
     * gauntlets (one {@code ItemDeathwormGauntlet}, three registry instances → red/white/yellow
     * textures). Without it the outline traces the full shared model (white.png / untextured dilation)
     * and the glow covers the whole item. Consulted by {@link #doItemOutline} (both stencil and
     * shader-pack paths) after {@link #BEWLR_OUTLINE_TEXTURES}.
     */
    public static final Map<String, Function<ItemStack, ResourceLocation>> BEWLR_OUTLINE_TEXTURE_RESOLVERS = new ConcurrentHashMap<>();

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
            IN_OUTLINE.set(true);
            try {
                // Textured outline path — alpha-discards against the variant's own texture so
                // BEWLRs that pack multiple variants into a single shared Model (IaF troll weapon,
                // death worm gauntlet) only outline the currently-active variant. The prior
                // texture-less forShaderOutline() drew the dilated mesh on every cube in the
                // shared model regardless of which variant's texture was bound — under a shader
                // pack you'd see all troll weapon outlines mixed together. forShaderArmorOutlineTextured
                // uses RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER which maps to gbuffers_entities and
                // discards transparent texels; the parallel alpha-mask (RGB=255, alpha preserved)
                // keeps the ring in the chosen outline color rather than tinting it with the texture
                // albedo. Caller must pass FullColorOverrideConsumer so UV reaches the buffer.
                //
                // Scale around the model's own AABB centroid (NullConsumer pre-pass), not the pose
                // origin — BEWLR models are typically offset from origin and a uniform pose-origin
                // scale shifts the dilated shell off-center.
                RenderType outlineRT = (texture != null)
                        ? forShaderArmorOutlineTextured(getArmorAlphaMask(texture))
                        : forShaderOutline();
                if (texture != null) {
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
                    VertexConsumer outlineBuf = new FullColorOverrideConsumer(
                            buffer.getBuffer(outlineRT), rByte, gByte, bByte, 255);
                    poseStack.pushPose();
                    poseStack.last().pose().mulLocal(new Matrix4f()
                            .translate(cx, cy, cz)
                            .scale(1.06f, 1.06f, 1.06f)
                            .translate(-cx, -cy, -cz));
                    model.renderToBuffer(poseStack, outlineBuf, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, oR, oG, oB, 1.0f);
                    if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch(outlineRT);
                    poseStack.popPose();
                } else {
                    // Texture-less fallback (kept for callers that pass null) — original behavior.
                    VertexConsumer outlineBuf = new PositionColorOnlyConsumer(
                            buffer.getBuffer(outlineRT), rByte, gByte, bByte, 255);
                    poseStack.pushPose();
                    poseStack.scale(1.06f, 1.06f, 1.06f);
                    model.renderToBuffer(poseStack, outlineBuf, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, oR, oG, oB, 1.0f);
                    if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch(outlineRT);
                    poseStack.popPose();
                }
            } finally {
                IN_OUTLINE.set(false);
            }
            return;
        }

        // Item-variant write: no polygon offset. BEWLR items' base draws don't use polygon offset,
        // and the armor-matching offset can clip the silhouette in front of the near plane in 3.5D FPM.
        int slot = nextStencilSlot();
        RenderType writeType = forOutlineStencilWriteItem(slot, texture);
        RenderType testType  = forOutlineStencilTest(slot, texture);
        flushAll(buffer);
        Minecraft.getInstance().getMainRenderTarget().enableStencil();
        IN_OUTLINE.set(true);
        try {
            // Pass 1 (stencil silhouette) — masks + stencil setup baked into writeType shards (shader-mod-safe).
            model.renderToBuffer(poseStack, buffer.getBuffer(writeType), packedLight, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);
            flushRT(buffer, writeType);

            // Pass 2 (dilated outline) — EQUAL,0 stencil test baked into testType shards.
            RenderSystem.setShaderColor(oR, oG, oB, 1.0f);
            poseStack.pushPose();
            poseStack.scale(1.06f, 1.06f, 1.06f);
            model.renderToBuffer(poseStack, buffer.getBuffer(testType), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, oR, oG, oB, 1.0f);
            flushRT(buffer, testType);
            poseStack.popPose();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            // Trailing full drain — see doItemOutline for the rationale (atomic per-item stencil
            // pipeline under shader-mod FullyBuffered, otherwise the last item of the frame stays
            // queued and its stencil pass sees disrupted state at end-of-frame drain).
            flushAll(buffer);
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
    /**
     * GUI icon glow outline (hotbar / inventory / chest / JEI / any 2D context). Fires from
     * {@code ItemRendererMixin}'s HEAD inject BEFORE the actual item renders so the 4 translated
     * copies land in the same RT buckets in submission order; the real item is appended last and
     * naturally overdraws the overlap, leaving only the +/- 1 GUI-pixel halo visible.
     *
     * Mechanism: wrap the bufferSource so every {@code getBuffer(rt)} returns a
     * {@link FullColorOverrideConsumer} clamped to the glow color. Recursively call
     * {@link net.minecraft.client.renderer.entity.ItemRenderer#render} with the wrapped source 4
     * times at offsets (-1, 0), (1, 0), (0, -1), (0, 1) GUI pixels. The pose stack at this point
     * has been scaled by 16, so 1 GUI pixel == 1/16 model-space unit.
     *
     * {@link #IN_OUTLINE} is set so {@code ItemRendererMixin.applyGlint} short-circuits to the
     * bare base buffer (no glint overlay during outline copies).
     */
    /**
     * Reverted to the first-iteration working state: 4 translated render() copies through a
     * FullColorOverrideConsumer that forwards to whatever RT vanilla's ItemRenderer chose for
     * each draw (no RT redirect, no alpha-mask atlas, no scissor). Outline IS visible in GUI
     * but its color BLEEDS the sprite's edge pixels — shader emits `texColor × vertexColor`,
     * so a green sword sprite tints the red glow into a muddy green. Fixing the color is still
     * an open problem; see "Attempted color fixes (all broke the draw entirely)" below.
     *
     * Attempted color fixes (all broke the draw entirely — outline disappeared):
     *   1. Redirect every getBuffer(rt) to a single `RenderType.entityCutoutNoCull(getBlocksAlphaMask())`.
     *      Idea: the alpha-mask atlas has RGB clamped to 255, so shader output =
     *      `white × vertexColor = glowColor`. Same trick used successfully by world-outline
     *      shader-pack path. RESULT: no outline rendered at all in GUI. Possibly the
     *      alpha-mask texture isn't fully built / bound in GUI context, or the RT's
     *      LIGHTMAP/OVERLAY state interacts badly with GUI's render state.
     *   2. Redirect every getBuffer(rt) to a single `RenderType.entityCutoutNoCull(LOCATION_BLOCKS)`
     *      (the real items atlas, not the mask). Same redirect mechanism, just different texture.
     *      Idea: at least prove the redirect path works. RESULT: also no outline at all.
     *      Suggests the issue isn't the texture but the RT redirect itself — possibly because
     *      a single shared outlineRT can't satisfy whatever format/state ItemRenderer's chosen
     *      per-quad RT needed (e.g. translucentCullBlockSheet uses BLOCK format, not NEW_ENTITY).
     *   3. Added slot scissor (glScissor on a 16x16 slot-bounds box). Initially read pose stack's
     *      m30/m31 (wrong — that PoseStack is identity in GUI; the GUI transform lives on
     *      RenderSystem.getModelViewStack). Even after switching to modelView stack, scissor
     *      was disabled and outlines still didn't render — so scissor wasn't the cause.
     *   4. Added BEWLR skip via `IClientItemExtensions.of(stack).getCustomRenderer() != null`.
     *      WRONG — Forge returns the default vanilla BlockEntityWithoutLevelRenderer for EVERY
     *      item, so this filtered out every 2D item including swords/apples. Use
     *      `model.isCustomRenderer()` instead if you need to gate on real BEWLR rendering.
     *      But even with that fix, the redirect approach (attempts 1+2) still broke the draw.
     *
     * Things to try next:
     *   - Keep the per-rt routing (don't redirect), but intercept `color()` on the
     *     FullColorOverrideConsumer differently. E.g. force a white texture via a TextureStateShard
     *     override at the consumer level — but BufferBuilder doesn't bind textures, the RT does.
     *   - Bind BLOCKS_ALPHA_MASK_LOC as Sampler0 via `RenderSystem.setShaderTexture(0, …)` for
     *     the duration of the outline copies, leaving the RT itself alone. The RT's
     *     TextureStateShard.setupRenderState happens at flush time and will REBIND Sampler0 to
     *     the RT's declared texture, undoing this — would need to either flush per copy or use
     *     a custom RT whose TextureStateShard.setupRenderState() does the alpha-mask bind.
     *   - Make a custom RT identical to translucentCullBlockSheet but with a setup-time
     *     `setShaderTexture(0, BLOCKS_ALPHA_MASK_LOC)` override. Keeps BLOCK format, keeps
     *     LIGHTMAP/OVERLAY state, only swaps the Sampler0 binding.
     *   - Skip the texture entirely: render dilated POSITION_COLOR quads built from the
     *     BakedModel's sprite UV bounds. Loses sprite-shape silhouette but guarantees pure
     *     glow color. Would need to manually iterate quads / handleCameraTransforms etc.
     */
    public static void doGuiItemOutline(ItemStack stack, ItemDisplayContext displayContext,
            boolean leftHand, PoseStack poseStack, MultiBufferSource buffer, int packedLight,
            int packedOverlay, BakedModel model) {
        if (displayContext != ItemDisplayContext.GUI) return;
        int color = outlineColor(stack);
        int rByte = (color >> 16) & 0xFF;
        int gByte = (color >>  8) & 0xFF;
        int bByte =  color        & 0xFF;
        Minecraft mc = Minecraft.getInstance();
        // New color approach: redirect every getBuffer call to a single outline RT that uses
        // RENDERTYPE_OUTLINE_SHADER. The outline shader outputs `vertexColor.rgb` with
        // `texture.alpha` — i.e. it samples the items atlas ONLY for the alpha mask and ignores
        // texture RGB entirely. So the output is pure glow color in the sprite silhouette.
        //
        // ColorOverrideConsumer (NOT Full) drops overlayCoords / uv2 / normal so the
        // POSITION_COLOR_TEX-format BufferBuilder isn't asked to write fields outside its format.
        // Earlier attempts redirecting to entityCutoutNoCull (NEW_ENTITY) killed the draw — those
        // failed because of a format/shader interaction we never pinpointed. This recipe matches
        // the proven world-space outline RT (forOutlineStencilTest) which we know renders
        // correctly, minus the stencil/depth-bias state that doesn't apply in GUI.
        // 3D BEWLR items render a full 3D model as their icon, not a flat sprite. The flat
        // 4-pixel-translate path below just refills their whole silhouette (the "glow covers the item"
        // bug), and forGuiItemOutline's BLOCKS-atlas alpha mask doesn't fit their own textures. Route
        // them through a white.png solid fill (or the per-variant texture from the resolver) plus one
        // scale-from-center pass. Items that report isCustomRenderer() but swap to a FLAT GUI sprite
        // (IaF tide trident, flagged FLAT_ON_GROUND_ITEMS) stay on the flat-sprite path.
        boolean flatGuiSwap = FLAT_ON_GROUND_ITEMS.contains(stack.getItem().getClass().getName());
        boolean isBewlr = model != null && model.isCustomRenderer() && !flatGuiSwap;
        RenderType outlineRT;
        if (isBewlr) {
            Function<ItemStack, ResourceLocation> resolver =
                    BEWLR_OUTLINE_TEXTURE_RESOLVERS.get(stack.getItem().getClass().getName());
            ResourceLocation bewlrTex = resolver != null ? resolver.apply(stack) : null;
            outlineRT = bewlrTex != null ? forGuiBewlrOutlineTextured(bewlrTex) : forGuiBewlrOutline();
        } else {
            outlineRT = forGuiItemOutline();
        }
        MultiBufferSource wrapped = rt -> new ColorOverrideConsumer(
                buffer.getBuffer(outlineRT), rByte, gByte, bByte, 255);

        // Slot scissor: in 1.20.1 the slot transform (translate(x+8,y+8,...) + scale(1,-1,1) +
        // scale(16,16,16)) can live on EITHER:
        //   - the PoseStack passed to render(), when called via GuiGraphics.renderItemInternal
        //     (most modern GUI screens), OR
        //   - RenderSystem.getModelViewStack(), when called via the legacy
        //     ItemRenderer.tryRenderGuiItem path (some older calls).
        // Reading only one matrix gives m30=0 for the other path → scissor lands far from the
        // slot and clips out the outline. Compose `modelView * poseStack` so the translation row
        // of the result is the slot center in GUI-scaled pixel space regardless of which path
        // applied the transform. Then convert to framebuffer pixels via guiScale, flip Y for GL
        // scissor (bottom-left origin), and clip to a 16x16 slot box. Drain vanilla's queued
        // work BEFORE setting the scissor so we don't accidentally clip earlier draws.
        if (buffer instanceof MultiBufferSource.BufferSource preBs) preBs.endBatch();
        Window window = mc.getWindow();
        double guiScale = window.getGuiScale();
        Matrix4f combined = new Matrix4f(RenderSystem.getModelViewStack().last().pose());
        combined.mul(poseStack.last().pose());
        float cxGui = combined.m30();
        float cyGui = combined.m31();
        int slotLeftPx = (int) Math.round((cxGui - 8) * guiScale);
        int slotTopPx  = (int) Math.round((cyGui - 8) * guiScale);
        int slotWPx    = (int) Math.round(16 * guiScale);
        int slotHPx    = (int) Math.round(16 * guiScale);
        int glX = slotLeftPx;
        int glY = window.getHeight() - slotTopPx - slotHPx;
        if (slotWPx <= 0 || slotHPx <= 0) return;
        boolean prevScissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] prevBox = new int[4];
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, prevBox);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(glX, glY, slotWPx, slotHPx);

        final float step = 1.0f / 16.0f;
        float[][] offsets = { {-step, 0}, {step, 0}, {0, -step}, {0, step} };
        // BEWLR halo thickness as a screen-space scale around the slot/model center. Z stays 1.0 so
        // the enlarged model keeps the real item's depth (GUI is orthographic). Tune if too thin/thick.
        final float bewlrHalo = 1.07f;
        IN_OUTLINE.set(true);
        try {
            if (isBewlr) {
                // One enlarged solid-glow pass; vanilla draws the real item on top right after this
                // HEAD inject returns, covering the center and leaving only the enlarged ring = halo.
                poseStack.pushPose();
                poseStack.scale(bewlrHalo, bewlrHalo, 1.0f);
                mc.getItemRenderer().render(stack, displayContext, leftHand, poseStack, wrapped,
                        packedLight, packedOverlay, model);
                poseStack.popPose();
            } else {
                for (float[] off : offsets) {
                    poseStack.pushPose();
                    poseStack.translate(off[0], off[1], 0.0f);
                    mc.getItemRenderer().render(stack, displayContext, leftHand, poseStack, wrapped,
                            packedLight, packedOverlay, model);
                    poseStack.popPose();
                }
            }
            // Flush ONLY our outline RT while scissor is still active so the clip applies to the
            // actual draw call. Other RTs accumulated by vanilla after our copies (the real item
            // draw that follows when this HEAD inject returns) are not flushed here, so they
            // render normally without our scissor.
            if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch(outlineRT);
        } finally {
            IN_OUTLINE.set(false);
            if (prevScissorEnabled) {
                GL11.glScissor(prevBox[0], prevBox[1], prevBox[2], prevBox[3]);
            } else {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
        }
    }

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
            LocalPlayer rp = mc.player;
            // FPM 3.5D + shaders ON: the local player's held item can render through multiple paths
            // (normal entity-loop render at actual position and/or FPM's arm render). The forward-pass
            // outline is additive, so a second call stacks a visible second ring.
            //
            // Dedup A — 1P suppression: suppress FIRST_PERSON context outlines only when FPM is
            // ACTIVELY rendering the player in 3.5D mode. When FPM is installed but the player is
            // in vanilla 1P view, FPM passes through to vanilla rendering — fpmRenderingPlayerGate
            // returns false — and the 1P outline is legitimate (no duplicate). Gating on fpmPresent
            // alone incorrectly killed vanilla 1P outlines whenever the FPM mod was merely installed.
            //
            // Dedup B — reference dedup: track which ItemStack instances have been outlined this frame.
            // The same inventory-slot instance is used across all render paths for a given held item,
            // so identity equality catches "same slot, second call." First call: added, proceeds.
            // Subsequent calls for the same instance: already in set, return early.
            boolean fpmRendering = fpmRenderingPlayerGate.getAsBoolean();
            if (fpmRendering) {
                if (displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                        || displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND) return;
            }
            // Gate on fpmRendering (FPM ACTIVELY in 3.5D), not fpmPresent (FPM merely installed).
            // FPM has its own F5 toggle — when the user is in FPM's vanilla-1P or vanilla-F5 view,
            // fpmRenderingPlayerGate returns false even though the mod is loaded. Using fpmPresent
            // here forced the held item through the FPM NP/dilation path whenever FPM was installed,
            // regardless of whether the player was actually in 3.5D mode.
            boolean isLocalPlayerItem = fpmRendering && rp != null
                    && (stack == rp.getMainHandItem() || stack == rp.getOffhandItem());
            if (isLocalPlayerItem && !shaderOutlinedThisFrame.add(stack)) return;
            boolean fpmActive = isLocalPlayerItem || fpmRendering;
            //
            // ── FPM + shaders ON: remaining known issues (as of current session) ──
            //
            // TRIED: dz=0.0 for FPM items (fpmActive ? 0.0f : 0.03f) — first attempt.
            //   RESULT: LEQUAL passes everywhere at equal depth — copies are coplanar with the item.
            //   Entire item interior fills with outline color instead of just the edge ring. Rejected.
            //   ROOT CAUSE (diagnosed later): outline flushed BEFORE item depth was committed because
            //   the LINES-bucket ordering (tagAsLateRenderForShaders) didn't exist yet. Polygon offset
            //   had no item depth in the buffer to compare against → LEQUAL trivially passed.
            //
            // TRIED: dz=0.008 compromise.
            //   RESULT: flat items (apple) show perspective XY drift when looking up/down — the
            //   outline layer moves over the item. 2.5D tilted sprites (swords) still wrap — tilted
            //   face depth variance exceeds the fixed 0.008 push on some interior quads → filled.
            //
            // TRIED: dz=0.0 + rely on PUSH_BACK_LAYERING's glPolygonOffset(1,10) alone (after the
            //   LINES-bucket fix committed item depth first).
            //   RESULT: 2.5D sword stopped drifting (good — XY was perspective-only) but interior
            //   still showed THICK FILL of outline color under shader packs. 3D BEWLR items
            //   (trident etc.) appeared tinted/wrapped in outline color — visible underneath but
            //   overlaid additively. Root cause: the shader mod's deferred pipeline drops or overrides
            //   glPolygonOffset for programs that write gl_FragDepth or reconstruct depth
            //   from a different gbuffer. With polygon-offset silently ignored, the outline mesh
            //   was coplanar with item depth → LEQUAL passes inside the silhouette → additive
            //   LIGHTNING_TRANSPARENCY paints over the whole item face.
            //
            // TRIED: FPM-specific dz=0.02/0.03 with inline glDepthRange(0.0005, 1.0) before endBatch.
            //   RESULT: outline still moved with camera. Root cause: glDepthRange only applies to
            //   rasterizer-computed depth; shader programs that write gl_FragDepth bypass it
            //   entirely. dz in the 0.02-0.03 range at FPM camera distance also causes visible
            //   perspective XY drift (the near-camera distance amplifies the xy/z ratio). Rejected.
            //
            // TRIED: unified dz=0.03 for all non-1P (1P uses 0).
            //   RESULT: FPM outline still moved; also 1P z-fighting under shader packs
            //   (polygon offset dropped → coplanar copies → LEQUAL passes inside). Rejected.
            //
            // TRIED: per-context dz (1P=0.003, FPM=0.005, 3P=0.03, ground=0.08).
            //   RESULT: terrible z-fighting in all shader-on FPM cases, even worse than dz=0.
            //   The small dz values at FPM close range were insufficient or interacted badly.
            //   Rejected, reverted.
            //
            // CURRENT: dz=0.03 for all contexts (1P/3P/FPM), 0.08 for ground/fixed.
            //   + Perspective compensation on XY offsets: each copy's XY is shifted by
            //   (cx*dz/D, cy*dz/D) where (cx,cy) = AABB eye-space centroid and D = eye depth.
            //   This cancels the first-order screen-space drift caused by the dz depth push at
            //   close camera distances. 1P previously used dz=0 (relied on polygon-offset baked
            //   into the RT) but shader packs drop polygon-offset for the hand pass, leaving
            //   copies coplanar → LEQUAL passes everywhere → outline wraps/fills the item.
            //   Switching to dz=0.03 for 1P (with perspective compensation) fixes that.
            //
            // TRIED: route FPM+shader items through the stencil path (skip isShaderPackActive() block,
            //   fall through to the stencil code below, with czOff=0 since stencil mask handles occlusion).
            //   RESULT: outline removed entirely under active shader packs (both FPM held items and vanilla
            //   1P items disappeared). The stencil pipeline is dead under an active shader pack — the
            //   RENDERTYPE_OUTLINE_SHADER + FORCE_MAIN_TARGET approach that works for armor (no pack,
            //   shader-mod-installed) does NOT work when a pack is actually loaded. Reverted immediately.
            //
            // TRIED: defer the shader-pack item outline draw out of the entity render loop into
            //   RenderLevelStageEvent.AFTER_ENTITIES via a frame-scoped DeferredItemOutline capture
            //   list. Gate was fpmActive (isLocalPlayerItem || fpmRendering). The replay used a
            //   pose-snapshot Matrix4f, the vanilla bufferSource (not Iris-batched), and the same
            //   dilation/4-translate geometry as the inline path. Theory: AFTER_ENTITIES fires after
            //   the entity batch flushes, so depth is committed and the dilated mesh's LEQUAL test
            //   would run against actual item geometry instead of an empty buffer.
            //   RESULT: outlines vanished entirely in vanilla 1P with shaders on. ROOT CAUSE: vanilla
            //   1P held items render via HandRenderer.renderSolid, which runs AFTER LevelRenderer.renderLevel
            //   completes — i.e. AFTER AFTER_ENTITIES has already fired. So the deferred outline drew
            //   into the gbuffer with no item depth committed yet (depth held only world/background),
            //   then HandRenderer painted the actual item ON TOP with depth-write, overdrawing our
            //   outline. The deferral only made sense for items routed through the entity pass (FPM
            //   3.5D held items go through ItemInHandLayer → entity loop → AFTER_ENTITIES sees their
            //   depth). To make this work for both paths we would need a second drain hook AFTER
            //   HandRenderer.renderSolid — but at that point we are no longer in renderLevel's
            //   view/projection state, so the pose-snapshot replay is also invalid. Reverted.
            //   See ScheduleWakeup-equivalent insight: AFTER_ENTITIES is for entity-rendered items
            //   only; never sufficient as a universal post-flush hook.
            //
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

                // Dilation path: BEWLR items always, plus 3D-modeled BakedModel items (crossbow,
                // bow) under FPM only. The two subtypes use DIFFERENT mechanisms inside this branch:
                // BEWLR → AABB-centroid scale (convex enough for scale to behave),
                // 3D BakedModel (crossbow/bow) → NP (non-convex limbs need per-vertex separation).
                boolean isGui3dS = mc.getItemRenderer().getModel(stack, mc.level, rp, 0).isGui3d();
                boolean useDilation = customRendererS || (fpmActive && isGui3dS);
                if (useDilation) {
                    // Dilated mesh with front-face cull (same trick as armor).
                    // Only the dilated mesh's back faces draw; LEQUAL depth-test occludes them
                    // wherever they sit behind the original item's front face → ring effect,
                    // not a filled silhouette.
                    float cx = (minMax[0] + minMax[3]) * 0.5f;
                    float cy = (minMax[1] + minMax[4]) * 0.5f;
                    float cz = (minMax[2] + minMax[5]) * 0.5f;
                    // At first-person camera distance under shaders (BOTH vanilla 1P and FPM
                    // 3.5D), the dilated mesh's depth ties or near-ties with the item's drawn
                    // depth on non-convex 3D items (crossbow, spears) from certain camera tilts:
                    // shader packs drop the polygon-offset that would bias the dilated back face
                    // decisively behind the item, and 3D scale around the eye-space centroid
                    // doesn't guarantee back-face depth > front-face depth when the model has
                    // parts extending sideways from the centroid. Two changes for that case:
                    //   1. Strict LESS depth test (forShaderItemOutlineFpm) so equal-depth pixels
                    //      no longer draw the outline over the item. Vanilla 3P keeps the LEQUAL
                    //      armor RT because its polygon-offset IS honored there (entity render
                    //      pass; shader packs only drop it for the held-hand pass).
                    //   2. Bump dilation 1.06 → 1.10 so the geometric back-face displacement is
                    //      larger; fewer angles produce a dilated face that lands in front of the
                    //      item's drawn geometry. Trade-off: visibly thicker outline at 1P.
                    if (customRendererS) {
                        // BEWLR items (trident, shield, banner, decorated pot, etc.) across all
                        // contexts — vanilla 1P, vanilla 3P, FPM 3.5D — use AABB-centroid scale
                        // dilation at 1.06×. BEWLR geometry is convex enough that centroid-scale
                        // reliably pushes back faces behind front faces; the non-convex failure
                        // modes that forced NP for crossbow/bow don't apply.
                        //
                        // Per-variant texture: the untextured POSITION_COLOR dilation traces the full
                        // shared model, so multi-variant BEWLRs (troll weapon / death worm gauntlet)
                        // get their whole model covered. When a resolver/override supplies the active
                        // variant's texture, route through the textured shader RT (alpha-mask = RGB 255
                        // + original alpha, so the ring stays the chosen color) so it alpha-discards to
                        // just that variant's silhouette.
                        String bewlrClass = stack.getItem().getClass().getName();
                        ResourceLocation bewlrTex = BEWLR_OUTLINE_TEXTURES.get(bewlrClass);
                        Function<ItemStack, ResourceLocation> bewlrResolver = BEWLR_OUTLINE_TEXTURE_RESOLVERS.get(bewlrClass);
                        if (bewlrResolver != null) {
                            ResourceLocation perStack = bewlrResolver.apply(stack);
                            if (perStack != null) bewlrTex = perStack;
                        }
                        final RenderType outlineRT = bewlrTex != null
                                ? forShaderArmorOutlineTextured(getArmorAlphaMask(bewlrTex))
                                : forShaderArmorOutline();
                        MultiBufferSource outSrc = bewlrTex != null
                                ? rt -> new FullColorOverrideConsumer(buffer.getBuffer(outlineRT), rByte, gByte, bByte, 255)
                                : rt -> new PositionColorOnlyConsumer(buffer.getBuffer(outlineRT), rByte, gByte, bByte, 255);
                        poseStack.pushPose();
                        poseStack.last().pose().mulLocal(new Matrix4f()
                                .translate(cx, cy, cz)
                                .scale(1.03f, 1.03f, 1.03f)
                                .translate(-cx, -cy, -cz));
                        mc.getItemRenderer().renderStatic(rp, stack, displayContext, lh, poseStack, outSrc, mc.level, LightTexture.FULL_BRIGHT, packedOverlay, 0);
                        if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch(outlineRT);
                        poseStack.popPose();
                    } else {
                        // 3D-modeled BakedModel items (crossbow, bow) under FPM only. Per-vertex
                        // normal-push — centroid-scale fails on non-convex limbs (the scale pushes
                        // concave back faces toward the camera). NP moves each vertex along its
                        // own world-space normal so back faces always move further away.
                        // TRIED (do not re-attempt): centroid-scale + world-Z push (0.012).
                        //   Helped at level pitch; still z-fought at steep tilts on crossbow/bow.
                        // TRIED (do not re-attempt): centroid-scale + LESS depth test.
                        //   LESS prevented equal-depth pixels but ring disappeared on many angles.
                        // TRIED (do not re-attempt): eye-space translate post-multiplied on dilation.
                        //   Removed outline on many 3D items — uniform translate lost LEQUAL at
                        //   fringe against already-buffered terrain/entity depth.
                        // TRIED (do not re-attempt): camera look-direction push, split-axis dilation
                        //   (XY centroid, Z=0), 45°-rotated normal second pass. All failed.
                        RenderType outlineRT = forShaderItemOutlineNormalPush();
                        final float push = 0.02f;
                        MultiBufferSource outSrc = rt -> new NormalPushConsumer(
                                buffer.getBuffer(outlineRT), rByte, gByte, bByte, 255, push);
                        mc.getItemRenderer().renderStatic(rp, stack, displayContext, lh, poseStack, outSrc, mc.level, LightTexture.FULL_BRIGHT, packedOverlay, 0);
                        if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch(outlineRT);
                    }
                } else {
                    // 2D sprite / BakedModel path: 4-translation, entityCutoutNoCull on blocks atlas.
                    // ENTITIES_CUTOUT mapping is universal across shader mods; alpha-discard preserves the sprite
                    // silhouette so the dilated copies form a silhouette-shaped halo rather than a
                    // rectangle. ColorOverrideConsumer forces vertex color to the outline color so
                    // the shader emits ~ outlineColor × spriteTexel. The "extra textured copy" issue
                    // this used to cause under shaders is fixed by the eye-space Z push-back below,
                    // which guarantees the copies sit behind the item depth; only the fringe
                    // extending past the original silhouette survives LEQUAL and is visible.
                    // FPM + shaders ON: normal-push outline. Pushes each voxel vertex along its
                    // own surface normal, so back faces move behind the item (LESS depth-test
                    // rejects inside silhouette) and side faces at silhouette edges push sideways
                    // in screen space — that's where the ring forms. Geometric separation is
                    // angle-independent: no more wrap-vs-drift trade-off. Single render call
                    // replaces the 4-translate copies of the eye-space push approach.
                    // TRIED (do not re-attempt): drop this fpmActive NP block entirely and let FPM
                    //   fall through to the 4-translate path below, reasoning that
                    //   tagAsLateRenderForShaders fixed the original "wraps/fills interior" failure.
                    //   RESULT: re-introduces the exact wrap/drift problem this NP path replaced.
                    //   The 4-translate eye-space Z push does not produce correct depth separation
                    //   for FPM items regardless of flush timing. DO NOT remove this block.
                    if (fpmActive) {
                        // Split flat 2D sprite items by whether their voxelization's side faces
                        // are part of the held-in-hand look or get in the way of it:
                        //
                        // EXTRUDED-LOOKING items (sword/axe/hoe/pickaxe/shovel/bow/crossbow/
                        //   fishing rod) — the slim diagonal blade or curved limb means the
                        //   voxelization side faces ARE part of the held silhouette; outlining
                        //   them with NormalPush gives the right "in-hand weapon" outline.
                        //
                        // FLAT-LOOKING items (apple, ingot, gem, food, dye, redstone, …) — the
                        //   visible "thing" is the centered sprite blob; the voxelization side
                        //   faces are a thin perimeter strip that, when the arm tilts ~40° toward
                        //   camera (FPM dynamicHands), face the camera as a slab edge and visually
                        //   dominate the outline ("the outline is standing up sideways").
                        //
                        // For flat-looking items: filter out the side faces at the consumer level
                        // (collapse to centroid → degenerate quads) AND apply a 1.10× pose-stack
                        // scale around the eye-space AABB centroid so the surviving front quads
                        // draw as an outline ring. Same geometric mechanism as the BEWLR/armor
                        // path; the filter restricts the dilated surface to the sprite plane only.
                        Item it = stack.getItem();
                        boolean extrudedLooking = it instanceof TieredItem
                                || it instanceof BowItem
                                || it instanceof CrossbowItem
                                || it instanceof FishingRodItem;
                        if (extrudedLooking) {
                            RenderType npRT = forShaderSpriteOutlineNormalPush(getBlocksAlphaMask());
                            final float npPush = 0.012f;
                            MultiBufferSource npSrc = rt -> new NormalPushTexturedConsumer(
                                    buffer.getBuffer(npRT), rByte, gByte, bByte, 255, npPush);
                            mc.getItemRenderer().renderStatic(rp, stack, displayContext, lh, poseStack, npSrc, mc.level, LightTexture.FULL_BRIGHT, packedOverlay, 0);
                            if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch(npRT);
                            return;
                        }
                        float ffCx = (minMax[0] + minMax[3]) * 0.5f;
                        float ffCy = (minMax[1] + minMax[4]) * 0.5f;
                        float ffCz = (minMax[2] + minMax[5]) * 0.5f;
                        // Dilation bumped well past 1.10 so the ring's screen width exceeds the
                        // perspective shrinkage incurred by the -dz push (outline_screen_ratio =
                        // dilation * D / (D + dz); at FPM hand depth D≈1, dz=0.06 a 1.10× dilation
                        // becomes only ~1.04× on screen → too thin to see).
                        final float ffDilation = 1.20f;
                        final float ffNormalThreshold = 0.7f;
                        // Eye-space Z push-back. Pose-stack scale alone barely shifts Z for a flat
                        // slab (its Z extent is ~0), so the dilated copy ties depth with the item
                        // and LEQUAL passes across the whole silhouette → outline wraps the item.
                        // Push the entire dilated copy back by dz in eye-space so the front face
                        // sits behind the item's drawn depth; LEQUAL then rejects the dilated copy
                        // inside the silhouette and only the fringe extending past the original
                        // silhouette draws — that fringe is the outline ring.
                        //
                        // Perspective compensation. Pushing the dilated copy back by dz in eye-space
                        // shifts its projected position toward screen center by (cx*dz/D, cy*dz/D)
                        // after the perspective divide. Without compensation, the outline drifts
                        // relative to the item as the camera rotates (cx and cy change in eye space).
                        // Pre-shift the dilated copy by (corrX, corrY) so the centroid's projected
                        // position lands exactly back on the item's centroid screen position.
                        final float ffDz = 0.06f;
                        float ffEyeDepth = -ffCz;
                        if (ffEyeDepth < 0.1f) return;
                        float ffCorrX = ffCx * ffDz / ffEyeDepth;
                        float ffCorrY = ffCy * ffDz / ffEyeDepth;
                        // forShaderSpriteOutlineNormalPush (CULL front-face + LESS depth +
                        // LIGHTNING_TRANSPARENCY) not forShaderSpriteOutline. The textured-LEQUAL
                        // variant loses its blend/cutout state in the shader pack's deferred
                        // composite — the dilated copy then renders as a fully textured ghost of
                        // the item instead of being depth-rejected inside the silhouette. The
                        // front-face cull guarantees only back faces of each sprite layer draw,
                        // and LESS rejects equal-depth pixels, so the only surviving fragments
                        // are the dilated back-face vertices that extend past the item's screen
                        // extent — that's the outline ring. Same RT the extruded-item NP branch
                        // uses for the same reason.
                        RenderType ffRT = forShaderSpriteOutlineNormalPush(getBlocksAlphaMask());
                        MultiBufferSource ffSrc = rt -> new FrontFaceFilterConsumer(
                                buffer.getBuffer(ffRT), rByte, gByte, bByte, 255,
                                ffCx, ffCy, ffCz, ffNormalThreshold);
                        poseStack.pushPose();
                        poseStack.last().pose().mulLocal(new Matrix4f()
                                .translate(ffCorrX, ffCorrY, -ffDz)
                                .translate(ffCx, ffCy, ffCz)
                                .scale(ffDilation, ffDilation, ffDilation)
                                .translate(-ffCx, -ffCy, -ffCz));
                        mc.getItemRenderer().renderStatic(rp, stack, displayContext, lh, poseStack, ffSrc, mc.level, LightTexture.FULL_BRIGHT, packedOverlay, 0);
                        if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch(ffRT);
                        poseStack.popPose();
                        return;
                    }
                    // Unified path for all non-FPM contexts (ground, fixed, 1P, 3P). Same approach
                    // ground/item-frame already uses successfully: forShaderSpriteOutline +
                    // PUSH_BACK_DEPTH_RANGE_LAYERING (glDepthRange honored in all Iris phases
                    // including HAND) + model-space 4-translate.
                    //
                    // Per-context quad filtering on the voxelized sprite:
                    //  - 1P held: drop 2D face quads (they tile the flat front/back and produce
                    //    sword-shape additive copies that z-fight with the actual face when the
                    //    camera pans). Keep extruded side-face quads → perimeter ring.
                    //  - 3P held: drop side-face quads (when viewed at angle, the translated
                    //    extruded sides look like a 3D outline ring around the 2D face).
                    //    Keep 2D face quads → flat outline copy on each face.
                    //  - Ground/Fixed: no filter — sprite is flat-on, all quads contribute the
                    //    right 1-pixel halo.
                    RenderType outlineRT = forShaderSpriteOutline(getBlocksAlphaMask());
                    boolean is1P = displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                                || displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
                    boolean is3P = displayContext == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                                || displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
                    boolean isGround = displayContext == ItemDisplayContext.GROUND;
                    boolean isGroundOrFixedDz = isGround || displayContext == ItemDisplayContext.FIXED;
                    // 1P: AABB-centroid dilation trial. Theory — multi-slab voxelized items
                    // (e.g. Spartan Weaponry spears, which Spartan's ItemModelGeneratorMixin builds
                    // by running processFrames on every non-coating texture layer separately and
                    // concatenating the resulting BlockElements) draw N stacked silhouettes. The
                    // 4-translate path outlines each slab independently → N visible rings stacked
                    // along the extrusion axis. Centroid-scale dilates the entire model AABB once;
                    // CULL_FRONT keeps only back faces; result is a single ring around the union
                    // silhouette regardless of slab count. Uses forShaderArmorOutlineTextured for
                    // alpha-discard (otherwise the dilated shell fills the voxelization's bounding
                    // rectangle instead of the sprite shape).
                    if (is1P || is3P) {
                        float cx = (minMax[0] + minMax[3]) * 0.5f;
                        float cy = (minMax[1] + minMax[4]) * 0.5f;
                        float cz = (minMax[2] + minMax[5]) * 0.5f;
                        // Texture-RGB tint bleed fix under shader packs. The pack's
                        // gbuffers_entities computes `vertexColor × textureRGB`, so binding the
                        // real block atlas tints the outline with the item's texture (worst case:
                        // white outline × red apple = red). {@link #getBlocksAlphaMask} returns a
                        // parallel atlas where every pixel has RGB=255 and the original alpha — the
                        // shader then computes `vertexColor × white = vertexColor` and the
                        // sprite silhouette is still preserved by alpha-discard. UVs are unchanged
                        // (same atlas dimensions).
                        //
                        // TRIED (kept here for context): RENDERTYPE_OUTLINE_SHADER routes to
                        // vanilla's separate outline target → invisible under Iris; custom
                        // POSITION_COLOR_TEX shader that samples only Sampler0.a is substituted
                        // away by Iris's EntityRenderStateShard wrap → also invisible even with
                        // FORCE_MAIN_TARGET. Mask atlas is the only path Iris doesn't override.
                        ResourceLocation maskAtlas = getBlocksAlphaMask();
                        RenderType outlineRTHeld = forShaderArmorOutlineTextured(maskAtlas);
                        MultiBufferSource outHeldSrc = rt -> new FullColorOverrideConsumer(
                                buffer.getBuffer(outlineRTHeld), rByte, gByte, bByte, 255);
                        poseStack.pushPose();
                        poseStack.last().pose().mulLocal(new Matrix4f()
                                .translate(cx, cy, cz)
                                .scale(1.06f, 1.06f, 1.06f)
                                .translate(-cx, -cy, -cz));
                        mc.getItemRenderer().renderStatic(rp, stack, displayContext, lh, poseStack, outHeldSrc, mc.level, LightTexture.FULL_BRIGHT, packedOverlay, 0);
                        if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch(outlineRTHeld);
                        poseStack.popPose();
                        return;
                    }
                    final MultiBufferSource outSrc;
                    if (false) {
                        outSrc = rt -> new SideFaceOnlyConsumer(
                                buffer.getBuffer(outlineRT), rByte, gByte, bByte, 255, 0.001f, true);
                    } else {
                        // 3P and ground/fixed: no filter. (3P with keepSides=false dropped all
                        // visible geometry — the flat-face quads project to near-zero area at
                        // 3P angles, so the side-face perimeter is what actually forms the ring.)
                        outSrc = rt -> new FullColorOverrideConsumer(
                                buffer.getBuffer(outlineRT), rByte, gByte, bByte, 255);
                    }
                    // GROUND: 0.4× (Item Physic world scale, no vanilla 0.5× hover).
                    // FIXED: 1.0× (item frame vanilla scale).
                    // 1P/3P: 0.6× (held tilt inflates eye-space AABB beyond sprite's 16-pixel extent).
                    float uStep = isGround ? 0.4f
                                : isGroundOrFixedDz ? 1.0f
                                : (is1P || is3P) ? 0.6f
                                : 1.0f;
                    float udw = (minMax[3] - minMax[0]) / 16.0f * uStep;
                    float udh = (minMax[4] - minMax[1]) / 16.0f * uStep;
                    float[][] uOffsets = { { udw, 0 }, { -udw, 0 }, { 0, udh }, { 0, -udh } };
                    // 3P needs an eye-space Z push to keep the 4 translated copies BEHIND the
                    // rotated item. The RT's baked glDepthRange bias alone is enough for flat-on
                    // cases (GROUND/FIXED, and vanilla 1P where sword tilt aligns model axes
                    // with eye axes) but not for 3P at angles, and not at all for long items
                    // (crossbow/spear) where eye-space depth varies a lot across the silhouette
                    // — a small push gets beaten by the near end of the item.
                    //
                    // Bigger push (-0.10) handles long-item depth spread. To stop the perspective
                    // shrinkage from drifting the ring toward screen center, pre-shift each copy
                    // by (cx*dz/D, cy*dz/D) so the centroid's projected screen position lands
                    // back on the item. Same pattern as the FPM dilation branch above.
                    // 1P kept on model-space .translate() (the currently-working path).
                    float p3Cx = 0, p3Cy = 0, p3CorrX = 0, p3CorrY = 0;
                    final float p3Dz = 0.20f;
                    if (is3P) {
                        p3Cx = (minMax[0] + minMax[3]) * 0.5f;
                        p3Cy = (minMax[1] + minMax[4]) * 0.5f;
                        float p3Cz = (minMax[2] + minMax[5]) * 0.5f;
                        float p3EyeDepth = -p3Cz;
                        if (p3EyeDepth < 0.1f) p3EyeDepth = 0.1f;
                        p3CorrX = p3Cx * p3Dz / p3EyeDepth;
                        p3CorrY = p3Cy * p3Dz / p3EyeDepth;
                    }
                    for (float[] off : uOffsets) {
                        poseStack.pushPose();
                        if (is3P) {
                            poseStack.last().pose().mulLocal(new Matrix4f().translate(off[0] + p3CorrX, off[1] + p3CorrY, -p3Dz));
                        } else {
                            poseStack.last().pose().translate(off[0], off[1], 0);
                        }
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
        // rp = mc.player (declared above). Item-model overrides that depend on entity state (e.g.
        // trident's "throwing:1" predicate) resolve to the same model variant used in the original render.
        // With null, the throwing predicate always returns 0 → wrong display transforms → inverted outline.
        LocalPlayer renderPlayer = mc.player;
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
            String itemClass = stack.getItem().getClass().getName();
            ResourceLocation override = BEWLR_OUTLINE_TEXTURES.get(itemClass);
            if (override != null) outlineTex = override;
            // Per-stack resolver wins (troll weapon / death worm gauntlet pack many variants into one
            // shared model — white.png would mark every cube and the glow covers the whole item).
            Function<ItemStack, ResourceLocation> resolver = BEWLR_OUTLINE_TEXTURE_RESOLVERS.get(itemClass);
            if (resolver != null) {
                ResourceLocation perStack = resolver.apply(stack);
                if (perStack != null) outlineTex = perStack;
            }
        } else {
            outlineTex = new ResourceLocation("minecraft", "textures/atlas/blocks.png");
        }
        // Item-variant write: no polygon offset. Sprite/BEWLR base draws have no polygon offset,
        // and the armor-matching offset can push silhouette quads in front of the near plane in
        // 3.5D FPM at angles where the bone-rotated slope multiplies the units factor; result is
        // missing stencil stamps → filled-blob outline that shifts with camera angle.
        int slot = nextStencilSlot();
        RenderType writeType = forOutlineStencilWriteItem(slot, outlineTex);
        RenderType outlineType = forOutlineStencilTest(slot, outlineTex);
        flushAll(buffer);
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
            flushRT(buffer, writeType);

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
                    poseStack.last().pose().mulLocal(new Matrix4f().translate(cx + cxOff, cy + cyOff, cz + czOff).scale(1.03f, 1.03f, 1.03f).translate(-cx, -cy, -cz));
                    mc.getItemRenderer().renderStatic(renderPlayer, stack, displayContext, leftHand, poseStack, outlineSrc, mc.level, LightTexture.FULL_BRIGHT, packedOverlay, 0);
                    flushRT(buffer, outlineType);
                    poseStack.popPose();
                } else if (displayContext == ItemDisplayContext.GROUND
                        || displayContext == ItemDisplayContext.FIXED) {
                    // Flat sprites lying on the ground (Item Physic) or in item frames are
                    // rotated out of the camera-facing plane the eye-space 4-translate below
                    // was designed for. Use MODEL-space translates (post-multiplied, applied
                    // before the model→eye transform) so each copy moves by ~1 sprite pixel
                    // in the sprite's own X/Y axes regardless of orientation. dw/dh from the
                    // eye-space AABB still correspond to model extents for vanilla 1×1 sprite
                    // items (rotation preserves length). Same approach as the shader path.
                    // GROUND step is shrunk because Item Physic renders dropped items at
                    // world scale (no vanilla 0.5× hover scale) — extruded items (swords)
                    // read as a thick band at 1.0×. FIXED (item frames) renders at vanilla
                    // scale so 1.0× is correct there.
                    boolean isGround = displayContext == ItemDisplayContext.GROUND;
                    float gStep = isGround ? 0.4f : 1.0f;
                    float gdw = (minMax[3] - minMax[0]) / 16.0f * gStep;
                    float gdh = (minMax[4] - minMax[1]) / 16.0f * gStep;
                    float[][] gOffsets = { { gdw, 0 }, { -gdw, 0 }, { 0, gdh }, { 0, -gdh } };
                    for (float[] off : gOffsets) {
                        poseStack.pushPose();
                        poseStack.last().pose().translate(off[0], off[1], 0);
                        mc.getItemRenderer().renderStatic(renderPlayer, stack, displayContext, leftHand, poseStack, outlineSrc, mc.level, LightTexture.FULL_BRIGHT, packedOverlay, 0);
                        flushRT(buffer, outlineType);
                        poseStack.popPose();
                    }
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
                    // Cap held-context step so oversized sprites (modded greatswords, 32+px
                    // textures, scale-inflated item models) don't get proportionally thicker
                    // outlines. AABB/16 assumes a 16-px vanilla sprite; a 2× sword doubles dw.
                    // Vanilla sword in 1P/3P lands well below this cap, so normal items are
                    // unaffected.
                    if (is1P || is3P) {
                        final float MAX_HELD_STEP = 0.04f;
                        if (dw > MAX_HELD_STEP) dw = MAX_HELD_STEP;
                        if (dh > MAX_HELD_STEP) dh = MAX_HELD_STEP;
                    }
                    poseStack.pushPose();
                    poseStack.last().pose().mulLocal(new Matrix4f().translate(dw, 0, czOff));
                    mc.getItemRenderer().renderStatic(renderPlayer, stack, displayContext, leftHand, poseStack, outlineSrc, mc.level, LightTexture.FULL_BRIGHT, packedOverlay, 0);
                    flushRT(buffer, outlineType);
                    poseStack.popPose();
                    poseStack.pushPose();
                    poseStack.last().pose().mulLocal(new Matrix4f().translate(-dw, 0, czOff));
                    mc.getItemRenderer().renderStatic(renderPlayer, stack, displayContext, leftHand, poseStack, outlineSrc, mc.level, LightTexture.FULL_BRIGHT, packedOverlay, 0);
                    flushRT(buffer, outlineType);
                    poseStack.popPose();
                    poseStack.pushPose();
                    poseStack.last().pose().mulLocal(new Matrix4f().translate(0, dh, czOff));
                    mc.getItemRenderer().renderStatic(renderPlayer, stack, displayContext, leftHand, poseStack, outlineSrc, mc.level, LightTexture.FULL_BRIGHT, packedOverlay, 0);
                    flushRT(buffer, outlineType);
                    poseStack.popPose();
                    poseStack.pushPose();
                    poseStack.last().pose().mulLocal(new Matrix4f().translate(0, -dh, czOff));
                    mc.getItemRenderer().renderStatic(renderPlayer, stack, displayContext, leftHand, poseStack, outlineSrc, mc.level, LightTexture.FULL_BRIGHT, packedOverlay, 0);
                    flushRT(buffer, outlineType);
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
            flushAll(buffer);

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
    public static final class FullColorOverrideConsumer implements VertexConsumer {
        private final VertexConsumer wrapped;
        private final int r, g, b, a;
        public FullColorOverrideConsumer(VertexConsumer wrapped, int r, int g, int b, int a) {
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
    public static final class AABBTrackingConsumer implements VertexConsumer {
        private final VertexConsumer wrapped;
        private final float[] minMax;
        public AABBTrackingConsumer(VertexConsumer wrapped, float[] minMax) {
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
    private static volatile Method SHADER_GET_INSTANCE = null;
    private static volatile Method SHADER_IS_IN_USE = null;

    public static boolean isShaderPackActive() {
        if (!SHADER_LOOKUP_DONE) {
            synchronized (CustomGlintRenderer.class) {
                if (!SHADER_LOOKUP_DONE) {
                    try {
                        // Package path applies on Forge as well — do not change it.
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

    // The shader mod's official extension point for tagging a RenderType as "outline geometry."
    // Wrapping a RT with IsOutlineRenderStateShard.INSTANCE via OuterWrappedRenderType causes
    // GbufferPrograms.beginOutline()/endOutline() to fire around the draw call, which is what
    // the shader mod itself uses to mark the vanilla block-selection outline.
    //
    // ⚠ NOT USED for our forward-pass outline RTs (forShaderArmorOutlineTextured, forShaderOutline,
    // forShaderArmorOutline, forShaderSpriteOutline). IsOutlineRenderStateShard routes draws to a
    // SEPARATE outline framebuffer that is composited at a different point than the main scene.
    // Cloud composites (and other post-process effects) read the MAIN SCENE depth texture to mask
    // geometry — they do not see depth written into the outline framebuffer — so the outline
    // disappears behind clouds even with depth-write enabled. By NOT wrapping, the outline geometry
    // goes directly into the main scene depth buffer. Pre-flush + tagAsLateRenderForShaders (LINES
    // bucket) ensure entity depth is committed before LEQUAL runs, preserving the ring silhouette.
    // Reflective lookup retained for potential future use; no compileOnly dep.
    private static volatile boolean SHADER_OUTLINE_LOOKUP_DONE = false;
    private static volatile Method SHADER_WRAP_OUTLINE_RT = null;
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
