package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.LivingEntity;
import net.tunamods.customglint.common.CustomGlint;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-only entity glint draw. Called from {@link
 * net.tunamods.customglint.common.mixin.LivingEntityRendererMixin} at the point just before
 * the renderer's outer popPose, so the pose stack is still in entity-local space (matches the
 * vantage of armor/layer renderers).
 *
 * Resolution order: per-instance via the registered {@link InstanceResolver} (standalone module
 * installs one that reads from EntityGlintCache), then {@link CustomGlint#ENTITY_GLINTS} type
 * registry. Mods that bundle only the api jar without a resolver still get type-registry-based glints.
 */
public final class EntityGlintRender {
    private EntityGlintRender() {}

    public interface InstanceResolver {
        @Nullable Resolution resolve(LivingEntity entity);
    }

    public static final class Resolution {
        @Nullable public final CustomGlint.Data data;
        public final boolean glowing;
        public final int[] glowColors;
        /** Whether this entity's glow outline draws through walls (dev API: setEntityGlowSeeThrough). */
        public final boolean seeThrough;
        public Resolution(@Nullable CustomGlint.Data data, boolean glowing, int[] glowColors, boolean seeThrough) {
            this.data = data;
            this.glowing = glowing;
            this.glowColors = glowColors;
            this.seeThrough = seeThrough;
        }
    }

    /** Default: no per-instance data — standalone module overrides this in client init. */
    public static InstanceResolver instanceResolver = entity -> null;

    /**
     * Render-state attachment key. In 26.1 the entity render is decoupled from the entity: by draw
     * time {@code LivingEntityRenderer.submit} only has a {@link net.minecraft.client.renderer.entity.state.LivingEntityRenderState}.
     * A NeoForge {@code RegisterRenderStateModifiersEvent} modifier (installed in {@code CustomGlintClientInit})
     * resolves the glint from the entity during extraction and stashes it here via
     * {@code state.setRenderData(RENDER_DATA, ...)}; {@code LivingEntityRendererMixin} reads it back with
     * {@code state.getRenderData(RENDER_DATA)}. This is the entity analog of the item carrier.
     */
    public static final ContextKey<Resolution> RENDER_DATA =
            new ContextKey<>(Identifier.fromNamespaceAndPath("customglint", "entity_glint"));

    /**
     * Per-frame glow-outline request for a glowing entity body. Set on the render state by
     * {@code LivingEntityRendererMixin} (which has {@code getTextureLocation}); read at draw time by
     * {@code ModelFeatureRendererMixin} to tee the silhouette in-phase — vanilla's own outline path
     * (see {@code ModelFeatureRenderer.renderModel}) does the same with {@code outlineColor}. Cleared
     * to null every frame for non-glowing entities, so a pooled/reused render state can't leak a stale
     * outline. {@code color} is the resolved animated glow colour; {@code texture} drives the mask
     * alpha-discard so the silhouette follows the real entity shape.
     */
    public static final ContextKey<GlowOutline> GLOW_OUTLINE =
            new ContextKey<>(Identifier.fromNamespaceAndPath("customglint", "glow_outline"));

    /** {@code model} is the entity's main body model; the in-phase tee fires only when a submit's model
     *  matches it, so overlay layers (which share the render state but submit their own models) don't get
     *  teed with the body texture. Worn armor outlines stay on the separate equipment path. */
    public record GlowOutline(int color, Identifier texture, Object model, boolean seeThrough) {}

    /**
     * Full glint resolution for an entity (per-instance NBT first, then the {@link CustomGlint#ENTITY_GLINTS}
     * type registry). Returns null when the entity has no glint at all. Called by the render-state
     * modifier during extraction — never per-frame on the draw thread.
     */
    @Nullable
    public static Resolution resolveResolution(LivingEntity entity) {
        Resolution r = instanceResolver.resolve(entity);
        if (r != null) return r;
        CustomGlint.Data data = CustomGlint.getEntityGlint(entity.getType());
        if (data == null) return null;
        return new Resolution(data, false, new int[0], false);
    }

    /**
     * Submits the entity-body glint as deferred model nodes — one {@code submitModel} per glint
     * layer/colour, reusing the renderer's body {@code model} so the glint follows the entity
     * silhouette exactly (the 26.1 replacement for the old {@code GlintWrappingBufferSource} fan-out).
     * The animated colour rides the node's {@code tintedColor} (= the model's per-vertex colour, read
     * by {@code customglint:core/glint_color}); {@code forEntityGlint} is NO_LAYERING + EQUAL depth to
     * match the entity body's {@code entityCutoutNoCull} draw. Outline/glow is a separate pass.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void submitEntityGlint(SubmitNodeCollector collector, EntityModel model, Object state,
                                         PoseStack pose, int light, CustomGlint.Data glint) {
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();
        CustomGlint.Layer[] gl = glint.layers();
        for (int layerIdx = 0; layerIdx < gl.length; layerIdx++) {
            int[] colors = gl[layerIdx].colors();
            if (gl[layerIdx].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    RenderType rt = CustomGlintRenderer.forEntityGlint(glint, layerIdx, buf, i);
                    if (rt != null) submitGlintNode(collector, model, state, pose, rt, light, colors[i]);
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                RenderType rt = CustomGlintRenderer.forEntityGlint(glint, layerIdx, buf, 0);
                if (rt != null) submitGlintNode(collector, model, state, pose, rt, light, color);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void submitGlintNode(SubmitNodeCollector collector, EntityModel model, Object state,
                                        PoseStack pose, RenderType rt, int light, int argb) {
        // Alpha honoured verbatim (A=0 → invisible). Colour sources OR in 0xFF by default, so a 0 alpha
        // byte is only ever a deliberate editor A value and must not be forced opaque.
        collector.submitModel(model, state, pose, rt, light, OverlayTexture.NO_OVERLAY, argb, null, 0, null);
    }

    /**
     * Submits the glint for a special-renderer 3D item that draws via a single {@code ModelPart} (trident)
     * — one {@code submitModelPart} per glint layer/colour into the item glint RenderType ({@code isItem=false}
     * → 3D scale), so the glint follows the model shape. The submit-node analog of the quad-item
     * {@code getFoilBuffer} replacement: vanilla foil is gated on enchantment, but a glinted item need not
     * be enchanted, so we draw our own glint geometry independently. Called from {@code SubmitNodeStorageMixin}
     * during the special renderer's submit (item glint context active). Animated colour rides the node's
     * {@code tintedColor} (read by {@code customglint:core/glint_color}).
     */
    public static void submitSpecialPartGlint(SubmitNodeCollector collector,
            ModelPart part, PoseStack pose, int light, CustomGlint.Data glint) {
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();
        CustomGlint.Layer[] gl = glint.layers();
        for (int layerIdx = 0; layerIdx < gl.length; layerIdx++) {
            int[] colors = gl[layerIdx].colors();
            if (gl[layerIdx].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    RenderType rt = CustomGlintRenderer.forGlint(glint, layerIdx, buf, false, i);
                    if (rt != null) collector.submitModelPart(part, pose, rt, light, OverlayTexture.NO_OVERLAY,
                            null, false, false, cg_glintArgb(colors[i]), null, 0);
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                RenderType rt = CustomGlintRenderer.forGlint(glint, layerIdx, buf, false, 0);
                if (rt != null) collector.submitModelPart(part, pose, rt, light, OverlayTexture.NO_OVERLAY,
                        null, false, false, cg_glintArgb(color), null, 0);
            }
        }
    }

    /**
     * {@link net.minecraft.client.model.Model} variant of {@link #submitSpecialPartGlint} for special-renderer
     * items that draw via a {@code submitModel} (shield). The {@code state} is re-applied at draw via
     * {@code model.setupAnim(state)} (e.g. {@code Unit.INSTANCE} for the shield), exactly as
     * {@code ModelFeatureRenderer.renderModel} does for the base model.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void submitSpecialModelGlint(SubmitNodeCollector collector,
            Model model, Object state, PoseStack pose, int light, CustomGlint.Data glint) {
        // A shield (and other submitModel special items) fills far more screen than a slim trident, so the
        // default 3D pattern scale (forGlint isItem=false → 1.0) tiles the design too large to cover the
        // model. Multiply the user's patternScale so the design reads — the 26.1 analog of the 1.21.1 shield
        // multiplier. The trident path (submitSpecialPartGlint) stays at 1.0.
        glint = cg_scalePattern(glint, SPECIAL_MODEL_PATTERN_SCALE);
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();
        CustomGlint.Layer[] gl = glint.layers();
        for (int layerIdx = 0; layerIdx < gl.length; layerIdx++) {
            int[] colors = gl[layerIdx].colors();
            if (gl[layerIdx].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    RenderType rt = CustomGlintRenderer.forGlint(glint, layerIdx, buf, false, i);
                    if (rt != null) collector.submitModel(model, state, pose, rt, light, OverlayTexture.NO_OVERLAY,
                            cg_glintArgb(colors[i]), null, 0, null);
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                RenderType rt = CustomGlintRenderer.forGlint(glint, layerIdx, buf, false, 0);
                if (rt != null) collector.submitModel(model, state, pose, rt, light, OverlayTexture.NO_OVERLAY,
                        cg_glintArgb(color), null, 0, null);
            }
        }
    }

    /** Glint colour passed verbatim to the node's tintedColor — alpha honoured (A=0 → invisible). Colour
     *  sources OR in 0xFF by default, so a 0 alpha byte is only ever a deliberate editor A value. */
    private static int cg_glintArgb(int argb) {
        return argb;
    }

    /** Extra pattern-scale applied to submitModel special items (shield) so the design tiles densely enough
     *  to cover the large model instead of one huge tile. Trident keeps 1.0. (1.21.1 parity: the shield
     *  multiplier; the backpack compat used ×32 for the same reason.) */
    private static final float SPECIAL_MODEL_PATTERN_SCALE = 4.0f;

    /** Returns a copy of {@code glint} with every layer's {@code patternScale} multiplied by {@code mul}. */
    private static CustomGlint.Data cg_scalePattern(CustomGlint.Data glint, float mul) {
        CustomGlint.Layer[] orig = glint.layers();
        CustomGlint.Layer[] scaled = new CustomGlint.Layer[orig.length];
        for (int i = 0; i < orig.length; i++) {
            CustomGlint.Layer l = orig[i];
            scaled[i] = new CustomGlint.Layer(l.design(), l.colors(), l.speed(), l.interpolate(),
                    l.patternScale() * mul, l.simultaneous());
        }
        return new CustomGlint.Data(scaled);
    }

    /** Animated glow-outline colour for an entity: glowColors first, then glint layer 0, else white. */
    public static int outlineColorFor(Resolution r) {
        return resolveOutlineColor(r.data, r.glowColors);
    }

    /**
     * Submits the entity-body glow outline as a deferred custom-geometry node. In 26.1 the stencil
     * two-pass outline ({@link CustomGlintRenderer#doModelOutline}) is immediate-mode and needs a live
     * render pass + a real {@code MultiBufferSource}, which don't exist during the entity
     * {@code submit(...)} extraction phase. So instead of drawing now, we queue a callback that runs at
     * draw time in the solid feature phase (keyed by {@link CustomGlintRenderer#outlineTriggerType()},
     * which {@code CustomFeatureRenderer} runs after every body model has drawn for that order). The
     * callback reconstructs the pose snapshot and replays {@code doModelOutline} against the live
     * buffer source — the 26.1 way to reach the immediate-mode context the stencil outline requires.
     * The supplied buffer is ignored (the trigger RT is a no-op bucket key).
     */
    @SuppressWarnings("rawtypes")
    public static void submitBodyOutline(SubmitNodeCollector collector, EntityModel model,
                                         PoseStack poseStack, int light, Identifier texture, int color) {
        if (model == null || texture == null) return;
        final EntityModel<?> outlineModel = model;
        collector.submitCustomGeometry(poseStack, CustomGlintRenderer.outlineTriggerType(), (pose, buffer) -> {
            PoseStack ps = new PoseStack();
            ps.last().set(pose);
            MultiBufferSource src = Minecraft.getInstance().renderBuffers().bufferSource();
            CustomGlintRenderer.doModelOutline(ps, src, light, outlineModel, texture, color, null);
        });
    }

    /**
     * One queued entity-body outline: the model, its texture, an entity-local pose snapshot, light,
     * and the resolved animated glow colour. Captured during the entity {@code submit(...)} extraction
     * (where the entity-local pose is in hand for free) and replayed later from
     * {@link #drainBodyOutlines()}.
     */
    @SuppressWarnings("rawtypes")
    private static final class BodyOutlineJob {
        final EntityModel model;            // raw: setupAnim is re-applied with the captured state
        final EntityRenderState state;
        final Identifier texture;
        final PoseStack.Pose pose;
        final int light;
        final int color;
        BodyOutlineJob(EntityModel model, EntityRenderState state, Identifier texture,
                       PoseStack.Pose pose, int light, int color) {
            this.model = model; this.state = state; this.texture = texture;
            this.pose = pose; this.light = light; this.color = color;
        }
    }

    /** Per-frame queue of body outlines, drained once at {@code RenderLevelStageEvent.AfterOpaqueFeatures}. */
    private static final List<BodyOutlineJob> BODY_OUTLINES = new ArrayList<>();

    /**
     * One queued held/dropped item glow outline: the item's baked quads, the camera-relative
     * {@code ItemSubmit} pose snapshot, light, and the resolved animated glow colour. Captured during
     * {@code ItemFeatureRenderer.renderItem} (see {@code ItemRendererMixin}) and replayed in the same
     * {@link #drainBodyOutlines()} pass as entity bodies so the item silhouette joins the unioned ring
     * and shares the single fullscreen composite. Items render as a list of {@link BakedQuad}s, not an
     * {@link EntityModel}, so there's no setupAnim to re-apply — the quads are self-contained.
     */
    private static final class ItemOutlineJob {
        final List<BakedQuad> quads;
        final PoseStack.Pose pose;
        final int light;
        final int color;
        ItemOutlineJob(List<BakedQuad> quads,
                       PoseStack.Pose pose, int light, int color) {
            this.quads = quads; this.pose = pose; this.light = light; this.color = color;
        }
    }

    private static final List<ItemOutlineJob> ITEM_OUTLINES = new ArrayList<>();

    /** Full-opaque texture for special/3D item silhouettes: alpha never discards, so the whole model
     *  shape outlines (the 1.21.1 BEWLR "white.png full 3D fill" approach). */
    private static final Identifier WHITE = Identifier.withDefaultNamespace("textures/misc/white.png");

    // Outline GROUPING (see drainBodyOutlines): each logical object gets its own isolated mask + composite
    // so SEPARATE objects never merge into one ring. A single shared mask would union overlapping
    // silhouettes (two nearby swords → one blob; two overlapping players → one ring). Group keys:
    //   - entity body + that entity's worn armor → the entity RENDER STATE identity (so one creature reads
    //     as a single outline, but two players/mobs stay separate).
    //   - each held/dropped/special item → its own group (per-item submit token / ItemSubmit identity).

    /**
     * One queued model-based glow outline: a posed {@link net.minecraft.client.model.Model} (worn armor,
     * elytra, barding, or a special-renderer 3D item like a shield) + its render {@code state}, a
     * camera-relative pose snapshot, the silhouette texture, light, and resolved glow colour. Drawn in
     * {@link #drainBodyOutlines()} exactly like an entity body — {@code model.setupAnim(state)} re-poses
     * the shared model before tracing, so multiple wearers don't get the last one's pose.
     */
    @SuppressWarnings("rawtypes")
    private static final class ModelOutlineJob {
        final Model model;
        final Object state;
        final Identifier texture;
        final PoseStack.Pose pose;
        final int light;
        final int color;
        final Object group;
        final boolean entityBound; // worn equipment on an entity (→ merges into the shared entity ring)
                                   // vs a special-renderer item (→ stays its own ring like dropped items)
        ModelOutlineJob(Model model, Object state, Identifier texture,
                        PoseStack.Pose pose, int light, int color, Object group, boolean entityBound) {
            this.model = model; this.state = state; this.texture = texture;
            this.pose = pose; this.light = light; this.color = color; this.group = group;
            this.entityBound = entityBound;
        }
    }

    /** One queued model-part glow outline for special-renderer 3D items that submit a single
     *  {@link net.minecraft.client.model.geom.ModelPart} (e.g. trident). White.png silhouette. */
    private static final class PartOutlineJob {
        final ModelPart part;
        final PoseStack.Pose pose;
        final int light;
        final int color;
        final Object group;
        PartOutlineJob(ModelPart part, PoseStack.Pose pose, int light,
                       int color, Object group) {
            this.part = part; this.pose = pose; this.light = light; this.color = color; this.group = group;
        }
    }

    private static final List<ModelOutlineJob> MODEL_OUTLINES = new ArrayList<>();
    private static final List<PartOutlineJob> PART_OUTLINES = new ArrayList<>();

    /** One outline group's accumulated jobs, drained into an isolated mask + composite so its ring never
     *  merges with another group's. See the grouping comment in {@link #drainBodyOutlines()}. */
    private static final class Group {
        final List<BodyOutlineJob> bodies = new ArrayList<>();
        final List<ModelOutlineJob> models = new ArrayList<>();
        final List<PartOutlineJob> parts = new ArrayList<>();
        final List<ItemOutlineJob> items = new ArrayList<>();
    }

    /**
     * Queues a worn-equipment glow outline (humanoid armor, elytra/cape, barding — all funnel through
     * {@code EquipmentLayerRenderer.renderLayers}). The model + state are re-posed at drain via
     * {@code setupAnim}, matching how {@code ModelFeatureRenderer} draws the armor body, so the
     * silhouette tracks the wearer's animation. The {@code texture} is the armor layer texture so the
     * mask alpha-discard follows the real armor shape. No-op when the item neither glows nor has colours.
     */
    @SuppressWarnings("rawtypes")
    public static void queueArmorOutline(Model model, Object state,
            PoseStack.Pose pose, Identifier texture, int light, @Nullable CustomGlint.Data glint,
            boolean glowing, int[] glowColors) {
        if (model == null || state == null || texture == null || pose == null) return;
        if (!GlintClientConfig.entityOutlines() || beyondOutlineDistance(pose)) return;
        int[] gc = glowColors == null ? new int[0] : glowColors;
        if (!glowing && gc.length == 0) return;
        // Group key = the entity render state, so this piece merges with that SAME entity's body outline
        // (and its other armor pieces) into one ring, but stays separate from other entities. The body
        // outline (LivingEntityRendererMixin) and the armor layer (EquipmentLayerRenderer.renderLayers)
        // receive the same render-state instance per entity, so the identities match.
        MODEL_OUTLINES.add(new ModelOutlineJob(model, state, texture, pose.copy(), light,
                resolveOutlineColor(glint, gc), state, true));
    }

    /**
     * Queues a special-renderer 3D item's glow outline (shield, etc. — items submitted as a
     * {@code submitModel} during {@code ItemStackRenderState} submit rather than as baked quads). Uses a
     * white.png silhouette (full model shape) since these have no single sprite texture to alpha-discard
     * against — the 1.21.1 BEWLR approach. See {@code SubmitNodeStorageMixin}.
     */
    @SuppressWarnings("rawtypes")
    public static void queueSpecialModelOutline(Model model, Object state,
            PoseStack.Pose pose, int light, @Nullable CustomGlint.Data glint, boolean glowing, int[] glowColors) {
        if (model == null || state == null || pose == null) return;
        if (!GlintClientConfig.itemOutlines() || beyondOutlineDistance(pose)) return;
        int[] gc = glowColors == null ? new int[0] : glowColors;
        if (!glowing && gc.length == 0) return;
        MODEL_OUTLINES.add(new ModelOutlineJob(model, state, WHITE, pose.copy(), light,
                resolveOutlineColor(glint, gc), cg_itemGroup(), false));
    }

    /** {@link net.minecraft.client.model.geom.ModelPart} variant of {@link #queueSpecialModelOutline}
     *  for special items that submit a single part (e.g. trident). White.png silhouette. */
    public static void queueSpecialPartOutline(ModelPart part,
            PoseStack.Pose pose, int light, @Nullable CustomGlint.Data glint, boolean glowing, int[] glowColors) {
        if (part == null || pose == null) return;
        if (!GlintClientConfig.itemOutlines() || beyondOutlineDistance(pose)) return;
        int[] gc = glowColors == null ? new int[0] : glowColors;
        if (!glowing && gc.length == 0) return;
        PART_OUTLINES.add(new PartOutlineJob(part, pose.copy(), light, resolveOutlineColor(glint, gc),
                cg_itemGroup()));
    }

    /** Group key for a special item's sub-model submits: the per-item submit token (so one shield's
     *  base + patterns + foil merge into one ring), or a fresh token if none is active. */
    private static Object cg_itemGroup() {
        Object token = net.tunamods.customglint.common.client.GlintCarrier.SUBMIT_TOKEN.get();
        return token != null ? token : new Object();
    }

    /**
     * Queues a held/dropped item's glow outline for the {@link #drainBodyOutlines()} pass. Called from
     * {@code ItemRendererMixin} at the draw of each glowing {@code ItemSubmit} node (3rd-person held
     * items, dropped item entities, item frames — anything routed through {@code ItemFeatureRenderer}
     * in the framegraph main pass). The glow colour resolves like the entity ring: glowColors first,
     * then glint layer 0, else white. No-op when the item neither glows nor has glow colours.
     */
    public static void queueItemOutline(List<BakedQuad> quads,
            PoseStack.Pose pose, int light, @Nullable CustomGlint.Data glint, boolean glowing, int[] glowColors) {
        if (quads == null || quads.isEmpty() || pose == null) return;
        if (!GlintClientConfig.itemOutlines() || beyondOutlineDistance(pose)) return;
        int[] gc = glowColors == null ? new int[0] : glowColors;
        if (!glowing && gc.length == 0) return;
        ITEM_OUTLINES.add(new ItemOutlineJob(quads, pose.copy(), light, resolveOutlineColor(glint, gc)));
    }

    /**
     * Queues the entity-body glow outline for a single deferred replay at
     * {@code RenderLevelStageEvent.AfterOpaqueFeatures} instead of drawing it through a per-entity
     * {@code submitCustomGeometry} node (see {@link #submitBodyOutline}).
     *
     * Why the move: {@code submitCustomGeometry} callbacks run inside {@code CustomFeatureRenderer},
     * interleaved per render-order bucket — so an early bucket's outline callback fires BEFORE a later
     * bucket's entity body is drawn, i.e. against only partially-committed scene depth. The stencil
     * TEST's occlusion then depends on draw order, which read in-game as the angle-dependent ring
     * dropouts. {@code AfterOpaqueFeatures} fires once, after {@code renderSolidFeatures()} has
     * committed EVERY solid body to the main-target depth — the same fully-committed immediate context
     * 1.21.1's popPose-time {@code renderOutline} had. The pose captured here is a self-contained
     * camera-relative snapshot (like the submit nodes), so the drain rebuilds it into a fresh PoseStack
     * exactly as {@code ModelFeatureRenderer} does.
     *
     * The render {@code state} is captured too: the {@link EntityModel} instance is SHARED across every
     * entity of its type, so by drain time its bone angles hold whatever the last body draw left on it.
     * Replaying the silhouette without re-running {@code setupAnim(state)} reproduced a stale animation
     * (e.g. a sheep's eat pose appearing on the outline seconds later). The drain re-applies setupAnim
     * per job, exactly as {@code ModelFeatureRenderer.renderModel} does before each body draw.
     */
    @SuppressWarnings("rawtypes")
    public static void queueBodyOutline(EntityModel model, EntityRenderState state, PoseStack poseStack,
                                        int light, Identifier texture, int color) {
        if (model == null || state == null || texture == null) return;
        BODY_OUTLINES.add(new BodyOutlineJob(model, state, texture, poseStack.last().copy(), light, color));
    }

    /** Our own isolated outline targets (never touch vanilla's entity_outline target), all at 1/DOWNSCALE
     *  resolution for ~DOWNSCALE² less fill + composite cost. {@code maskTarget} holds the single combined
     *  mask (shape + per-fragment visibility encoded in alpha — see core/glow_silhouette); {@code ringTarget}
     *  holds the composed ring before it's bilinear-upscaled onto the main target. Lazily created and
     *  resized. The mask target keeps a depth attachment only so its size matches the colour attachment for
     *  the ALWAYS_PASS mask pipeline — the depth content is never read or written. */
    // The outline resolution divisor is the client config's outlineRenderScale (1 = full res; higher =
    // softer outline, less GPU fill — the weak-GPU lever). Read fresh each drain so the in-game config
    // screen / on-disk edits apply live (the targets resize on the next frame). DOWNSCALE=1 keeps the
    // silhouette at the SAME res as the scene depth it tests against, so the occlusion epsilon stays tight.
    /** Shared group key for every glowing entity body + its worn armor, so they all draw into one mask
     *  and one composite (O(1) instead of one composite per entity). */
    private static final Object ENTITY_GROUP = new Object();
    private static TextureTarget maskTarget;
    private static TextureTarget ringTarget;
    /** First-person hand projection matrix (captured by GameRendererMixin at renderItemInHand HEAD).
     *  Combined with the live modelview in the FP drain to scissor the held-item composite to its
     *  silhouette instead of running it full-screen. Null until first captured. */
    private static Matrix4f firstPersonProjection;
    /** Sets the captured first-person hand projection — see GameRendererMixin. */
    public static void setFirstPersonProjection(Matrix4f m) { firstPersonProjection = m; }

    /**
     * Drains the per-frame body-outline queue using the isolated silhouette-target pipeline (the 26.1
     * replacement for the dead-end per-pixel-depth stencil band ring — see GlintPipelines.outlineTestPipe
     * TRIED block). Called from {@code RenderLevelStageEvent.AfterOpaqueFeatures}
     * (inside the framegraph main pass, all solid bodies committed to depth, no open render pass).
     *
     * Per entity, ONE un-dilated silhouette render into {@code maskTarget} (always-pass depth → whole
     * outer shape). core/glow_silhouette decides occlusion per-fragment by sampling the full-res scene
     * depth and encodes shape + visibility + distance thickness into alpha (replacing the earlier separate
     * full-shape + visible passes and the depth-downsample pass). The composite then rings a pixel only
     * when it is OUTSIDE the full shape (so internal gap edges from occluders like leaves are never traced)
     * AND has a VISIBLE neighbour (so occluded outer edges stay hidden). Thickness is a 2D screen-space
     * dilation in the composite (no depth band, no flicker).
     *
     * Always clears the queue, even on early return, so a frame that queued but never reached the stage
     * can't leak into the next.
     *
     * TRIED (session 7, made it WORSE — do not re-add to the OLD stencil-ring path): clearMainStencil()
     * at this drain point. clearStencilTexture is raw GL that binds the scratch drawFbo then framebuffer 0
     * mid-framegraph-pass; doing it between endBatch and the draws corrupted the render. (Moot for this
     * path — it uses no main-target stencil.)
     */
    public static void drainBodyOutlines() {
        drainBodyOutlines(true);
    }

    /**
     * @param allowScissor when true (the level-pass drain), each group's clear + composite is scissored
     *     to its projected screen bbox so the per-group fullscreen cost scales with the object's screen
     *     area instead of the whole framebuffer. False for the first-person hand drain, whose item pose
     *     is in view space (not camera-relative world space), so the world projection wouldn't line up.
     */
    public static void drainBodyOutlines(boolean allowScissor) {
        boolean hasBodyGlow = allowScissor && CustomGlintRenderer.hasBodyGlow();
        if (BODY_OUTLINES.isEmpty() && ITEM_OUTLINES.isEmpty()
                && MODEL_OUTLINES.isEmpty() && PART_OUTLINES.isEmpty() && !hasBodyGlow) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (main == null) { clearBodyOutlineQueue(); return; }
        // Read the client outline-resolution divisor fresh each frame so config changes apply live.
        final int DOWNSCALE = GlintClientConfig.outlineRenderScale();
        int w = Math.max(1, main.width / DOWNSCALE), h = Math.max(1, main.height / DOWNSCALE);
        // maskTarget (single combined mask): keeps a depth attachment only to match the colour attachment
        // size for the ALWAYS_PASS mask pipeline — its depth content is never read or written.
        if (maskTarget == null) {
            maskTarget = new TextureTarget("customglint glow mask", w, h, true);
        } else if (maskTarget.width != w || maskTarget.height != h) {
            maskTarget.resize(w, h);
        }
        // ringTarget (composed ring, colour only) — only needed when DOWNSCALE>1, where the ring is
        // composed at reduced res then bilinear-upscaled onto main. At DOWNSCALE=1 that upscale is a 1:1
        // copy (pure waste), so we composite straight onto the main target and skip the ring buffer + the
        // upscale pass + its clear entirely.
        if (DOWNSCALE != 1) {
            if (ringTarget == null) {
                ringTarget = new TextureTarget("customglint glow ring", w, h, false);
            } else if (ringTarget.width != w || ringTarget.height != h) {
                ringTarget.resize(w, h);
            }
        }
        // Expose the live full-res scene depth to the mask shader (sampled per-fragment for occlusion —
        // replaces the old separate downsample pass). Cleared per group below.
        CustomGlintRenderer.bindSceneDepth(main.getDepthTextureView());

        // GROUPING. ALL glowing ENTITIES (every body + its worn armor) share ONE mask + ONE composite —
        // the per-entity composite was the O(N) "dozens of glowing mobs tank the fps" regression, and a
        // shared union ring is exactly what vanilla's entity_outline glow (and the 1.21.1 build) did:
        // overlapping glowing entities read as one merged outline. DROPPED/HELD ITEMS keep their own
        // group each (they're few, and adjacent dropped items reading as separate rings is the nicety the
        // grouping was added for). So entity cost collapses to O(1); item cost stays O(few).
        Map<Object, Group> groups = new LinkedHashMap<>();
        for (BodyOutlineJob j : BODY_OUTLINES) groups.computeIfAbsent(ENTITY_GROUP, k -> new Group()).bodies.add(j);
        for (ModelOutlineJob j : MODEL_OUTLINES)
            groups.computeIfAbsent(j.entityBound ? ENTITY_GROUP : j.group, k -> new Group()).models.add(j);
        for (PartOutlineJob j : PART_OUTLINES) groups.computeIfAbsent(j.group, k -> new Group()).parts.add(j);
        for (ItemOutlineJob j : ITEM_OUTLINES) groups.computeIfAbsent(j, k -> new Group()).items.add(j);
        // Entity bodies are captured in-phase (ModelFeatureRendererMixin → CustomGlintRenderer.teeBodyGlow),
        // not as jobs — ensure the shared entity group is processed so its silhouette buffer gets flushed,
        // even for a lone glowing mob with no worn armor.
        if (hasBodyGlow) groups.computeIfAbsent(ENTITY_GROUP, k -> new Group());
        Group entityGroup = groups.get(ENTITY_GROUP);

        // Combined Projection*ViewRotation: maps a camera-relative world-axis point (an entity/item
        // pose's translation column) to clip space — the matrix the mask draws themselves project with.
        // Used to size each group's scissor. Null on the first-person drain (no world-space scissor).
        Matrix4f vrp = allowScissor
                ? mc.gameRenderer.getMainCamera().getViewRotationProjectionMatrix(new Matrix4f())
                : null;
        // First-person hand drain (allowScissor == false): the held item's geometry is in the hand pose's
        // space; the glow-mask shader draws it with ProjMat*ModelViewMat = the captured hand projection ×
        // the live modelview. Reproduce that exact matrix so the held-item composite scissors to the item's
        // silhouette instead of running full-screen (the reported 1st-person glow cost). Null until the hand
        // projection has been captured (GameRendererMixin), in which case the composite stays full-screen.
        Matrix4f fpMvp = (!allowScissor && firstPersonProjection != null)
                ? new Matrix4f(firstPersonProjection).mul(RenderSystem.getModelViewMatrix())
                : null;

        for (Group g : groups.values()) {
            // Scissor this group's clear + composite to its projected screen bbox so the per-group
            // fullscreen composite (the "dozens of glowing mobs tank the fps" regression) costs only the
            // object's screen area, not the whole framebuffer. null → full screen (FP drain, or a box
            // crossing the near plane where a tight rect could clip the ring).
            boolean isEntityGroup = g == entityGroup;
            // The entity group's scissor must also cover the in-phase body silhouettes (captured as a
            // bbox by fanBodyGlow, not as jobs).
            float[] bodyBox = (isEntityGroup && hasBodyGlow) ? CustomGlintRenderer.bodyGlowBox() : null;
            // Scissor rects are full-res pixels; only valid against the full-res mask at DOWNSCALE=1.
            // At >1 (low-end half-res lever) skip the scissor — the reduced-res mask + composite already
            // cut fill, and a full-res rect on a half-res target would clear/compose the wrong region.
            int[] rect = (vrp != null && DOWNSCALE == 1)
                    ? computeGroupScissor(g, vrp, main.width, main.height, bodyBox) : null;
            // Fresh mask per group so its ring is computed in isolation (no cross-group merge). Depth
            // cleared to far so the LEQUAL inter-mob early-Z test (GLOW_MASK_PIPE) has a clean reference.
            // Region-clear to the scissor rect when we have one: the composite scissors its WRITES to the
            // rect, so we only need the mask clean where it READS — but the composite samples a ±SEARCH
            // texel neighbourhood (post/glow_outline_id), which reaches OUTSIDE the rect. So the clear must
            // cover rect + that read radius, or a group's composite picks up a neighbouring object's
            // leftover silhouette just past its scissor edge and rings it: the axis-aligned "box" that
            // appeared around a glowing mob when a glowing item was held nearby (the held item is its own
            // group; armor merges into the entity group and so never crossed a boundary). Inflating the
            // clear (not the composite scissor) keeps the ring tight while killing the cross-group bleed.
            if (rect == null) {
                RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                        maskTarget.getColorTexture(), 0, maskTarget.getDepthTexture(), 1.0);
            } else {
                int[] cr = inflateRect(rect, MASK_CLEAR_PAD, main.width, main.height);
                RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                        maskTarget.getColorTexture(), 0, maskTarget.getDepthTexture(), 1.0,
                        cr[0], cr[1], cr[2], cr[3]);
            }
            if (DOWNSCALE != 1) {
                RenderSystem.getDevice().createCommandEncoder().clearColorTexture(ringTarget.getColorTexture(), 0);
            }
            // Track the ACTUAL silhouette geometry this group emits (AABBTrackingConsumer fills
            // CustomGlintRenderer.glowMaskBox during the accumulate* calls below) so the composite can
            // scissor to the real shape instead of the loose pose-origin box — near-fullscreen for a close
            // object such as a 3rd-person player in glowing armor.
            CustomGlintRenderer.resetGlowMaskBox();
            Set<Identifier> textures = new LinkedHashSet<>();
            for (BodyOutlineJob job : g.bodies) {
                PoseStack ps = new PoseStack();
                ps.last().set(job.pose);
                // The shared model holds the last entity's bone angles by now — re-apply THIS entity's
                // animation before tracing, or the outline shows a stale pose.
                job.model.setupAnim(job.state);
                CustomGlintRenderer.accumulateGlowMask(ps, job.model,
                        CustomGlintRenderer.glowMaskRT(job.texture), job.light, job.color,
                        CustomGlintRenderer.glowKeyFor(job.state, CustomGlintRenderer.CAT_ENTITY));
                textures.add(job.texture);
            }
            // Worn equipment + special-renderer 3D items: re-pose via setupAnim like the body so multiple
            // wearers don't share a stale pose.
            for (ModelOutlineJob job : g.models) {
                PoseStack ps = new PoseStack();
                ps.last().set(job.pose);
                cg_setupAnim(job.model, job.state);
                // Worn armor merges with its wearer's body + other pieces (identity = the entity render
                // state, category ARMOR); a special-renderer item's sub-models merge with each other
                // (identity = its submit-token group, category ITEM). Shared id ⇒ no doubled ring on overlap.
                int glowKey = job.entityBound
                        ? CustomGlintRenderer.glowKeyFor(job.state, CustomGlintRenderer.CAT_ARMOR)
                        : CustomGlintRenderer.glowKeyFor(job.group, CustomGlintRenderer.CAT_ITEM);
                CustomGlintRenderer.accumulateGlowMask(ps, job.model,
                        CustomGlintRenderer.glowMaskRT(job.texture), job.light, job.color, glowKey);
                textures.add(job.texture);
            }
            // Special-renderer items (e.g. trident) — white.png full-shape silhouette. Every part of one
            // item submit shares the submit-token group, so they merge into ONE outline (not boxes).
            for (PartOutlineJob job : g.parts) {
                PoseStack ps = new PoseStack();
                ps.last().set(job.pose);
                CustomGlintRenderer.accumulatePartGlowMask(ps, job.part,
                        CustomGlintRenderer.glowMaskRT(WHITE), job.light, job.color,
                        CustomGlintRenderer.glowKeyFor(job.group, CustomGlintRenderer.CAT_ITEM));
                textures.add(WHITE);
            }
            // Quad items: re-emit per sprite atlas; no setupAnim — item quads are self-contained.
            for (ItemOutlineJob job : g.items) {
                CustomGlintRenderer.accumulateItemGlowMask(job.pose, job.quads, job.light, job.color, textures,
                        allowScissor ? CustomGlintRenderer.CAT_ITEM : CustomGlintRenderer.CAT_HELD_FP);
            }
            try {
                // Group's combined mask (shape + visibility in alpha) → mask target: one draw per texture.
                RenderSystem.outputColorTextureOverride = maskTarget.getColorTextureView();
                RenderSystem.outputDepthTextureOverride = maskTarget.getDepthTextureView();
                for (Identifier tex : textures) CustomGlintRenderer.flushGlowRT(CustomGlintRenderer.glowMaskRT(tex));
                // In-phase entity-body silhouettes (vanilla-parity capture) draw into the SAME mask, so
                // body + worn armor compose as one ring.
                if (isEntityGroup && hasBodyGlow) CustomGlintRenderer.flushBodyGlow();
            } finally {
                RenderSystem.outputColorTextureOverride = null;
                RenderSystem.outputDepthTextureOverride = null;
            }
            // Tight composite rect from the ACTUAL silhouette geometry (AABBTrackingConsumer filled
            // glowMaskBox during accumulate; in-phase bodies tracked separately). Far smaller than the
            // loose pose-origin computeGroupScissor box for a close/screen-filling object (a 3rd-person
            // player in glowing armor), so the 149-tap composite pays for the silhouette, not a
            // near-fullscreen pose box. Falls back to the loose rect when tracking yields nothing or a
            // corner crosses the near plane. ONLY the composite uses this; the mask clear stays on the
            // loose rect (a superset, so the composite's ±SEARCH neighbourhood reads stay inside it).
            int[] compRect = rect;
            if (vrp != null) {
                float[] tb = CustomGlintRenderer.glowMaskBox();
                if (isEntityGroup && hasBodyGlow) tb = unionTightBox(tb, CustomGlintRenderer.bodyGlowTightUnion());
                if (tb != null) {
                    int[] tr = projectBoxToRect(tb[0], tb[1], tb[2], tb[3], tb[4], tb[5],
                            vrp, main.width, main.height);
                    if (tr != null) compRect = tr;
                }
            } else if (fpMvp != null) {
                // First-person held item: project its tracked silhouette geometry by the hand MVP. No body
                // box here — the FP drain only carries the hand item(s).
                float[] tb = CustomGlintRenderer.glowMaskBox();
                if (tb != null) {
                    int[] tr = projectBoxToRect(tb[0], tb[1], tb[2], tb[3], tb[4], tb[5],
                            fpMvp, main.width, main.height);
                    if (tr != null) compRect = tr;
                }
            }
            if (DOWNSCALE == 1) {
                // Full-res: composite the ring straight onto the main target — no ring buffer + upscale.
                var maskV = maskTarget.getColorTextureView();
                var mainV = main.getColorTextureView();
                // For the shared entity group, split into disjoint per-cluster rects so each cluster's
                // composite pays for its own screen area, not the empty gaps the union bbox spans between
                // far-apart mobs. The id-aware composite keeps objects WITHIN a cluster separate (per-object
                // id in the mask). null → no split (single mob, too many, near-plane, non-entity group) →
                // the single (now geometry-tight) rect.
                List<int[]> clusters = (isEntityGroup && rect != null)
                        ? entityClusterRects(g, vrp, main.width, main.height) : null;
                if (clusters != null) {
                    for (int[] cr : clusters) CustomGlintRenderer.compositeGlowOutline(maskV, mainV, cr);
                } else {
                    CustomGlintRenderer.compositeGlowOutline(maskV, mainV, compRect);
                }
            } else {
                // Reduced-res: compose into ringTarget, then bilinear-upscale onto the main target.
                CustomGlintRenderer.compositeGlowOutline(maskTarget.getColorTextureView(),
                        ringTarget.getColorTextureView(), null);
                CustomGlintRenderer.upscaleGlowRing(ringTarget.getColorTextureView(), main.getColorTextureView());
            }
        }
        BODY_OUTLINES.clear();
        ITEM_OUTLINES.clear();
        MODEL_OUTLINES.clear();
        PART_OUTLINES.clear();
        CustomGlintRenderer.resetBodyGlow();
    }

    /** Pixel margin around a group's projected box: covers the composite's outer-ring dilation
     *  (MAX_THICKNESS/SEARCH in post/glow_dilate_h/v) plus a safety band so the ring never clips the
     *  scissor edge. Also the minimum gap between disjoint clusters, so a ring never crosses a seam. */
    private static final int SCISSOR_PAD = 12;
    /** Extra pixels the per-group mask clear extends beyond the composite scissor rect, so the composite's
     *  ±SEARCH sample neighbourhood (post/glow_outline_id, SEARCH=7) never reads another group's leftover
     *  silhouette just past the edge. Must be >= that shader's SEARCH; +1 for rounding safety. */
    private static final int MASK_CLEAR_PAD = 8;
    /** World radius for jobs with no entity bounding box (held/dropped items, special 3D items, and
     *  worn equipment whose wearer isn't itself glowing). Covers up to ~3-block models; a larger model
     *  just gets a looser-than-needed box — never a clipped one. */
    private static final float NONBODY_RADIUS = 3.0f;

    /**
     * Projects a group's camera-relative bounding box to a framebuffer scissor rect — bottom-left
     * origin, full-res pixels (the convention {@code glScissor}/{@code _scissorBox} and the region
     * clear use). Returns null for "no scissor, run full-screen": either the box crosses the near plane
     * (perspective divide unstable) or it clamps to nothing, where a tight rect could wrongly clip the
     * ring. The box is intentionally generous — the captured pose sits in entity-local space (offset by
     * the renderer's {@code scale(-1,-1,1)}/{@code translate(0,-1.5,0)} model setup), so each body uses
     * radius {@code bbWidth + bbHeight + 2} to stay safely larger than the real silhouette.
     */
    @Nullable
    private static int[] computeGroupScissor(Group g, Matrix4f vrp, int mainW, int mainH, @Nullable float[] extraBox) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (BodyOutlineJob j : g.bodies) {
            float r = j.state.boundingBoxWidth + j.state.boundingBoxHeight + 2.0f;
            Matrix4f m = j.pose.pose();
            minX = Math.min(minX, m.m30() - r); maxX = Math.max(maxX, m.m30() + r);
            minY = Math.min(minY, m.m31() - r); maxY = Math.max(maxY, m.m31() + r);
            minZ = Math.min(minZ, m.m32() - r); maxZ = Math.max(maxZ, m.m32() + r);
        }
        // Non-body jobs (armor on a non-glowing wearer, special items, quad items): no bbox, fixed radius.
        for (PoseStack.Pose p : nonBodyPoses(g)) {
            Matrix4f m = p.pose();
            minX = Math.min(minX, m.m30() - NONBODY_RADIUS); maxX = Math.max(maxX, m.m30() + NONBODY_RADIUS);
            minY = Math.min(minY, m.m31() - NONBODY_RADIUS); maxY = Math.max(maxY, m.m31() + NONBODY_RADIUS);
            minZ = Math.min(minZ, m.m32() - NONBODY_RADIUS); maxZ = Math.max(maxZ, m.m32() + NONBODY_RADIUS);
        }
        // In-phase body silhouettes (entity group): union their pre-projected camera-relative bbox.
        if (extraBox != null) {
            minX = Math.min(minX, extraBox[0]); minY = Math.min(minY, extraBox[1]); minZ = Math.min(minZ, extraBox[2]);
            maxX = Math.max(maxX, extraBox[3]); maxY = Math.max(maxY, extraBox[4]); maxZ = Math.max(maxZ, extraBox[5]);
        }
        if (minX > maxX) return null; // empty group → full screen (shouldn't happen)

        float sMinX = Float.POSITIVE_INFINITY, sMinY = Float.POSITIVE_INFINITY;
        float sMaxX = Float.NEGATIVE_INFINITY, sMaxY = Float.NEGATIVE_INFINITY;
        Vector4f v = new Vector4f();
        for (int i = 0; i < 8; i++) {
            float x = (i & 1) == 0 ? minX : maxX;
            float y = (i & 2) == 0 ? minY : maxY;
            float z = (i & 4) == 0 ? minZ : maxZ;
            v.set(x, y, z, 1.0f).mul(vrp);
            if (v.w <= 1.0e-4f) return null; // corner at/behind the near plane → full-screen fallback
            float ndcX = v.x / v.w, ndcY = v.y / v.w;
            float px = (ndcX * 0.5f + 0.5f) * mainW;
            float py = (ndcY * 0.5f + 0.5f) * mainH; // bottom-left origin: ndcY -1 → 0, +1 → H
            sMinX = Math.min(sMinX, px); sMaxX = Math.max(sMaxX, px);
            sMinY = Math.min(sMinY, py); sMaxY = Math.max(sMaxY, py);
        }
        int x0 = clampPx((int) Math.floor(sMinX) - SCISSOR_PAD, mainW);
        int y0 = clampPx((int) Math.floor(sMinY) - SCISSOR_PAD, mainH);
        int x1 = clampPx((int) Math.ceil(sMaxX) + SCISSOR_PAD, mainW);
        int y1 = clampPx((int) Math.ceil(sMaxY) + SCISSOR_PAD, mainH);
        int rw = x1 - x0, rh = y1 - y0;
        if (rw <= 0 || rh <= 0) return null; // clamped off-screen → full screen (degenerate, very rare)
        return new int[]{x0, y0, rw, rh};
    }

    private static int clampPx(int v, int max) {
        return v < 0 ? 0 : Math.min(v, max);
    }

    /** Grows a scissor rect ({x,y,w,h}, bottom-left origin, full-res px) by {@code pad} on every side,
     *  clamped to the framebuffer. Used to widen the mask clear past the composite read radius. */
    private static int[] inflateRect(int[] rect, int pad, int mainW, int mainH) {
        int x0 = clampPx(rect[0] - pad, mainW);
        int y0 = clampPx(rect[1] - pad, mainH);
        int x1 = clampPx(rect[0] + rect[2] + pad, mainW);
        int y1 = clampPx(rect[1] + rect[3] + pad, mainH);
        return new int[]{x0, y0, x1 - x0, y1 - y0};
    }

    private static final int MAX_CLUSTERS = 16;       // cap composite passes per frame
    private static final int MAX_CLUSTER_INPUT = 64;  // above this, skip clustering (its own cost isn't worth it)

    /**
     * Splits the shared entity group into disjoint per-object screen rects so each cluster's composite pays
     * for its own screen area instead of one union bbox spanning the empty gaps between far-apart objects
     * (e.g. several glowing mobs in separate "pig holes" across the view). Each object's projected bbox is
     * padded by {@link #SCISSOR_PAD} (>> the ring's MAX_THICKNESS), then overlapping rects are merged — so
     * disjoint clusters are far enough apart that no ring is clipped at a seam and no pixel is composited
     * twice. Returns null (caller falls back to the single union rect, identical to before) when there is
     * nothing to split, too many objects to cluster cheaply, the merge yields one/too-many clusters, or any
     * box crosses the near plane.
     */
    @Nullable
    private static List<int[]> entityClusterRects(Group g, Matrix4f vrp, int mainW, int mainH) {
        List<int[]> rects = new ArrayList<>();
        for (float[] b : CustomGlintRenderer.bodyGlowBoxes()) {
            int[] r = projectBoxToRect(b[0], b[1], b[2], b[3], b[4], b[5], vrp, mainW, mainH);
            if (r == null) return null;
            rects.add(r);
        }
        for (BodyOutlineJob j : g.bodies) {
            float rad = j.state.boundingBoxWidth + j.state.boundingBoxHeight + 2.0f;
            Matrix4f m = j.pose.pose();
            int[] r = projectBoxToRect(m.m30() - rad, m.m31() - rad, m.m32() - rad,
                    m.m30() + rad, m.m31() + rad, m.m32() + rad, vrp, mainW, mainH);
            if (r == null) return null;
            rects.add(r);
        }
        for (PoseStack.Pose p : nonBodyPoses(g)) {
            Matrix4f m = p.pose();
            int[] r = projectBoxToRect(m.m30() - NONBODY_RADIUS, m.m31() - NONBODY_RADIUS, m.m32() - NONBODY_RADIUS,
                    m.m30() + NONBODY_RADIUS, m.m31() + NONBODY_RADIUS, m.m32() + NONBODY_RADIUS, vrp, mainW, mainH);
            if (r == null) return null;
            rects.add(r);
        }
        if (rects.size() <= 1 || rects.size() > MAX_CLUSTER_INPUT) return null;
        mergeOverlappingRects(rects);
        if (rects.size() <= 1 || rects.size() > MAX_CLUSTERS) return null;
        return rects;
    }

    /** Projects a camera-relative AABB to a padded screen scissor rect (bottom-left origin, full-res px),
     *  or null if any corner is at/behind the near plane or it clamps to nothing. Mirrors the projection in
     *  {@link #computeGroupScissor} for a single box. */
    @Nullable
    private static int[] projectBoxToRect(float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                                          Matrix4f vrp, int mainW, int mainH) {
        float sMinX = Float.POSITIVE_INFINITY, sMinY = Float.POSITIVE_INFINITY;
        float sMaxX = Float.NEGATIVE_INFINITY, sMaxY = Float.NEGATIVE_INFINITY;
        Vector4f v = new Vector4f();
        for (int i = 0; i < 8; i++) {
            float x = (i & 1) == 0 ? minX : maxX;
            float y = (i & 2) == 0 ? minY : maxY;
            float z = (i & 4) == 0 ? minZ : maxZ;
            v.set(x, y, z, 1.0f).mul(vrp);
            if (v.w <= 1.0e-4f) return null;
            float px = (v.x / v.w * 0.5f + 0.5f) * mainW;
            float py = (v.y / v.w * 0.5f + 0.5f) * mainH;
            sMinX = Math.min(sMinX, px); sMaxX = Math.max(sMaxX, px);
            sMinY = Math.min(sMinY, py); sMaxY = Math.max(sMaxY, py);
        }
        int x0 = clampPx((int) Math.floor(sMinX) - SCISSOR_PAD, mainW);
        int y0 = clampPx((int) Math.floor(sMinY) - SCISSOR_PAD, mainH);
        int x1 = clampPx((int) Math.ceil(sMaxX) + SCISSOR_PAD, mainW);
        int y1 = clampPx((int) Math.ceil(sMaxY) + SCISSOR_PAD, mainH);
        int rw = x1 - x0, rh = y1 - y0;
        if (rw <= 0 || rh <= 0) return null;
        return new int[]{x0, y0, rw, rh};
    }

    /** Union of two camera-relative AABBs {minX,minY,minZ,maxX,maxY,maxZ}; either may be null
     *  (null iff both are null). Combines the tight armor-model box with the in-phase body box. */
    @Nullable
    private static float[] unionTightBox(@Nullable float[] a, @Nullable float[] b) {
        if (a == null) return b;
        if (b == null) return a;
        return new float[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
                Math.max(a[3], b[3]), Math.max(a[4], b[4]), Math.max(a[5], b[5])};
    }

    /** Greedily unions overlapping rects in place until none overlap (so the resulting clusters are
     *  disjoint — no double-composite, no ring clipped at a seam). O(n²) per merge; n is capped by
     *  {@link #MAX_CLUSTER_INPUT}. */
    private static void mergeOverlappingRects(List<int[]> rects) {
        boolean merged = true;
        while (merged) {
            merged = false;
            for (int i = 0; i < rects.size() && !merged; i++) {
                for (int j = i + 1; j < rects.size(); j++) {
                    if (rectsOverlap(rects.get(i), rects.get(j))) {
                        rects.set(i, unionRect(rects.get(i), rects.get(j)));
                        rects.remove(j);
                        merged = true;
                        break;
                    }
                }
            }
        }
    }

    private static boolean rectsOverlap(int[] a, int[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2]
            && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    private static int[] unionRect(int[] a, int[] b) {
        int x0 = Math.min(a[0], b[0]), y0 = Math.min(a[1], b[1]);
        int x1 = Math.max(a[0] + a[2], b[0] + b[2]), y1 = Math.max(a[1] + a[3], b[1] + b[3]);
        return new int[]{x0, y0, x1 - x0, y1 - y0};
    }

    /** True if a camera-relative pose is beyond the client's configured outline draw distance. */
    private static boolean beyondOutlineDistance(PoseStack.Pose pose) {
        Matrix4f m = pose.pose();
        double dSq = (double) m.m30() * m.m30() + (double) m.m31() * m.m31() + (double) m.m32() * m.m32();
        return dSq > GlintClientConfig.outlineMaxDistanceSq();
    }

    /** Poses of a group's non-body jobs (model/part/item), in one list for the bbox union. */
    private static List<PoseStack.Pose> nonBodyPoses(Group g) {
        List<PoseStack.Pose> poses = new ArrayList<>();
        for (ModelOutlineJob j : g.models) poses.add(j.pose);
        for (PartOutlineJob j : g.parts) poses.add(j.pose);
        for (ItemOutlineJob j : g.items) poses.add(j.pose);
        return poses;
    }

    /** Defensive per-frame reset: drop any outlines that were queued but never drained. */
    public static void clearBodyOutlineQueue() {
        BODY_OUTLINES.clear();
        ITEM_OUTLINES.clear();
        MODEL_OUTLINES.clear();
        PART_OUTLINES.clear();
        CustomGlintRenderer.resetBodyGlow();
    }

    /** Raw {@code setupAnim} on a {@link net.minecraft.client.model.Model} of unknown render-state type
     *  (armor/special-item models carry an {@code Object} state captured at submit). Mirrors what
     *  {@code ModelFeatureRenderer.renderModel} does before each deferred model draw. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void cg_setupAnim(Model model, Object state) {
        if (state != null) model.setupAnim(state);
    }

    /**
     * Force the client glint cache to re-read this entity's current persistent NBT. Call after
     * a client-side mutation (e.g. {@link CustomGlint#writeEntity}, {@link CustomGlint#setEntityGlowing},
     * {@link CustomGlint#setEntityGlowColors}) when you need the change visible immediately
     * without waiting for a server broadcast — typically preview UIs, replay viewers, or mods
     * that reconstruct entities from stored NBT on the client.
     *
     * Server-side callers should use {@link net.tunamods.customglint.common.entity.EntityGlintEvents#broadcast}
     * instead; the broadcast packet handler refreshes the cache on every tracking client.
     *
     * No-op (clears the cache entry) if the entity has no glint NBT.
     */
    public static void refreshClientCache(LivingEntity entity) {
        EntityGlintCache.put(entity.getUUID(), CustomGlint.entityGlintTag(entity));
    }

    /**
     * Wraps the renderer's MultiBufferSource so EVERY entity-* RenderType requested during
     * the entity render (base model + every RenderLayer like StrayClothingLayer, EyesLayer,
     * VillagerProfessionLayer, …) gets a glint overlay fan-out. The wrapper is a no-op if the
     * entity has no glint data, so we just return the original buffer in that case.
     *
     * Called from {@link net.tunamods.customglint.common.mixin.LivingEntityRendererMixin} at HEAD.
     */
    public static MultiBufferSource wrapForEntity(LivingEntity entity, MultiBufferSource original) {
        // Idempotent guard: never re-wrap an already-wrapped source (would wrap twice and break
        // body-builder vertex routing).
        if (original instanceof GlintWrappingBufferSource) return original;
        CustomGlint.Data data = resolveData(entity);
        if (data == null) return original;
        return new GlintWrappingBufferSource(original, data);
    }

    /**
     * Unwraps the entity-glint buffer wrapper to the real underlying {@code BufferSource}. Mixins
     * that must {@code endBatch} a specific RenderType to order passes (the stencil-mask-before-glint
     * sequence on mount/dragon armor) need the real BufferSource: during entity rendering the layer
     * receives {@link GlintWrappingBufferSource}, which is NOT a BufferSource, so a raw
     * {@code instanceof BufferSource} check silently skips the flush and the mask never commits
     * before the glint. That only bites when the entity ALSO carries its own glint (so the wrapper
     * is installed) — e.g. a glinted dragon wearing glinted armor; the body part's glint then bled
     * across the whole dragon.
     */
    public static MultiBufferSource unwrap(MultiBufferSource buffer) {
        return buffer instanceof GlintWrappingBufferSource w ? w.delegate : buffer;
    }

    /**
     * Captured (model, texture, pose snapshot, light) for one render-layer surface, queued by
     * {@link net.tunamods.customglint.common.mixin.RenderLayerMixin} during the entity render and
     * drained at popPose by {@link #renderOutline}. Pose snapshot is taken because each layer
     * may have applied its own intermediate transforms onto the PoseStack before invoking the
     * shared static helpers in {@code RenderLayer}.
     */
    public static final class PendingOutline {
        public final EntityModel<?> model;
        public final Identifier texture;
        public final Matrix4f pose;
        public final Matrix3f normal;
        public final int light;
        PendingOutline(EntityModel<?> m, Identifier t, Matrix4f p, Matrix3f n, int l) {
            this.model = m; this.texture = t; this.pose = p; this.normal = n; this.light = l;
        }
    }

    /** Per-thread overlay queue. Cleared at the start of every entity outline drain so a
     *  non-glowing entity's queued (but never drained) entries don't leak into the next one. */
    private static final ThreadLocal<List<PendingOutline>> PENDING =
            ThreadLocal.withInitial(ArrayList::new);

    /**
     * Cheap-gate version of glow lookup used by the layer mixin before snapshotting pose.
     * Returns true iff the entity has a glow/glowColors signal that would trigger an outline.
     */
    private static boolean entityHasGlow(LivingEntity entity) {
        Resolution r = instanceResolver.resolve(entity);
        if (r == null) return false;
        return r.glowing || r.glowColors.length > 0;
    }

    /**
     * True iff this entity carries our glow outline (per-instance glowing flag or glow colours). Used by
     * {@code EntityRendererMixin} to CONSUME vanilla's outline (the glowing effect / team glow) when ours
     * is present, so the two never stack on one entity.
     */
    public static boolean hasGlow(LivingEntity entity) {
        return entityHasGlow(entity);
    }

    /**
     * Called from {@link net.tunamods.customglint.common.mixin.RenderLayerMixin} at the RETURN of
     * the two shared static helpers in {@code RenderLayer} (coloredCutoutModelCopyLayerRender
     * and renderColoredCutoutModel). Snapshots the current pose and queues the overlay for
     * outline rendering at popPose-time, where it shares a single stencil slot with the base
     * body and every other overlay so the union of all silhouettes is stamped before any
     * dilated TEST pass runs. Without this union approach, an early layer's TEST ring would
     * spill into the area that a later overlay covers (e.g. stray's body outline visible
     * inside the clothing outline).
     */
    public static void queueLayerOutline(LivingEntity entity, EntityModel<?> model,
                                         Identifier texture, PoseStack pose,
                                         int packedLight) {
        if (entity == null || model == null || texture == null) return;
        if (!entityHasGlow(entity)) return;
        PENDING.get().add(new PendingOutline(model, texture,
                new Matrix4f(pose.last().pose()), new Matrix3f(pose.last().normal()), packedLight));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void renderOutline(LivingEntityRenderer renderer, LivingEntity entity,
                                     PoseStack pose, MultiBufferSource buffer, int packedLight) {
        List<PendingOutline> pending = PENDING.get();
        CustomGlint.Data data;
        boolean glowing;
        int[] glowColors;
        Resolution r = instanceResolver.resolve(entity);
        if (r != null) {
            data = r.data;
            glowing = r.glowing;
            glowColors = r.glowColors;
        } else {
            data = CustomGlint.getEntityGlint(entity.getType());
            glowing = false;
            glowColors = new int[0];
        }
        if (!glowing && glowColors.length == 0) {
            // Stale entries left over if a layer queued without our glow gate matching
            // (shouldn't happen, but defensively clear).
            pending.clear();
            return;
        }
        int color = resolveOutlineColor(data, glowColors);
        EntityModel model = renderer.getModel();
        // 26.1: getTextureLocation(entity) is gone — it now takes the LivingEntityRenderState, which
        // this old entity-based entry point does not have. The whole entity-outline path is dormant
        // until LivingEntityRendererMixin/RenderLayerMixin are retargeted to the render-state
        // render(S,...) signature (a redesign that needs in-game iteration; the data/network/command
        // entity-glint API is unaffected). Fall back to white.png so the body silhouette still
        // compiles. TODO(26.1): texture = renderer.getTextureLocation(renderState).
        Identifier texture = Identifier.withDefaultNamespace("textures/misc/white.png");

        List<PendingOutline> all = new ArrayList<>(pending.size() + 1);
        all.add(new PendingOutline(model, texture,
                new Matrix4f(pose.last().pose()), new Matrix3f(pose.last().normal()), packedLight));
        all.addAll(pending);
        pending.clear();

        // Unwrap so the outline's stencil RTs flow into the real buffer source (the wrap
        // re-fans entity_* RTs to glint, which would corrupt the stencil pass).
        MultiBufferSource raw = buffer instanceof GlintWrappingBufferSource w ? w.delegate : buffer;
        CustomGlintRenderer.doMultiModelOutline(pose, raw, color, all);
    }

    @Nullable
    private static CustomGlint.Data resolveData(LivingEntity entity) {
        Resolution r = instanceResolver.resolve(entity);
        if (r != null) return r.data;
        return CustomGlint.getEntityGlint(entity.getType());
    }

    private static void fillPremul(float[] buf, int argb) {
        float a = ((argb >> 24) & 0xFF) / 255.0f;
        buf[0] = ((argb >> 16) & 0xFF) / 255.0f * a;
        buf[1] = ((argb >>  8) & 0xFF) / 255.0f * a;
        buf[2] = ( argb        & 0xFF) / 255.0f * a;
        buf[3] = 1.0f;
    }

    private static int resolveOutlineColor(@Nullable CustomGlint.Data data, int[] glowColors) {
        if (glowColors.length > 0) return CustomGlintRenderer.computeAnimatedGlowColor(glowColors);
        if (data != null) return CustomGlintRenderer.computeAnimatedColor(data, 0);
        return 0xFFFFFFFF;
    }

    /**
     * MultiBufferSource wrapper that auto-fans every entity-* RenderType request through the
     * glint render-types of all configured layers. The strategy mirrors what
     * {@link CustomGlintRenderer#applyGlint(...)} (well, ItemRendererMixin's getFoilBuffer path)
     * does for items: a VertexMultiConsumer of {base, glint_layer0, glint_layer1, …}.
     *
     * RenderType filter: only RTs whose toString begins with "entity_" get wrapped. That covers
     * entityCutoutNoCull / entitySolid / entityTranslucent / itemEntityTranslucentCull / etc.
     * (used by all stock entity models and overlay layers like StrayClothingLayer), and
     * excludes text/nametag/particles/our own glint RTs so they pass through untouched.
     */
    public static final class GlintWrappingBufferSource implements MultiBufferSource {
        final MultiBufferSource delegate;
        final CustomGlint.Data glint;

        GlintWrappingBufferSource(MultiBufferSource delegate, CustomGlint.Data glint) {
            this.delegate = delegate;
            this.glint = glint;
        }

        @Override
        public VertexConsumer getBuffer(RenderType rt) {
            if (!shouldApplyGlint(rt)) return delegate.getBuffer(rt);
            // Don't bleed entity glint onto the item the entity is holding. ItemRenderer.render
            // (mixin'd to set CURRENT_ITEM_STACK at HEAD, clear at RETURN) is invoked through
            // HeldItemLayer / ItemInHandLayer during the entity render — those item draws route
            // through entity_solid / entity_translucent RTs which would otherwise match
            // shouldApplyGlint and fan-out the entity's glint onto the item's vertex stream.
            // The item has its own per-item glint via ItemRendererMixin's getFoilBuffer, which
            // still fires correctly; we just want to skip the entity-glint overlay on it.
            if (CustomGlintRenderer.CURRENT_ITEM_STACK.get() != null) return delegate.getBuffer(rt);

            // Acquire glint buffers BEFORE the base. The body's entity_* RT is non-fixed so it
            // shares the BufferSource's single non-fixed BufferBuilder, while our glint RTs are
            // registered in fixedBuffers (dedicated builders). If we got `base` first, the first
            // `delegate.getBuffer(grt)` call would see lastState=body_rt (non-fixed) and switch
            // away from it, which in vanilla BufferSource.getBuffer ends the previous non-fixed
            // builder — i.e. flushes the body builder while it's still empty and leaves it in a
            // non-building state. Subsequent vertex writes to `base` then drop on the floor and
            // the body renders invisible (the dilated outline ring still appears because its
            // stencil-write pass re-renders the model into its own dedicated fixed builder).
            // Acquiring `base` last leaves it as the current active builder when the model writes.
            CustomGlint.Layer[] layers = glint.layers();
            float[] buf = CustomGlintRenderer.COLOR_BUF.get();
            List<VertexConsumer> list = new ArrayList<>(layers.length + 1);
            for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
                int[] colors = layers[layerIdx].colors();
                if (layers[layerIdx].simultaneous()) {
                    for (int i = 0; i < colors.length; i++) {
                        fillPremul(buf, colors[i]);
                        RenderType grt = CustomGlintRenderer.forEntityGlint(glint, layerIdx, buf, i);
                        if (grt != null) list.add(delegate.getBuffer(grt));
                    }
                } else {
                    int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                    fillPremul(buf, color);
                    RenderType grt = CustomGlintRenderer.forEntityGlint(glint, layerIdx, buf, 0);
                    if (grt != null) list.add(delegate.getBuffer(grt));
                }
            }
            VertexConsumer base = delegate.getBuffer(rt);
            if (list.isEmpty()) return base;
            list.add(base);
            return VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
        }

        private static boolean shouldApplyGlint(RenderType rt) {
            String name = rt.toString();
            // Cover entity_cutout_no_cull / entity_solid / entity_translucent / etc.
            return name.startsWith("entity_") || name.startsWith("RenderType[entity_");
        }
    }
}
