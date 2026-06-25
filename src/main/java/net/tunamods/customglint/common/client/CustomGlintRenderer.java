package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
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
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
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
import net.tunamods.customglint.common.CustomGlint;
import org.joml.Matrix4f;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import org.joml.Vector4f;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SequencedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
        for (RenderType rt : BY_GLINT.values())             evictRt(rt);
        for (RenderType rt : BY_ARMOR_GLINT.values())       evictRt(rt);
        for (RenderType rt : BY_HORSE_ARMOR_GLINT.values()) evictRt(rt);
        for (RenderType rt : BY_MOUNT_ARMOR_GLINT.values()) evictRt(rt);
        for (RenderType rt : BY_MOUNT_ARMOR_MASK.values())  evictRt(rt);
        BY_GLINT.clear();
        BY_ARMOR_GLINT.clear();
        BY_HORSE_ARMOR_GLINT.clear();
        BY_MOUNT_ARMOR_GLINT.clear();
        BY_MOUNT_ARMOR_MASK.clear();
        GLINT_COLORS.clear();
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
        ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(MOD_ID,"glint/" + safePath);
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
    public static final ThreadLocal<float[]> COLOR_BUF = ThreadLocal.withInitial(() -> new float[4]);

    /** Reused scroll matrix for the glint texturing shards. Each shard's setup→draw→clear is atomic
     *  per RenderType flush, so a single per-thread instance (reset via {@code translation(...)}) avoids
     *  allocating a fresh {@link Matrix4f} on every batch flush of every glinted surface. */
    private static final ThreadLocal<Matrix4f> TEX_MATRIX = ThreadLocal.withInitial(Matrix4f::new);

    /** Hard cap on each glint RenderType cache. A creative player cycling colours/designs on the wand
     *  generates a distinct key per config; without a bound these maps (and the native
     *  {@link ByteBufferBuilder} each cached RT pins in {@code fixedBuffers}) grow for the whole session.
     *  256 covers any realistic on-screen variety; the eldest entry is evicted (buffer closed) past it. */
    private static final int RT_CACHE_CAP = 256;

    /** Access-order LRU of glint RenderTypes. On eviction it closes the RT's native fixed buffer
     *  ({@link #evictRt}) and drops the paired colour holder so the two caches stay in lockstep. */
    private static final class RtCache extends java.util.LinkedHashMap<String, RenderType> {
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

    /** Per-key mutable float[4] holders; RenderType lambdas close over these references and read them each frame. */
    private static final Map<String, float[]>    GLINT_COLORS          = new HashMap<>();
    private static final Map<String, RenderType> BY_GLINT              = new RtCache();
    private static final Map<String, RenderType> BY_ARMOR_GLINT        = new RtCache();
    private static final Map<String, RenderType> BY_HORSE_ARMOR_GLINT  = new RtCache();
    private static final Map<String, RenderType> BY_MOUNT_ARMOR_GLINT  = new RtCache();
    private static final Map<ResourceLocation, RenderType> BY_MOUNT_ARMOR_MASK = new HashMap<>();

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
                                Matrix4f m = TEX_MATRIX.get().translation(-f, f1, 0.0F);
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
                fixedBufferRegistry.put(rt, new ByteBufferBuilder(rt.bufferSize()));
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
                                Matrix4f m = TEX_MATRIX.get().translation(-f, f1, 0.0F);
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
                fixedBufferRegistry.put(rt, new ByteBufferBuilder(rt.bufferSize()));
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
            if (fixedBufferRegistry != null)
                fixedBufferRegistry.put(rt, new ByteBufferBuilder(rt.bufferSize()));
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
                                Matrix4f m = TEX_MATRIX.get().translation(-f, f1, 0.0F);
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
                fixedBufferRegistry.put(rt, new ByteBufferBuilder(rt.bufferSize()));
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
                                Matrix4f m = TEX_MATRIX.get().translation(-f, 0.0F, 0.0F);
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
                fixedBufferRegistry.put(rt, new ByteBufferBuilder(rt.bufferSize()));
            return rt;
        });
        registerLiveFixedBuffer(cached);
        return cached;
    }

    public static int computeAnimatedColor(Data glint, int layerIdx) {
        Layer layer = glint.layers()[layerIdx];
        int[] colors = layer.colors();
        if (colors.length == 0) return 0xFFFFFFFF;
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

    // ── Glint stencil support (glow OUTLINE removed) ───────────────────────────

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
    // NOTE: the glow OUTLINE no longer uses the stencil buffer (it is a post-process mask +
    // composite — see GlowMaskRenderer). This slot pool survives only for the Epic Knights
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

    // The glow-outline draw API (doModelOutline / doItemOutline / doGuiItemOutline /
    // doMultiModelOutline / doModelPartsOutline), the silhouette color helpers, the RenderType
    // texture reader, and FullColorOverrideConsumer were removed along with the outline render
    // path. Glint rendering (forGlint / forArmorGlint / forHorseArmorGlint / forMountArmorGlint /
    // forEntityGlint) is unaffected.

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
