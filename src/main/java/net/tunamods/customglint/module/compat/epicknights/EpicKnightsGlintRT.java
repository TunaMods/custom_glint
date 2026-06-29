package net.tunamods.customglint.module.compat.epicknights;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * Compat-local render pipeline for Epic Knights armor decorations.
 *
 * The naive approach (just render parts with a glint render type) fails because:
 * - EQUAL depth test is invisible — EK's decoration depth and our glint depth differ by an FP
 *   epsilon despite using the same VIEW_OFFSET_Z_LAYERING. The same EQUAL works fine on
 *   {@link CustomGlint#forArmorGlint} for vanilla armor, so it's something about EK's pipeline.
 * - LEQUAL depth + no mask renders the full cuboid bounding box of each decoration ModelPart,
 *   bleeding through the transparent regions of the decoration texture — looks "huge" because
 *   the plume's cuboid is much larger than its visible feather shape.
 *
 * Solution: stencil pre-pass.
 * 1. Render parts with {@link CustomGlint#forOutline}(decorationTexture) — this is the outline
 *    shader with alpha-discard, so it skips transparent texels. Stencil op REPLACE writes 1
 *    only where the discard passes. Color/depth masks off so nothing visible is drawn.
 * 2. Render glint passes with stencil test EQUAL 1 — glint only appears on opaque decoration
 *    texels. LEQUAL depth handles occlusion (other geometry in front of the decoration).
 */
public final class EpicKnightsGlintRT extends RenderStateShard {
    private EpicKnightsGlintRT() { super("", () -> {}, () -> {}); }

    private static final Map<String, RenderType> CACHE  = new HashMap<>();
    private static final Map<String, float[]>    COLORS = new HashMap<>();

    /** Glint render type for the stencil-masked second pass. LEQUAL is safe because stencil masks to opaque pixels. */
    public static RenderType forDecorationGlint(CustomGlint.Data glint, int layerIdx, float[] frameColor, int colorIdx) {
        CustomGlint.Layer layer = glint.layers()[layerIdx];
        if (CustomGlintRenderer.getTexture(layer.design()) == null) return null;
        String key = "ek-deco|" + layer.design() + "|" + Arrays.toString(layer.colors())
                + "|" + layer.speed() + "|" + layer.patternScale() + "|" + colorIdx;
        float[] holder = COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = CACHE.computeIfAbsent(key, k -> {
            ResourceLocation tex = layer.design();
            RenderType rt = RenderType.create(
                    "customglint:ek_decoration_glint|" + k.hashCode(),
                    DefaultVertexFormat.POSITION_TEX,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_GLINT_SHADER)
                            .setTextureState(new TextureStateShard(tex, false, false) {
                                @Override public void setupRenderState() {
                                    RenderSystem.setShaderTexture(0, CustomGlintRenderer.getTexture(tex));
                                    RenderSystem.setShaderColor(holder[0], holder[1], holder[2], holder[3]);
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard("customglint:ek_decoration_glint_texturing", () -> {
                                float phase = (float) colorIdx / Math.max(1, layer.colors().length);
                                long t = (long) (Util.getMillis() * 8.0 * layer.speed());
                                float f  = (float) (t % 110000L) / 110000.0F + phase;
                                float f1 = (float) (t % 30000L)  /  30000.0F;
                                Matrix4f m = new Matrix4f().translation(-f, f1, 0.0F);
                                m.rotateZ((float) (Math.PI / 3.0));
                                m.translate(f, -f1, 0.0F);
                                m.rotateZ((float) (Math.PI / 3.0));
                                m.translate(-f, f1, 0.0F);
                                m.rotateZ((float) (Math.PI / 3.0));
                                m.translate(f, f1, 0.0F);
                                m.scale(8.0f * layer.patternScale());
                                RenderSystem.setTextureMatrix(m);
                            }, RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            if (CustomGlintRenderer.fixedBufferRegistry != null)
                CustomGlintRenderer.fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            return rt;
        });
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(cached)) live.put(cached, new BufferBuilder(cached.bufferSize()));
        // Tag for shader-pack late-render bucket so under an active pack the glint flushes after
        // the main scene depth is committed (mirrors what every forShader* RT in CustomGlintRenderer
        // does). Without this, under an active pack the deferred FullyBuffered flush orders the
        // glint draw before the decoration's depth lands → glint either fails the LEQUAL test
        // against unwritten depth (clear value) or draws before the pack's gbuffers_entities pass.
        CustomGlintRenderer.tagAsLateRenderForShaders(cached);
        return cached;
    }

    /**
     * Depth-correct stencil-write layering for EK decorations.
     *
     * The shared {@link CustomGlintRenderer#forOutlineStencilWriteItem} uses
     * STENCIL_WRITE_LAYERING_ITEM, whose {@code setupRenderState} sets
     * {@code glStencilOp(KEEP, REPLACE, REPLACE)} — i.e. dpfail=REPLACE. That writes stencil
     * even where depth fails, which on multi-plane flat decorations (horns, feathers, ears)
     * causes the back-side plane sitting behind the player head to write stencil, and pass 2's
     * glint then bleeds through the head.
     *
     * Here we use dpfail=KEEP, dppass=REPLACE — strict depth-correct write. The armor variant
     * needs dpfail=REPLACE to compensate for polygon-offset slope variance under shader mods,
     * but EK decorations are drawn with entityCutoutNoCull (no polygon offset) so depth is
     * reliable and dppass-only works correctly.
     */
    private static final RenderStateShard.LayeringStateShard EK_STENCIL_WRITE_LAYERING =
            new RenderStateShard.LayeringStateShard("custom_glint_ek_stencil_write",
                () -> {
                    Minecraft.getInstance().getMainRenderTarget().enableStencil();
                    GL11.glEnable(GL11.GL_STENCIL_TEST);
                    GL11.glStencilMask(0xFF);
                    GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
                },
                () -> {
                    GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
                    GL11.glDisable(GL11.GL_STENCIL_TEST);
                });

    private static final RenderStateShard.WriteMaskStateShard EK_NO_WRITE =
            new RenderStateShard.WriteMaskStateShard(false, false);

    // ── Per-slot infrastructure for FullyBuffered (Oculus-no-pack) path ────────────────────
    //
    // Under Oculus's FullyBufferedMultiBufferSource, endBatch(rt) defers the actual draw to a
    // later graph-ordered flush. Any manual glStencilOp/glStencilFunc/glColorMask calls between
    // pass A's endBatch and pass B's endBatch execute immediately on the CPU but DON'T apply to
    // the deferred draws — by the time the graph flushes A and B, GL state is whatever was set
    // last. Result: stencil disabled, both glint and outline silently invisible.
    //
    // Fix: bake every stencil state change into the RT's layering shard (setupRenderState fires
    // at deferred-flush time, just before the draw). No manual GL state between passes.
    //
    // Slot isolation: each applyDecorationGlint call allocates a unique stencil slot V via
    // CustomGlintRenderer.nextStencilSlot(). WRITE stamps V at the decoration's silhouette;
    // GLINT tests EQUAL V (this decoration only); OUTLINE tests EQUAL 0 (empty space only).
    // Multiple decorations per frame don't cross-contaminate.

    /** Per-slot WRITE RT cache, keyed by (slot, tex). Each entry's TextureStateShard closes
     *  over a stable texture, so multiple textures in one slot don't clobber each other's
     *  binding under FullyBuffered's deferred flush. */
    private static final Map<String, RenderType> SLOT_WRITE_CACHE = new HashMap<>();

    /** Depth-correct per-slot WRITE shard. dpfail=KEEP (no back-plane bleed). Honors the once-
     *  per-frame {@code pendingFrameStencilClear} gate from CustomGlintRenderer so the stencil
     *  buffer is cleared at the first WRITE of the frame (any glint pipeline can do it). */
    private static RenderStateShard.LayeringStateShard ekStencilWriteLayeringSlot(final int v) {
        return new RenderStateShard.LayeringStateShard(
                "custom_glint_ek_stencil_write_slot_v" + v,
                () -> {
                    Minecraft.getInstance().getMainRenderTarget().enableStencil();
                    GL11.glEnable(GL11.GL_STENCIL_TEST);
                    GL11.glStencilMask(0xFF);
                    if (CustomGlintRenderer.pendingFrameStencilClear) {
                        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
                        CustomGlintRenderer.pendingFrameStencilClear = false;
                    }
                    GL11.glStencilFunc(GL11.GL_ALWAYS, v, 0xFF);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
                },
                () -> {
                    GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
                    GL11.glDisable(GL11.GL_STENCIL_TEST);
                });
    }

    /** Per-slot WRITE RT factory. Cached per (slot, tex) so calling with different textures in
     *  the same slot doesn't clobber a shared holder. Crowns need a stencil write through BOTH
     *  base and overlay textures (shape lives in overlay); a mutable per-slot holder would
     *  collapse the second call's texture onto both deferred draws under FullyBuffered. */
    public static RenderType forDecorationStencilWriteSlot(int v, ResourceLocation texture) {
        String key = v + "|" + texture;
        RenderType rt = SLOT_WRITE_CACHE.computeIfAbsent(key, k -> {
            RenderType created = RenderType.create(
                    "customglint:ek_deco_stencil_write_slot|" + k.hashCode(),
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_OUTLINE_SHADER)
                            .setTextureState(new TextureStateShard(texture, false, false))
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setOutputState(CustomGlintRenderer.FORCE_MAIN_TARGET)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setWriteMaskState(EK_NO_WRITE)
                            .setLayeringState(ekStencilWriteLayeringSlot(v))
                            .createCompositeState(false));
            if (CustomGlintRenderer.fixedBufferRegistry != null)
                CustomGlintRenderer.fixedBufferRegistry.put(created, new BufferBuilder(created.bufferSize()));
            return created;
        });
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(rt))
            live.put(rt, new BufferBuilder(rt.bufferSize()));
        return rt;
    }

    /** Per-slot GLINT layering: VIEW_OFFSET_Z polygon offset + stencil EQUAL v. */
    private static RenderStateShard.LayeringStateShard ekGlintStencilTestLayeringSlot(final int v) {
        return new RenderStateShard.LayeringStateShard(
                "custom_glint_ek_glint_stencil_test_slot_v" + v,
                () -> {
                    GL11.glEnable(GL11.GL_STENCIL_TEST);
                    GL11.glStencilMask(0xFF);
                    GL11.glStencilFunc(GL11.GL_EQUAL, v, 0xFF);
                    GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
                    GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
                    GL11.glPolygonOffset(-1.0f, -10.0f);
                },
                () -> {
                    GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
                    GL11.glDisable(GL11.GL_STENCIL_TEST);
                    GL11.glPolygonOffset(0.0f, 0.0f);
                    GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
                });
    }

    /** Per-slot glint RT cache, keyed by (slot, design, colors, speed, scale, colorIdx). */
    private static final Map<String, RenderType> SLOT_GLINT_CACHE = new HashMap<>();
    private static final Map<String, float[]> SLOT_GLINT_COLORS = new HashMap<>();

    public static RenderType forDecorationGlintSlot(int slot, CustomGlint.Data glint, int layerIdx,
            float[] frameColor, int colorIdx) {
        CustomGlint.Layer layer = glint.layers()[layerIdx];
        if (CustomGlintRenderer.getTexture(layer.design()) == null) return null;
        String key = "ek-deco-slot|" + slot + "|" + layer.design() + "|" + Arrays.toString(layer.colors())
                + "|" + layer.speed() + "|" + layer.patternScale() + "|" + colorIdx;
        float[] holder = SLOT_GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = SLOT_GLINT_CACHE.computeIfAbsent(key, k -> {
            ResourceLocation tex = layer.design();
            RenderType rt = RenderType.create(
                    "customglint:ek_deco_glint_slot|" + k.hashCode(),
                    DefaultVertexFormat.POSITION_TEX,
                    VertexFormat.Mode.QUADS,
                    256, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_GLINT_SHADER)
                            .setTextureState(new TextureStateShard(tex, false, false) {
                                @Override public void setupRenderState() {
                                    RenderSystem.setShaderTexture(0, CustomGlintRenderer.getTexture(tex));
                                    RenderSystem.setShaderColor(holder[0], holder[1], holder[2], holder[3]);
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setLayeringState(ekGlintStencilTestLayeringSlot(slot))
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard("customglint:ek_deco_glint_slot_tx", () -> {
                                float phase = (float) colorIdx / Math.max(1, layer.colors().length);
                                long t = (long) (Util.getMillis() * 8.0 * layer.speed());
                                float f  = (float) (t % 110000L) / 110000.0F + phase;
                                float f1 = (float) (t % 30000L)  /  30000.0F;
                                Matrix4f m = new Matrix4f().translation(-f, f1, 0.0F);
                                m.rotateZ((float) (Math.PI / 3.0));
                                m.translate(f, -f1, 0.0F);
                                m.rotateZ((float) (Math.PI / 3.0));
                                m.translate(-f, f1, 0.0F);
                                m.rotateZ((float) (Math.PI / 3.0));
                                m.translate(f, f1, 0.0F);
                                m.scale(8.0f * layer.patternScale());
                                RenderSystem.setTextureMatrix(m);
                            }, RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            if (CustomGlintRenderer.fixedBufferRegistry != null)
                CustomGlintRenderer.fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            return rt;
        });
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(cached)) live.put(cached, new BufferBuilder(cached.bufferSize()));
        return cached;
    }

    private static final Map<ResourceLocation, RenderType> EK_STENCIL_WRITE_CACHE = new HashMap<>();
    private static RenderType forDecorationStencilWrite(ResourceLocation tex) {
        return EK_STENCIL_WRITE_CACHE.computeIfAbsent(tex, t -> {
            RenderType rt = RenderType.create(
                    "customglint:ek_deco_stencil_write",
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_OUTLINE_SHADER)
                            .setTextureState(new TextureStateShard(t, false, false))
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setOutputState(CustomGlintRenderer.FORCE_MAIN_TARGET)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setWriteMaskState(EK_NO_WRITE)
                            .setLayeringState(EK_STENCIL_WRITE_LAYERING)
                            .createCompositeState(false));
            if (CustomGlintRenderer.fixedBufferRegistry != null)
                CustomGlintRenderer.fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            return rt;
        });
    }

    /**
     * Derive the sibling texture for an EK decoration: base ↔ overlay.
     *
     * EK splits decoration art across two files for dyeable items:
     *   base = textures/.../foo.png         (dye-tinted layer)
     *   overlay = textures/.../foo_overlay.png  (un-tinted detail)
     *
     * For most decorations the SHAPE lives in the base (plumes, surcoats); for crowns it's
     * inverted — base holds only the 16 gem pixels, overlay holds the 96 band pixels. Stencil
     * write must therefore union both texture's opaque pixels so the glint mask covers the
     * complete decoration silhouette regardless of layout. Same for the outline halo.
     *
     * Returns null if the path doesn't match the expected suffix shape, or for non-dyeable
     * decorations whose sibling file doesn't exist.
     */
    private static ResourceLocation siblingTexture(ResourceLocation tex) {
        String path = tex.getPath();
        ResourceLocation sibling;
        if (path.endsWith("_overlay.png")) {
            sibling = new ResourceLocation(tex.getNamespace(),
                    path.substring(0, path.length() - "_overlay.png".length()) + ".png");
        } else if (path.endsWith(".png")) {
            sibling = new ResourceLocation(tex.getNamespace(),
                    path.substring(0, path.length() - ".png".length()) + "_overlay.png");
        } else {
            return null;
        }
        // Existence check avoids triggering the missing-texture pink fallback (which has
        // full alpha → would write stencil over the entire decoration cuboid).
        return Minecraft.getInstance().getResourceManager().getResource(sibling).isPresent() ? sibling : null;
    }


    /**
     * Entry point. Dispatches to one of three implementations based on shader-mod state.
     * Each path has different constraints:
     * <ul>
     *   <li><b>NO SHADERS</b>: main render target is vanilla's, stencil buffer is reliable,
     *       call order matches GL submission order. Stencil-mask path works.</li>
     *   <li><b>SHADERS OFF</b> (Oculus loaded, no pack): main render target swapped by Oculus's
     *       MixinRenderTarget; stencil semantics differ. Forward-pass outline path is required
     *       (no stencil), same gate as {@code doModelOutline} uses.</li>
     *   <li><b>SHADERS ON</b> (active pack): FullyBuffered batching reorders RT flushes by
     *       TransparencyType; vanilla glow outline pipeline (OutlineBufferSource) is the only
     *       reliable route the shader mod preserves.</li>
     * </ul>
     */
    public static void applyDecorationGlint(PoseStack pose, MultiBufferSource buffer, int light,
            int overlay, ModelPart[] parts, ResourceLocation decorationTexture, CustomGlint.Data glint,
            boolean glowing, ItemStack stack) {
        if (CustomGlintRenderer.isShaderPackActive()) {
            applyDecorationGlint_shadersOn(pose, buffer, light, overlay, parts, decorationTexture, glint, glowing, stack);
        } else if (CustomGlintRenderer.isShaderModInstalled()) {
            applyDecorationGlint_shadersOff(pose, buffer, light, overlay, parts, decorationTexture, glint, glowing, stack);
        } else {
            applyDecorationGlint_noShaders(pose, buffer, light, overlay, parts, decorationTexture, glint, glowing, stack);
        }
    }

    /**
     * SHADERS OFF path (Oculus loaded, no pack active). FullyBufferedMultiBufferSource defers
     * endBatch(rt) draws to a later graph-ordered flush — manual glStencilXxx calls between
     * passes don't apply. Solution: bake all stencil state into per-slot RT layering shards.
     *
     * Each call allocates a unique stencil slot V. WRITE stamps V; GLINT tests EQUAL V (this
     * decoration's own silhouette); OUTLINE halo tests EQUAL 0 (open space). Multiple decorations
     * per frame use different slots — no cross-contamination.
     *
     * The dependency graph adds edges based on bs.getBuffer() insertion order: calling WRITE→
     * GLINT→OUTLINE registers WRITE-before-GLINT-before-OUTLINE for the eventual flush.
     */
    private static void applyDecorationGlint_shadersOff(PoseStack pose, MultiBufferSource buffer, int light,
            int overlay, ModelPart[] parts, ResourceLocation decorationTexture, CustomGlint.Data glint,
            boolean glowing, ItemStack stack) {
        // After an Oculus/Iris pack toggle (activate then deactivate), the entity render dispatcher
        // hands layers a synthetic lambda MultiBufferSource that does NOT extend BufferSource. The old
        // `instanceof BufferSource` check then bailed here and the decoration glint silently vanished
        // until a world reload rebuilt the dispatcher with a real BufferSource. Fall back to the global
        // bufferSource (the canonical singleton that wrapper delegates to under the hood) so getBuffer
        // and endBatch hit the same underlying builders — same fix as CustomGlintRenderer.flushRT.
        MultiBufferSource.BufferSource bs = buffer instanceof MultiBufferSource.BufferSource direct
                ? direct : Minecraft.getInstance().renderBuffers().bufferSource();
        if (bs == null) return;
        if (CustomGlintRenderer.isInShadowPass()) return;

        // Full pre-flush — drains EK's pending decoration verts and any other queued state so
        // our slot-stamped writes don't compete with unflushed earlier work in the graph order.
        // bs.endLastBatch() (used by _noShaders) only flushes the lastState RT; under FullyBuffered
        // we need a full drain to avoid mid-frame state mixing.
        bs.endBatch();

        int slot = CustomGlintRenderer.nextStencilSlot();

        // ── Pass 1: per-slot stencil write ─────────────────────────────────────────────────
        // forDecorationStencilWriteSlot's shard handles enableStencil, pendingFrameStencilClear,
        // and stamps slot V where alpha-pass AND depth-pass. No manual GL here.
        // Union over base + sibling overlay: crowns put their band shape in the overlay and only
        // the gems in the base. Without writing through both, stencil V only covers the gems and
        // the outline's NOTEQUAL-V test fills the entire crown band.
        ResourceLocation siblingWrite = siblingTexture(decorationTexture);
        ResourceLocation[] writeTextures = siblingWrite != null
                ? new ResourceLocation[]{decorationTexture, siblingWrite}
                : new ResourceLocation[]{decorationTexture};
        for (ResourceLocation tex : writeTextures) {
            RenderType writeRT = forDecorationStencilWriteSlot(slot, tex);
            VertexConsumer writeVC = bs.getBuffer(writeRT);
            for (ModelPart part : parts) {
                part.render(pose, writeVC, light, OverlayTexture.NO_OVERLAY, 1, 1, 1, 0);
            }
            bs.endBatch(writeRT);
        }

        // ── Pass 2: glint where stencil == slot ────────────────────────────────────────────
        // forDecorationGlintSlot's shard does stencil EQUAL slot + polygon offset. No manual GL.
        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();
        List<VertexConsumer> glintVCs = new ArrayList<>();
        List<RenderType> glintRTs = new ArrayList<>();
        for (int li = 0; li < layers.length; li++) {
            int[] colors = layers[li].colors();
            if (layers[li].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    float a = ((colors[i] >> 24) & 0xFF) / 255.0f;
                    buf[0] = ((colors[i] >> 16) & 0xFF) / 255.0f * a;
                    buf[1] = ((colors[i] >>  8) & 0xFF) / 255.0f * a;
                    buf[2] = ( colors[i]        & 0xFF) / 255.0f * a;
                    buf[3] = 1.0f;
                    RenderType rt = forDecorationGlintSlot(slot, glint, li, buf, i);
                    if (rt != null) { glintRTs.add(rt); glintVCs.add(bs.getBuffer(rt)); }
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, li);
                float a = ((color >> 24) & 0xFF) / 255.0f;
                buf[0] = ((color >> 16) & 0xFF) / 255.0f * a;
                buf[1] = ((color >>  8) & 0xFF) / 255.0f * a;
                buf[2] = ( color        & 0xFF) / 255.0f * a;
                buf[3] = 1.0f;
                RenderType rt = forDecorationGlintSlot(slot, glint, li, buf, 0);
                if (rt != null) { glintRTs.add(rt); glintVCs.add(bs.getBuffer(rt)); }
            }
        }
        if (!glintVCs.isEmpty()) {
            VertexConsumer combined = glintVCs.size() == 1 ? glintVCs.get(0)
                    : VertexMultiConsumer.create(glintVCs.toArray(new VertexConsumer[0]));
            for (ModelPart part : parts) {
                part.render(pose, combined, light, overlay, 1.0f, 1.0f, 1.0f, 1.0f);
            }
            for (RenderType rt : glintRTs) bs.endBatch(rt);
        }

    }

    /**
     * SHADERS ON path. An active shader pack runs FullyBufferedMultiBufferSource with
     * TransparencyType-based RT reordering, so any stencil silhouette we stamp is overwritten /
     * reordered by pack-injected passes before our glint/outline test fires — the slot-based
     * stencil path used in {@link #applyDecorationGlint_shadersOff} (which works because Oculus-
     * no-pack still preserves WRITE→TEST insertion edges) goes completely invisible here.
     *
     * Strategy: mirror the armor shader-on branch in {@link CustomGlintRenderer#doModelOutline}.
     * Forward-pass dilated outline via {@link CustomGlintRenderer#forShaderArmorOutlineTextured}
     * — the alpha-mask texture variant of {@code ENTITY_CUTOUT_NO_CULL_SHADER} that universally
     * maps to {@code gbuffers_entities} under every shader pack, so the ring is actually
     * visible. No stencil dependency.
     *
     * Glint pass: existing {@link #forDecorationGlint} RT (LEQUAL depth + VIEW_OFFSET_Z_LAYERING
     * matching EK's entityCutoutNoCull pipeline). Under shaders we lose the stencil mask, so on
     * flat decorations whose visible shape lives in texture alpha (plumes, surcoats) the glint
     * paints across the full cuboid quad. Documented limitation — the alternative (no glint)
     * is worse. 3D decorations (crowns, horns) come out clean because their geometry IS the
     * visible silhouette.
     *
     * Per-decoration ModelParts are children of the HumanoidModel head bone, so they're already
     * posed at head-position. Scaling 1.04× within the current pose expands outward from the
     * head bone — same natural pivot armor uses without needing an AABB pre-pass.
     */
    private static void applyDecorationGlint_shadersOn(PoseStack pose, MultiBufferSource buffer, int light,
            int overlay, ModelPart[] parts, ResourceLocation decorationTexture, CustomGlint.Data glint,
            boolean glowing, ItemStack stack) {
        if (CustomGlintRenderer.isInShadowPass()) return;

        // Sibling overlay union — dyeable decorations have shape split across base+overlay
        // (crowns: shape in overlay, base = gems only; surcoats: opposite). Compute once.
        ResourceLocation sibling = siblingTexture(decorationTexture);

        // ── Glint: depth-prewrite self-mask ─────────────────────────────────────────
        // forDecorationDepthPrewrite uses RENDERTYPE_OUTLINE_SHADER which alpha-discards at 0
        // in fragment code (independent of shader pack settings), writing depth ONLY at opaque
        // texels — no polygon offset, raw projected depth. forDecorationGlintShader then tests
        // EQUAL against that raw depth (also no offset), so it draws only at the same opaque
        // texels. Self-mask without stencil. Both RTs are late-tagged so they flush after EK's
        // own decoration draw under FullyBuffered. Union over base + sibling so the prewrite
        // covers the complete silhouette regardless of which texture holds the shape (crowns
        // put the band in overlay, base = gems only; surcoats are the opposite).
        ResourceLocation[] depthTextures = sibling != null
                ? new ResourceLocation[]{decorationTexture, sibling}
                : new ResourceLocation[]{decorationTexture};
        for (ResourceLocation tex : depthTextures) {
            RenderType prewriteRT = forDecorationDepthPrewrite(tex);
            VertexConsumer prewriteVC = buffer.getBuffer(prewriteRT);
            for (ModelPart part : parts) {
                part.render(pose, prewriteVC, light, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);
            }
        }

        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();
        List<VertexConsumer> glintVCs = new ArrayList<>();
        for (int li = 0; li < layers.length; li++) {
            int[] colors = layers[li].colors();
            if (layers[li].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    float a = ((colors[i] >> 24) & 0xFF) / 255.0f;
                    buf[0] = ((colors[i] >> 16) & 0xFF) / 255.0f * a;
                    buf[1] = ((colors[i] >>  8) & 0xFF) / 255.0f * a;
                    buf[2] = ( colors[i]        & 0xFF) / 255.0f * a;
                    buf[3] = 1.0f;
                    RenderType rt = forDecorationGlintShader(glint, li, buf, i);
                    if (rt != null) glintVCs.add(buffer.getBuffer(rt));
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, li);
                float a = ((color >> 24) & 0xFF) / 255.0f;
                buf[0] = ((color >> 16) & 0xFF) / 255.0f * a;
                buf[1] = ((color >>  8) & 0xFF) / 255.0f * a;
                buf[2] = ( color        & 0xFF) / 255.0f * a;
                buf[3] = 1.0f;
                RenderType rt = forDecorationGlintShader(glint, li, buf, 0);
                if (rt != null) glintVCs.add(buffer.getBuffer(rt));
            }
        }
        if (!glintVCs.isEmpty()) {
            VertexConsumer combined = glintVCs.size() == 1 ? glintVCs.get(0)
                    : VertexMultiConsumer.create(glintVCs.toArray(new VertexConsumer[0]));
            for (ModelPart part : parts) {
                part.render(pose, combined, light, overlay, 1.0f, 1.0f, 1.0f, 1.0f);
            }
        }
    }

    /**
     * Depth pre-pass RT for the shader-pack glint path. RENDERTYPE_OUTLINE_SHADER does its own
     * alpha-discard (alpha == 0) in fragment code — independent of shader pack settings —
     * so depth gets written ONLY at opaque decoration texels. No polygon offset (raw projected
     * depth), so the subsequent EQUAL glint pass (also no offset) compares raw depths and
     * matches exactly: no FP epsilon mismatch with EK's polygon-offset depth.
     *
     * Color write disabled — we only need this to land in the depth buffer. Tagged late so
     * it flushes after EK's color/depth land (we want OUR depth to be the most recent value
     * at each opaque texel, so the glint EQUAL sees a value we control).
     */
    private static final Map<ResourceLocation, RenderType> DEPTH_PREWRITE_CACHE = new HashMap<>();
    private static RenderType forDecorationDepthPrewrite(ResourceLocation decoTex) {
        return DEPTH_PREWRITE_CACHE.computeIfAbsent(decoTex, tex -> {
            RenderType rt = RenderType.create(
                    "customglint:ek_deco_depth_prewrite",
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_OUTLINE_SHADER)
                            .setTextureState(new TextureStateShard(tex, false, false))
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            // DEPTH_ONLY_WRITE writes only depth (no color).
                            .setWriteMaskState(DEPTH_WRITE)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setOutputState(CustomGlintRenderer.FORCE_MAIN_TARGET)
                            .createCompositeState(false));
            if (CustomGlintRenderer.fixedBufferRegistry != null)
                CustomGlintRenderer.fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
            if (live != null && !live.containsKey(rt)) live.put(rt, new BufferBuilder(rt.bufferSize()));
            CustomGlintRenderer.tagAsLateRenderForShaders(rt);
            return rt;
        });
    }

    /**
     * Shader-pack-only glint RT: EQUAL depth + VIEW_OFFSET_Z_LAYERING (matches EK's
     * armorCutoutNoCull polygon offset exactly). Self-masks to opaque decoration texels —
     * EK's armorCutoutNoCull discards transparent fragments (alpha &lt; 0.1), so depth is
     * written ONLY where the decoration has visible pixels. EQUAL passes only at those
     * exact texels, transparent regions don't draw → glint follows the decoration shape
     * without needing a stencil mask.
     *
     * Why we can't just switch {@link #forDecorationGlint} to EQUAL: that RT is used by the
     * no-shaders and shaders-off paths which rely on its LEQUAL behavior with stencil masking
     * for cases where the depth-match approach was historically observed to fail (FP epsilon,
     * documented in this file's javadoc). Under shader packs we don't have a stencil option,
     * so depth-self-mask is the only path — and empirically it works here.
     *
     * Tagged for shader-pack late render (LINES bucket) so this flushes AFTER EK's decoration
     * depth lands. Otherwise FullyBuffered ordering could schedule the glint first and EQUAL
     * compares against clear-depth → invisible.
     */
    private static final Map<String, RenderType> SHADER_GLINT_CACHE = new HashMap<>();
    private static final Map<String, float[]> SHADER_GLINT_COLORS = new HashMap<>();
    public static RenderType forDecorationGlintShader(CustomGlint.Data glint, int layerIdx,
            float[] frameColor, int colorIdx) {
        CustomGlint.Layer layer = glint.layers()[layerIdx];
        if (CustomGlintRenderer.getTexture(layer.design()) == null) return null;
        String key = "ek-deco-sh|" + layer.design() + "|" + Arrays.toString(layer.colors())
                + "|" + layer.speed() + "|" + layer.patternScale() + "|" + colorIdx;
        float[] holder = SHADER_GLINT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = SHADER_GLINT_CACHE.computeIfAbsent(key, k -> {
            ResourceLocation tex = layer.design();
            RenderType rt = RenderType.create(
                    "customglint:ek_decoration_glint_sh|" + k.hashCode(),
                    DefaultVertexFormat.POSITION_TEX,
                    VertexFormat.Mode.QUADS,
                    256, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_GLINT_SHADER)
                            .setTextureState(new TextureStateShard(tex, false, false) {
                                @Override public void setupRenderState() {
                                    RenderSystem.setShaderTexture(0, CustomGlintRenderer.getTexture(tex));
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
                            // No layering — match the depth pre-pass which uses no polygon
                            // offset, so EQUAL compares raw projected depths and matches.
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            .setTexturingState(new TexturingStateShard("customglint:ek_decoration_glint_sh_texturing", () -> {
                                float phase = (float) colorIdx / Math.max(1, layer.colors().length);
                                long t = (long) (Util.getMillis() * 8.0 * layer.speed());
                                float f  = (float) (t % 110000L) / 110000.0F + phase;
                                float f1 = (float) (t % 30000L)  /  30000.0F;
                                Matrix4f m = new Matrix4f().translation(-f, f1, 0.0F);
                                m.rotateZ((float) (Math.PI / 3.0));
                                m.translate(f, -f1, 0.0F);
                                m.rotateZ((float) (Math.PI / 3.0));
                                m.translate(-f, f1, 0.0F);
                                m.rotateZ((float) (Math.PI / 3.0));
                                m.translate(f, f1, 0.0F);
                                m.scale(8.0f * layer.patternScale());
                                RenderSystem.setTextureMatrix(m);
                            }, RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            if (CustomGlintRenderer.fixedBufferRegistry != null)
                CustomGlintRenderer.fixedBufferRegistry.put(rt, new BufferBuilder(rt.bufferSize()));
            CustomGlintRenderer.tagAsLateRenderForShaders(rt);
            return rt;
        });
        SortedMap<RenderType, BufferBuilder> live = Minecraft.getInstance().renderBuffers().fixedBuffers;
        if (live != null && !live.containsKey(cached)) live.put(cached, new BufferBuilder(cached.bufferSize()));
        return cached;
    }

    /**
     * NO SHADERS path. Delegates to the slot-based {@link #applyDecorationGlint_shadersOff}
     * pipeline: per-call stencil slot V via {@link CustomGlintRenderer#nextStencilSlot()},
     * per-(slot, tex) cached WRITE RT with a stable TextureStateShard closure so base AND
     * overlay textures both contribute to the stencil silhouette, all stencil state baked
     * into RT layering shards.
     *
     * Why delegate rather than keep the legacy manual-GL path: the legacy implementation
     * union-wrote base+sibling overlay through a single per-tex shared RT cache, and the
     * second (overlay) iteration silently no-opped — so crown-style decorations whose
     * SHAPE lives in the overlay (base = gems only) had glint only on the gems and the
     * band rendered as a stenciled halo around them. Documented at the time as a
     * stylistic choice but actually a missed coverage bug. The slot-based path's per-
     * (slot, tex) closure binds each texture's GL handle correctly at draw time so both
     * iterations land.
     *
     * BufferSource semantics are compatible: vanilla {@code BufferSource.endBatch(rt)}
     * draws synchronously, FullyBuffered defers — either way each RT's layering shard
     * setupRenderState fires at the right moment because it's baked into the RT.
     */
    private static void applyDecorationGlint_noShaders(PoseStack pose, MultiBufferSource buffer, int light,
            int overlay, ModelPart[] parts, ResourceLocation decorationTexture, CustomGlint.Data glint,
            boolean glowing, ItemStack stack) {
        applyDecorationGlint_shadersOff(pose, buffer, light, overlay, parts, decorationTexture, glint, glowing, stack);
    }

}
