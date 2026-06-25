package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Post-process glow outline backend (1.21.1). Captures glowing items' silhouettes into an offscreen
 * mask target, then runs one fullscreen id-aware dilation composite that paints a ring over the scene.
 * Client-only; reached from {@link CustomGlintClientInit}. The approach is ported from the 26.1 branch
 * (silhouette mask + {@code glow_outline_id} composite) onto 1.21.1 primitives: {@link ShaderInstance}
 * core shaders, a {@link TextureTarget} mask, and a manual {@link Tesselator} fullscreen blit.
 *
 * <p>Milestone scope: items rendered in the world (third-person held, dropped, item frames, other
 * players) drained at {@code RenderLevelStageEvent.AFTER_WEATHER} where the live world projection /
 * modelview match what the items were drawn with. The first-person hand (a separate hand-FOV
 * projection), special BEWLR items (trident/shield), armor and entities are not wired yet.
 *
 * <p><b>No scene-depth occlusion yet</b> — the ring is constant-thickness and draws over everything
 * (visible through walls). Occlusion was removed because the composite cannot sample the main target's
 * depth while it is also writing the main target's colour: reading an attachment of the bound framebuffer
 * is undefined and returns garbage depth on some drivers / under Sodium, which made the ring thickness and
 * occlusion vary per pixel (a crackling, speckled ring). When occlusion is re-added it must sample a depth
 * COPY whose format matches the main target — the main target is DEPTH+STENCIL, so a plain-DEPTH copy
 * fails the {@code glBlitFramebuffer}. Until then the silhouette marks every texel "visible" and the
 * composite uses a fixed per-category thickness.
 */
public final class GlowOutlineRenderer extends RenderStateShard {

    private static final Logger LOGGER = LogUtils.getLogger();

    private GlowOutlineRenderer() { super("", () -> {}, () -> {}); }

    private static ResourceLocation res(String path) {
        return ResourceLocation.fromNamespaceAndPath("customglint", path);
    }

    // ── Outline categories + per-object id keys ────────────────────────────────
    // key = (category << 5) | id : top 2 bits = category, low 5 = a running 1..31 id. Stamped into the
    // silhouette's vertex-colour alpha so the composite can keep each object's ring separate and pick a
    // per-category thickness (see glow_composite.fsh THICKNESS[]).
    public static final int CAT_ENTITY = 0, CAT_ARMOR = 1, CAT_ITEM = 2, CAT_HELD_FP = 3;

    private static int glowIdCounter = 0;

    private static int nextGlowId() { glowIdCounter = (glowIdCounter % 31) + 1; return glowIdCounter; }

    public static int nextGlowKey(int category) { return (category << 5) | nextGlowId(); }

    // ── Shaders ────────────────────────────────────────────────────────────────

    private static ShaderInstance silhouetteShader;
    private static ShaderInstance compositeShader;

    /** Mod-event-bus listener; registered from {@link CustomGlintClientInit#run}.
     *  NOTE: the {@code "vertex"}/{@code "fragment"} program names INSIDE each core-shader JSON must be
     *  namespaced (e.g. {@code "customglint:glow_silhouette"}). They are parsed with
     *  {@code ResourceLocation.parse}, which defaults a bare name to the {@code minecraft} namespace and
     *  then fails to find {@code minecraft:shaders/core/glow_silhouette.vsh}. */
    public static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(), res("glow_silhouette"),
                            DefaultVertexFormat.NEW_ENTITY),
                    shader -> silhouetteShader = shader);
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(), res("glow_composite"),
                            DefaultVertexFormat.POSITION_TEX),
                    shader -> compositeShader = shader);
        } catch (Exception e) {
            LOGGER.error("[customglint] failed to register glow-outline shaders", e);
        }
    }

    // ── Offscreen mask target ──────────────────────────────────────────────────

    private static TextureTarget maskTarget;

    private static void ensureTarget(int width, int height) {
        // Colour-only: the base ring uses no depth. (Occlusion, when added later, must sample a depth
        // COPY — never the main target's live depth while the composite writes into its colour, which is
        // a feedback loop = undefined behaviour = the garbage-depth speckle/crackle.)
        if (maskTarget == null) {
            maskTarget = new TextureTarget(width, height, false, Minecraft.ON_OSX);
            maskTarget.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            maskTarget.setFilterMode(GL11.GL_NEAREST);
        } else if (maskTarget.width != width || maskTarget.height != height) {
            maskTarget.resize(width, height, Minecraft.ON_OSX);
            maskTarget.setFilterMode(GL11.GL_NEAREST);
        }
    }

    /** Released on resource reload (registered via CustomGlintRenderer.additionalReloadCleanup). */
    public static void release() {
        if (maskTarget != null) { maskTarget.destroyBuffers(); maskTarget = null; }
    }

    // ── Silhouette RenderType ──────────────────────────────────────────────────
    // NEW_ENTITY so VertexConsumer.putBulkData writes naturally; the silhouette shader reads only
    // Position/Color/UV0. Sampler0 = the block atlas (item shape via alpha). No depth test/write and no
    // blend: the alpha channel is a packed classifier, occlusion is done in-shader against the scene
    // depth (Sampler1, bound by the drain just before the flush).

    private static RenderType silhouetteRT;

    private static RenderType silhouetteRT() {
        if (silhouetteRT == null) {
            silhouetteRT = RenderType.create(
                    "customglint:glow_silhouette",
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    1024,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(() -> silhouetteShader))
                            .setTextureState(new RenderStateShard.TextureStateShard(
                                    TextureAtlas.LOCATION_BLOCKS, false, false))
                            // NO_CULL keeps the silhouette a solid union of faces (no winding-dependent
                            // holes/seam crackle). The back-face / extrusion-edge slivers are handled at
                            // capture instead, by dropping flat items' contour-edge quads (see
                            // ItemRendererMixin#cg_isEdgeQuad).
                            .setCullState(NO_CULL)
                            .setDepthTestState(NO_DEPTH_TEST)
                            .setTransparencyState(NO_TRANSPARENCY)
                            .setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(false));
        }
        return silhouetteRT;
    }

    private static final ByteBufferBuilder MASK_BUFFER = new ByteBufferBuilder(4096);
    private static final MultiBufferSource.BufferSource MASK_BUFFERS =
            MultiBufferSource.immediate(MASK_BUFFER);

    // ── Composite scissor ──────────────────────────────────────────────────────
    // The composite is a fullscreen kernel pass: unbounded, it runs the ~15x15 source search over EVERY
    // screen pixel every frame and pegs the GPU even for one small item (~700->400 fps on a single
    // third-person sword). Fix: track the silhouette's camera-relative AABB while accumulating it,
    // project that to a screen box, and scissor the composite to it so the kernel only runs near the
    // item(s). Pad must cover the ring reach (shader SEARCH=7) + the opening guard's 1px read + a margin.
    private static final int SCISSOR_PAD = 10;
    private static final float[] camBox = new float[6]; // minX,minY,minZ, maxX,maxY,maxZ (camera-relative)
    private static final List<int[]> itemBoxes = new ArrayList<>(); // per-item screen boxes [x,y,w,h]

    private static void resetCamBox() {
        camBox[0] = camBox[1] = camBox[2] = Float.POSITIVE_INFINITY;
        camBox[3] = camBox[4] = camBox[5] = Float.NEGATIVE_INFINITY;
    }

    // ── Capture queue ──────────────────────────────────────────────────────────

    private record ItemJob(List<BakedQuad> quads, PoseStack.Pose pose, int light, int color) {}

    private static final List<ItemJob> worldJobs = new ArrayList<>();

    /** Queue a world-space glowing item (third-person held / dropped / frame / other player). The pose
     *  must be a copy ({@code pose.last().copy()}); it is camera-relative and is replayed at drain. */
    public static void queueWorldItem(List<BakedQuad> quads, PoseStack.Pose pose, int light, int color) {
        worldJobs.add(new ItemJob(quads, pose, light, color));
    }

    /** Per-frame reset; called from {@code RenderFrameEvent.Pre}. */
    public static void beginFrame() {
        worldJobs.clear();
        glowIdCounter = 0;
    }

    // ── Drain ──────────────────────────────────────────────────────────────────

    /** Drain world-space item outlines. Called at {@code RenderLevelStageEvent.AFTER_WEATHER}, where the
     *  live projection / modelview are the world ones the items were drawn with. */
    public static void drainWorld() {
        if (worldJobs.isEmpty()) return;
        if (silhouetteShader == null || compositeShader == null) { worldJobs.clear(); return; }

        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        ensureTarget(main.width, main.height);

        // ── 1) accumulate each item's silhouette into the mask, tracking its OWN screen box ──
        maskTarget.clear(Minecraft.ON_OSX);
        maskTarget.bindWrite(true);
        itemBoxes.clear();
        boolean fullscreen = false;

        VertexConsumer base = MASK_BUFFERS.getBuffer(silhouetteRT());
        for (ItemJob job : worldJobs) {
            resetCamBox();
            int key = nextGlowKey(CAT_ITEM);
            int r = (job.color >> 16) & 0xFF, g = (job.color >> 8) & 0xFF, b = job.color & 0xFF;
            SilhouetteConsumer sc = new SilhouetteConsumer(base, r, g, b, key);
            for (BakedQuad quad : job.quads) {
                sc.putBulkData(job.pose, quad, 1.0f, 1.0f, 1.0f, 1.0f, job.light, OverlayTexture.NO_OVERLAY);
            }
            int[] box = computeScissor(main.width, main.height);
            if (box == null) fullscreen = true; else itemBoxes.add(box);
        }
        MASK_BUFFERS.endBatch();

        // ── 2) composite once per CLUSTER of overlapping item boxes, so many spread-out items each pay
        //        for their own small region instead of one screen-spanning union box. A near-plane
        //        crossing (box==null) forces a single fullscreen pass. Overlapping composites are
        //        idempotent: a ring pixel is written opaque (alpha 1), so a second pass just re-writes
        //        the same colour. ──
        if (fullscreen || itemBoxes.isEmpty()) {
            composite(main, null);
        } else {
            mergeBoxes(itemBoxes);
            for (int[] box : itemBoxes) composite(main, box);
        }

        worldJobs.clear();
    }

    private static void composite(RenderTarget main, int[] scissor) {
        main.bindWrite(true);

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,       GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        if (scissor != null) RenderSystem.enableScissor(scissor[0], scissor[1], scissor[2], scissor[3]);

        RenderSystem.setShader(() -> compositeShader);
        RenderSystem.setShaderTexture(0, maskTarget.getColorTextureId());

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bb.addVertex(0.0f, 0.0f, 0.0f).setUv(0.0f, 0.0f);
        bb.addVertex(1.0f, 0.0f, 0.0f).setUv(1.0f, 0.0f);
        bb.addVertex(1.0f, 1.0f, 0.0f).setUv(1.0f, 1.0f);
        bb.addVertex(0.0f, 1.0f, 0.0f).setUv(0.0f, 1.0f);
        MeshData mesh = bb.buildOrThrow();
        BufferUploader.drawWithShader(mesh);

        if (scissor != null) RenderSystem.disableScissor();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
    }

    /** Projects the accumulated camera-relative silhouette AABB (camBox) to a padded screen-space scissor
     *  box (GL bottom-left origin, pixels), matching the silhouette shader's {@code ProjMat*ModelViewMat}.
     *  Returns null — composite fullscreen — when nothing was accumulated or a box corner is at/behind the
     *  near plane (where the perspective divide is unstable). */
    private static int[] computeScissor(int width, int height) {
        if (camBox[0] > camBox[3]) return null; // nothing accumulated this drain
        Matrix4f mvp = new Matrix4f(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix());
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        Vector4f v = new Vector4f();
        for (int c = 0; c < 8; c++) {
            float x = (c & 1) == 0 ? camBox[0] : camBox[3];
            float y = (c & 2) == 0 ? camBox[1] : camBox[4];
            float z = (c & 4) == 0 ? camBox[2] : camBox[5];
            mvp.transform(v.set(x, y, z, 1.0f));
            if (v.w <= 1.0e-4f) return null; // corner at/behind the near plane -> fullscreen fallback
            float sx = (v.x / v.w * 0.5f + 0.5f) * width;
            float sy = (v.y / v.w * 0.5f + 0.5f) * height;
            minX = Math.min(minX, sx); maxX = Math.max(maxX, sx);
            minY = Math.min(minY, sy); maxY = Math.max(maxY, sy);
        }
        int x0 = Math.max(0,      (int) Math.floor(minX) - SCISSOR_PAD);
        int y0 = Math.max(0,      (int) Math.floor(minY) - SCISSOR_PAD);
        int x1 = Math.min(width,  (int) Math.ceil(maxX)  + SCISSOR_PAD);
        int y1 = Math.min(height, (int) Math.ceil(maxY)  + SCISSOR_PAD);
        if (x1 <= x0 || y1 <= y0) return null;
        return new int[]{ x0, y0, x1 - x0, y1 - y0 };
    }

    /** Merges overlapping screen boxes [x,y,w,h] in-place, so each disjoint cluster of items composites
     *  once. O(n^2) but n = number of glowing items on screen (small). */
    private static void mergeBoxes(List<int[]> boxes) {
        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (int i = 0; i < boxes.size(); i++) {
                for (int j = i + 1; j < boxes.size(); j++) {
                    if (boxesOverlap(boxes.get(i), boxes.get(j))) {
                        boxes.set(i, unionBox(boxes.get(i), boxes.get(j)));
                        boxes.remove(j);
                        changed = true;
                        break outer;
                    }
                }
            }
        }
    }

    private static boolean boxesOverlap(int[] a, int[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    private static int[] unionBox(int[] a, int[] b) {
        int x0 = Math.min(a[0], b[0]), y0 = Math.min(a[1], b[1]);
        int x1 = Math.max(a[0] + a[2], b[0] + b[2]), y1 = Math.max(a[1] + a[3], b[1] + b[3]);
        return new int[]{ x0, y0, x1 - x0, y1 - y0 };
    }

    // ── Silhouette vertex consumer ─────────────────────────────────────────────
    // Wraps the mask buffer and forces every vertex colour to (glow rgb, key) so the silhouette shader
    // receives the glow colour in rgb and the per-object key (1..127) in alpha. Position / uv / overlay /
    // light / normal pass through unchanged (the shader uses only Position, UV0 and the forced Color).

    private static final class SilhouetteConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final int r, g, b, key;

        SilhouetteConsumer(VertexConsumer delegate, int r, int g, int b, int key) {
            this.delegate = delegate;
            this.r = r; this.g = g; this.b = b; this.key = key;
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            // Track the silhouette's camera-relative bounds (post-pose) for the composite scissor.
            if (x < camBox[0]) camBox[0] = x;
            if (y < camBox[1]) camBox[1] = y;
            if (z < camBox[2]) camBox[2] = z;
            if (x > camBox[3]) camBox[3] = x;
            if (y > camBox[4]) camBox[4] = y;
            if (z > camBox[5]) camBox[5] = z;
            delegate.addVertex(x, y, z);
            return this;
        }
        @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) { delegate.setColor(r, g, b, key); return this; }
        @Override public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { delegate.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { delegate.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float nx, float ny, float nz) { delegate.setNormal(nx, ny, nz); return this; }
    }
}
