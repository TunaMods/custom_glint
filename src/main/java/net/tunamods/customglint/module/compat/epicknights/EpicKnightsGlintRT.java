package net.tunamods.customglint.module.compat.epicknights;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone-only compat: Epic Knights (magistuarmory) renders armor decorations (plumes, surcoats, crowns)
 * through its own {@code ArmorDecorationLayer}, past the base-armor mixin's reach, so the glint for those is
 * drawn here. Glow outlines are unaffected; those come from the generic post-process silhouette
 * ({@link #captureDecorationOutline}).
 *
 * <p>Two paths, chosen by whether a shader pack is active. A decoration model is not its own shape (the mesh is a
 * cuboid; only the texture's alpha says which texels are the plume, band, or cloth), so both paths clip the glint
 * to the decoration's opaque texels and differ only in how.
 *
 * <p>Off pack: a per-fragment texture cutout, the same technique {@link CustomGlintRenderer#forMountArmorGlint
 * horse armor} uses. The custom {@code glint_cutout_deco} shader samples the decoration texture on Sampler1 /
 * Sampler2 and discards where it is transparent, in the same draw as the glint. LEQUAL plus the camera-ward
 * {@code gl_Position} bias in that shader's vertex stage let it sit proud of the coplanar decoration mesh, and
 * NEW_ENTITY keeps it on Sodium's {@code renderCuboid} path alongside the decoration (see {@code forMountArmorGlint}
 * and the per-buffer draw in {@link #applyDecorationGlint_cutout} for the Sodium rationale). This path replaced the
 * old stencil mask, so the mod no longer calls {@code enableStencil} anywhere.
 *
 * <p>On pack: defer instead of drawing in phase. An active pack hijacks the custom cutout program (every fragment
 * discards, so the glint vanishes), and a vanilla-shader depth self-mask flickers under Iris's re-sorted depth. So
 * capture the decoration parts and queue them for the post-Iris textured-glint overlay drain, the same path
 * horse/mount armor uses under a pack ({@link EntityGlintRender#captureGlintModelParts}): it re-renders after the
 * pack finishes and composites back, which is stable. Union over base + sibling so a split-shape decoration is
 * fully covered.
 *
 * <p>Union: dyeable decorations split their shape across a dye-tinted base and an un-tinted overlay file (crowns
 * keep the band in the overlay and only the gems in the base; surcoats are the opposite), so both paths cover the
 * union of the two textures.
 */
public final class EpicKnightsGlintRT extends RenderStateShard {
    private EpicKnightsGlintRT() { super("", () -> {}, () -> {}); }

    // Is Sodium's entity render path on the classpath this run? Gates the per-buffer glint draw in the off-pack
    // cutout, exactly as HorseArmorLayerMixin does for the horse cutout.
    private static final boolean SODIUM_PRESENT =
            cg_classPresent("net.caffeinemc.mods.sodium.client.render.immediate.model.EntityRenderer");

    private static boolean cg_classPresent(String fqn) {
        try { Class.forName(fqn, false, EpicKnightsGlintRT.class.getClassLoader()); return true; }
        catch (Throwable t) { return false; }
    }

    /** Two-mask decoration cutout program (off-pack); loaded in core alongside {@code glint_cutout}. */
    private static final RenderStateShard.ShaderStateShard DECO_CUTOUT_SHADER_SHARD =
            new RenderStateShard.ShaderStateShard(CustomGlintRenderer::glintCutoutDecoShader);

    /** Bound on the off-pack cutout RT cache. Design / colours / speed / scale are user-controllable and each
     *  distinct config is a distinct entry; a fixed decoration also spans one entry per texture it draws with.
     *  Without a cap the map grows until resource reload, each entry pinning a native ByteBufferBuilder in
     *  fixedBuffers, exactly what CustomGlintRenderer's RtCache (cap 256) prevents for the core caches. The cap
     *  sits well above the distinct configs on screen in one frame, so the LRU never evicts an entry still needed
     *  this frame. */
    private static final int EK_CACHE_CAP = 512;

    /** Access-order LRU mirroring {@link CustomGlintRenderer}'s RtCache: past {@link #EK_CACHE_CAP} it evicts the
     *  eldest RT (closing its native fixed buffer via {@link CustomGlintRenderer#evictRt}) and drops its colour
     *  holder so the two maps stay in lockstep. */
    private static final class EkRtCache extends LinkedHashMap<String, RenderType> {
        private final Map<String, float[]> colors;
        EkRtCache(Map<String, float[]> colors) { super(64, 0.75f, true); this.colors = colors; }
        @Override protected boolean removeEldestEntry(Map.Entry<String, RenderType> e) {
            if (size() > EK_CACHE_CAP) {
                CustomGlintRenderer.evictRt(e.getValue());
                if (colors != null) colors.remove(e.getKey());
                return true;
            }
            return false;
        }
    }

    private static final Map<String, float[]> DECO_CUTOUT_COLORS = new HashMap<>();
    private static final Map<String, RenderType> DECO_CUTOUT_CACHE = new EkRtCache(DECO_CUTOUT_COLORS);

    /**
     * Off-pack decoration cutout glint RT. Sampler0 is the scrolled design; Sampler1 / Sampler2 are the decoration
     * base + overlay textures whose alpha union masks the glint to the decoration silhouette (see the class header).
     * NEW_ENTITY + LEQUAL + NO_LAYERING with the depth bias in the vertex program, exactly like
     * {@link CustomGlintRenderer#forMountArmorGlint}; only the texturing (EK's own scrolling glint drift) and the
     * second mask differ.
     */
    public static RenderType forDecorationGlintCutout(CustomGlint.Data glint, int layerIdx, float[] frameColor,
            int colorIdx, ResourceLocation baseTex, ResourceLocation overlayTex) {
        if (CustomGlintRenderer.glintCutoutDecoShader() == null) return null;
        CustomGlint.Layer layer = glint.layers()[layerIdx];
        final ResourceLocation design = layer.design();
        if (CustomGlintRenderer.getTexture(design) == null) return null;
        // Sampler2 = the overlay for dyeable decorations, else the base itself (union collapses to base).
        final ResourceLocation mask2 = overlayTex != null ? overlayTex : baseTex;
        // Key MUST include layerIdx: two visually identical layers would otherwise resolve to one cached RT and,
        // added twice to the same VertexMultiConsumer, trip Mixin's "Duplicate delegates" guard.
        String key = "ek-deco-cut|" + design + "|" + Arrays.toString(layer.colors()) + "|" + layer.speed()
                + "|" + layer.patternScale() + "|" + layerIdx + "|" + colorIdx + "|" + baseTex + "|" + mask2;
        float[] holder = DECO_CUTOUT_COLORS.computeIfAbsent(key, k -> new float[4]);
        System.arraycopy(frameColor, 0, holder, 0, 4);
        RenderType cached = DECO_CUTOUT_CACHE.computeIfAbsent(key, k -> {
            RenderType rt = RenderType.create(
                    "customglint:ek_deco_glint_cutout|" + k.hashCode(),
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    256, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(DECO_CUTOUT_SHADER_SHARD)
                            .setTextureState(new TextureStateShard(design, false, false) {
                                @Override public void setupRenderState() {
                                    RenderSystem.setShaderTexture(0, CustomGlintRenderer.getTexture(design));
                                    RenderSystem.setShaderTexture(1, baseTex);
                                    RenderSystem.setShaderTexture(2, mask2);
                                    RenderSystem.setShaderColor(holder[0], holder[1], holder[2], holder[3]);
                                }
                                @Override public void clearRenderState() {
                                    super.clearRenderState();
                                    RenderSystem.setShaderTexture(1, 0);
                                    RenderSystem.setShaderTexture(2, 0);
                                    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                                }
                            })
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            // LEQUAL + NO_LAYERING; the camera-ward bias lives in the glint_cutout VERTEX SHADER
                            // (gl_Position), so it survives Sodium's entity path. Same setup as forMountArmorGlint.
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setLayeringState(NO_LAYERING)
                            .setTransparencyState(GLINT_TRANSPARENCY)
                            // Same model-UV scroll as worn armor and the on-pack overlay (forGlintEntityOverlay),
                            // so the decoration glint tiles at one consistent scale on and off pack. NOT the
                            // item-style 8x scroll, which tiled far too densely on the model-space decoration.
                            .setTexturingState(new TexturingStateShard("customglint:ek_deco_glint_cutout_tx",
                                    () -> CustomGlintRenderer.setModelScrollMatrix(layer, colorIdx),
                                    RenderSystem::resetTextureMatrix))
                            .createCompositeState(false));
            CustomGlintRenderer.putCapturedFixedBuffer(rt);
            return rt;
        });
        CustomGlintRenderer.registerLiveFixedBuffer(cached);
        return cached;
    }

    /**
     * Derive the sibling texture for an EK decoration: base ↔ overlay.
     *
     * EK splits decoration art across two files for dyeable items:
     *   base = textures/.../foo.png         (dye-tinted layer)
     *   overlay = textures/.../foo_overlay.png  (un-tinted detail)
     *
     * For most decorations the SHAPE lives in the base (plumes, surcoats); for crowns it's inverted: base holds
     * only the 16 gem pixels, overlay holds the 96 band pixels. The mask must therefore union both textures'
     * opaque pixels so the glint covers the complete decoration silhouette regardless of layout.
     *
     * Returns null if the path doesn't match the expected suffix shape, or for non-dyeable decorations whose
     * sibling file doesn't exist.
     */
    private static ResourceLocation siblingTexture(ResourceLocation tex) {
        String path = tex.getPath();
        ResourceLocation sibling;
        if (path.endsWith("_overlay.png")) {
            sibling = ResourceLocation.fromNamespaceAndPath(tex.getNamespace(),
                    path.substring(0, path.length() - "_overlay.png".length()) + ".png");
        } else if (path.endsWith(".png")) {
            sibling = ResourceLocation.fromNamespaceAndPath(tex.getNamespace(),
                    path.substring(0, path.length() - ".png".length()) + "_overlay.png");
        } else {
            return null;
        }
        // Existence check avoids triggering the missing-texture pink fallback (which has full alpha → would
        // extend the mask over the entire decoration cuboid).
        return Minecraft.getInstance().getResourceManager().getResource(sibling).isPresent() ? sibling : null;
    }

    /**
     * Capture the decoration's glow-outline silhouette. EK decorations draw via {@code ModelPart.render} +
     * {@code getArmorFoilBuffer}, so the generic entity/armor outline tees never see them and a glowing
     * decoration got no ring. Trace the parts against the decoration texture (union with the sibling overlay,
     * since dyeable decorations split their shape across base+overlay; crowns put the band in the overlay),
     * keyed by the wearing ENTITY so the decoration ring merges with the body + base-armor ring.
     */
    public static void captureDecorationOutline(LivingEntity entity, PoseStack pose, int light,
            ModelPart[] parts, ResourceLocation decorationTexture, int color) {
        ResourceLocation sibling = siblingTexture(decorationTexture);
        ResourceLocation[] textures = sibling != null
                ? new ResourceLocation[]{decorationTexture, sibling}
                : new ResourceLocation[]{decorationTexture};
        for (ResourceLocation tex : textures) {
            EntityGlintRender.captureModelPartsSilhouette(entity, entity, parts, tex, pose, light,
                    color, GlowOutlineRenderer.CAT_ARMOR, 0);
        }
    }

    /** Entry point. Off-pack uses the per-fragment cutout; under a shader pack the cutout program is hijacked, so
     *  it defers to the post-Iris overlay drain (see the class header). */
    public static void applyDecorationGlint(LivingEntity entity, PoseStack pose, MultiBufferSource buffer,
            int light, int overlay, ModelPart[] parts, ResourceLocation decorationTexture, CustomGlint.Data glint,
            boolean glowing, ItemStack stack) {
        if (CustomGlintRenderer.isInShadowPass()) return;
        if (CustomGlintRenderer.isShaderPackActive()) {
            applyDecorationGlint_shadersOn(entity, pose, light, parts, decorationTexture, glint);
        } else {
            applyDecorationGlint_cutout(pose, buffer, light, overlay, parts, decorationTexture, glint);
        }
    }

    /**
     * Off-pack path. Binds the decoration's base + sibling overlay to the cutout shader and draws the glint
     * clipped to their union, biased proud of the decoration.
     */
    private static void applyDecorationGlint_cutout(PoseStack pose, MultiBufferSource buffer, int light,
            int overlay, ModelPart[] parts, ResourceLocation decorationTexture, CustomGlint.Data glint) {
        if (CustomGlintRenderer.glintCutoutDecoShader() == null) return;

        // After an Iris pack toggle (activate then deactivate) the entity render dispatcher can hand layers a
        // synthetic lambda MultiBufferSource that does NOT extend BufferSource; its getBuffer would spin up a
        // throwaway builder instead of our registered fixed buffer, and the glint would silently vanish until a
        // world reload. Fall back to the canonical global bufferSource (where our fixed buffers live) so getBuffer
        // routes to the right builders.
        MultiBufferSource.BufferSource bs = buffer instanceof MultiBufferSource.BufferSource direct
                ? direct : Minecraft.getInstance().renderBuffers().bufferSource();
        if (bs == null) return;

        // Union cutout: crowns put the band in the overlay and only the gems in the base; surcoats are the
        // opposite. Bind both so the glint lands on the whole silhouette. No sibling → the factory binds the base
        // to Sampler2 as well.
        ResourceLocation overlayTex = siblingTexture(decorationTexture);

        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();
        List<VertexConsumer> glintVCs = new ArrayList<>();
        for (int li = 0; li < layers.length; li++) {
            if (CustomGlint.isChromatic(layers[li])) {
                // The chromatic design is procedural (no PNG), so forDecorationGlintCutout can't texture it. Draw
                // the chromatic slick clipped to the decoration silhouette via the chromatic_cutout shader (same
                // NEW_ENTITY / LEQUAL / camera-ward bias as forDecorationGlintCutout). One draw per texture unions
                // a split-shape decoration (crown: band in the overlay) the way the two-mask cutout shader does.
                RenderType crt = CustomGlintRenderer.forMountChromaticGlint(glint, li, decorationTexture);
                if (crt != null) glintVCs.add(bs.getBuffer(crt));
                if (overlayTex != null) {
                    RenderType crt2 = CustomGlintRenderer.forMountChromaticGlint(glint, li, overlayTex);
                    if (crt2 != null) glintVCs.add(bs.getBuffer(crt2));
                }
                continue;
            }
            int[] colors = layers[li].colors();
            if (layers[li].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    float a = ((colors[i] >> 24) & 0xFF) / 255.0f;
                    buf[0] = ((colors[i] >> 16) & 0xFF) / 255.0f * a;
                    buf[1] = ((colors[i] >>  8) & 0xFF) / 255.0f * a;
                    buf[2] = ( colors[i]        & 0xFF) / 255.0f * a;
                    buf[3] = 1.0f;
                    RenderType rt = forDecorationGlintCutout(glint, li, buf, i, decorationTexture, overlayTex);
                    if (rt != null) glintVCs.add(bs.getBuffer(rt));
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, li);
                float a = ((color >> 24) & 0xFF) / 255.0f;
                buf[0] = ((color >> 16) & 0xFF) / 255.0f * a;
                buf[1] = ((color >>  8) & 0xFF) / 255.0f * a;
                buf[2] = ( color        & 0xFF) / 255.0f * a;
                buf[3] = 1.0f;
                RenderType rt = forDecorationGlintCutout(glint, li, buf, 0, decorationTexture, overlayTex);
                if (rt != null) glintVCs.add(bs.getBuffer(rt));
            }
        }
        if (glintVCs.isEmpty()) return;

        // Sodium coplanar z-fight (mirrors HorseArmorLayerMixin): the glint is bit-coplanar with the decoration
        // mesh, and the +0.01 gl_Position bias only wins the tie cleanly when both meshes ride the SAME vertex
        // pipeline. Under Sodium a single VertexMultiConsumer breaks the renderCuboid fast-path ("does not
        // support optimized vertex writing"), dropping the glint to the vanilla per-vertex path while the
        // decoration stays on renderCuboid, so the bias can't arbitrate. Per-buffer walks keep every layer on
        // renderCuboid alongside the decoration; without Sodium the single combined draw is fine.
        if (SODIUM_PRESENT) {
            for (VertexConsumer vc : glintVCs) {
                for (ModelPart part : parts) {
                    part.render(pose, vc, light, overlay, 0xFFFFFFFF);
                }
            }
        } else {
            VertexConsumer combined = glintVCs.size() == 1 ? glintVCs.get(0)
                    : VertexMultiConsumer.create(glintVCs.toArray(new VertexConsumer[0]));
            for (ModelPart part : parts) {
                part.render(pose, combined, light, overlay, 0xFFFFFFFF);
            }
        }
        // The glow outline comes from the generic post-process silhouette captured by RenderLayerMixin for EK
        // decoration layers (see captureDecorationOutline), not here.
    }

    /**
     * On-pack path. An active pack hijacks any in-phase custom cutout program, and a vanilla-shader depth
     * self-mask flickers under Iris's re-sorted depth, so defer: capture the decoration parts and queue them for
     * the post-Iris textured-glint overlay drain (the same path horse/mount armor uses under a pack). Union over
     * base + sibling so a split-shape decoration (crown: band in the overlay) is fully covered.
     */
    private static void applyDecorationGlint_shadersOn(LivingEntity entity, PoseStack pose, int light,
            ModelPart[] parts, ResourceLocation decorationTexture, CustomGlint.Data glint) {
        if (entity == null) return;
        ResourceLocation sibling = siblingTexture(decorationTexture);
        ResourceLocation[] textures = sibling != null
                ? new ResourceLocation[]{decorationTexture, sibling}
                : new ResourceLocation[]{decorationTexture};
        CustomGlint.Layer[] layers = glint.layers();
        for (ResourceLocation tex : textures) {
            for (int li = 0; li < layers.length; li++) {
                // A pack hijacks the in-phase chromatic program too, so chromatic layers queue into the post-Iris
                // chromatic overlay drain rather than the textured-glint one.
                if (CustomGlint.isChromatic(layers[li])) {
                    EntityGlintRender.captureChromaticModelParts(entity, parts, tex, pose, light, glint, li);
                } else {
                    EntityGlintRender.captureGlintModelParts(entity, parts, tex, pose, light, glint, li);
                }
            }
        }
    }

    /**
     * Release the off-pack cutout RenderTypes (closing each native fixed buffer via
     * {@link CustomGlintRenderer#evictRt}) and clear the colour holders, on resource reload. Without this the RTs
     * survive a reload pointing at freed design textures and their {@code ByteBufferBuilder}s leak. Registered
     * into {@link CustomGlintRenderer#additionalReloadCleanup} by EK client wiring. (The on-pack path holds no RTs
     * of its own; it queues into the shared overlay drain.)
     */
    public static void releaseCaches() {
        for (RenderType rt : DECO_CUTOUT_CACHE.values()) CustomGlintRenderer.evictRt(rt);
        DECO_CUTOUT_CACHE.clear();
        DECO_CUTOUT_COLORS.clear();
    }

}
