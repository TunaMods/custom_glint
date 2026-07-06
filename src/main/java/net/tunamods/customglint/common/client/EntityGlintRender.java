package net.tunamods.customglint.common.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.LivingEntity;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.CustomGlintComponents;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
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
 * Resolution order: per-instance via the registered {@link InstanceResolver} (default reads the synced
 * {@link CustomGlint#ENTITY_GLINT} attachment), then the {@link CustomGlint#ENTITY_GLINTS} type registry.
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
        public Resolution(@Nullable CustomGlint.Data data, boolean glowing, int[] glowColors) {
            this.data = data;
            this.glowing = glowing;
            // Floor to empty: glowColors is dereferenced (.length) every frame; instanceResolver is a public
            // extension point, so a compat resolver passing null would NPE per visible entity per frame.
            this.glowColors = glowColors == null ? new int[0] : glowColors;
        }
    }

    /** Default resolver: read the synced {@link CustomGlintComponents#ENTITY_GLINT} attachment off the
     *  entity. Overridable by compat that wants a different per-instance source. */
    public static InstanceResolver instanceResolver = entity -> {
        CustomGlint.GlintState s = entity.getExistingDataOrNull(CustomGlintComponents.ENTITY_GLINT);
        if (s == null || s.isEmpty()) return null;
        return new Resolution(s.data(), s.glowing(), s.glowColors());
    };

    /**
     * Render-state attachment key. In 26.1 the entity render is decoupled from the entity: by draw
     * time {@code LivingEntityRenderer.submit} only has a {@link net.minecraft.client.renderer.entity.state.LivingEntityRenderState}.
     * A NeoForge {@code RegisterRenderStateModifiersEvent} modifier (installed in {@code CustomGlintClientInit})
     * resolves the glint from the entity during extraction and stashes it here via
     * {@code state.setRenderData(RENDER_DATA, ...)}; {@code LivingEntityRendererMixin} reads it back with
     * {@code state.getRenderData(RENDER_DATA)}. This is the entity analog of the item carrier.
     */
    public static final ContextKey<Resolution> RENDER_DATA =
            new ContextKey<>(CustomGlint.res("entity_glint"));

    /**
     * Per-frame glow-outline request for a glowing entity body. Set on the render state by
     * {@code LivingEntityRendererMixin} (which has {@code getTextureLocation}); read at draw time by
     * {@code ModelFeatureRendererMixin} to tee the silhouette in-phase, vanilla's own outline path
     * (see {@code ModelFeatureRenderer.renderModel}) does the same with {@code outlineColor}. Cleared
     * to null every frame for non-glowing entities, so a pooled/reused render state can't leak a stale
     * outline. {@code color} is the resolved animated glow colour; {@code texture} drives the mask
     * alpha-discard so the silhouette follows the real entity shape.
     */
    public static final ContextKey<GlowOutline> GLOW_OUTLINE =
            new ContextKey<>(CustomGlint.res("glow_outline"));

    /** {@code model} is the entity's main body model; the in-phase tee fires only when a submit's model
     *  matches it, so overlay layers (which share the render state but submit their own models) don't get
     *  teed with the body texture. Worn armor outlines stay on the separate equipment path. */
    public record GlowOutline(int color, Identifier texture, Object model) {}

    /**
     * Full glint resolution for an entity (per-instance NBT first, then the {@link CustomGlint#ENTITY_GLINTS}
     * type registry). Returns null when the entity has no glint at all. Called by the render-state
     * modifier during extraction, never per-frame on the draw thread.
     */
    @Nullable
    public static Resolution resolveResolution(LivingEntity entity) {
        Resolution r = instanceResolver.resolve(entity);
        if (r != null) return r;
        CustomGlint.Data data = CustomGlint.getEntityGlint(entity.getType());
        if (data == null) return null;
        return new Resolution(data, false, new int[0]);
    }

    /**
     * Submits the entity-body glint as deferred model nodes, one {@code submitModel} per glint
     * layer/colour, reusing the renderer's body {@code model} so the glint follows the entity
     * silhouette exactly (the 26.1 replacement for the old {@code GlintWrappingBufferSource} fan-out).
     * The animated colour rides the node's {@code tintedColor} (= the model's per-vertex colour, read
     * by {@code customglint:core/glint_color}); {@code forEntityGlint} is NO_LAYERING + EQUAL depth to
     * match the entity body's {@code entityCutoutNoCull} draw. Outline/glow is a separate pass.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void submitEntityGlint(OrderedSubmitNodeCollector collector, EntityModel model, Object state,
                                         PoseStack pose, int light, CustomGlint.Data glint, @Nullable Identifier texture) {
        CustomGlint.Layer[] gl = glint.layers();
        for (int layerIdx = 0; layerIdx < gl.length; layerIdx++) {
            int[] colors = gl[layerIdx].colors();
            if (colors.length == 0) colors = new int[]{0xFFFFFFFF}; // unchosen layer → white placeholder
            // Under an active shader pack a chromatic layer can't draw in-phase (Iris → flat white); queue it
            // for the post-Iris overlay drain instead. See queueChromaticModel / drainChromaticOverlays.
            if (CustomGlint.isChromatic(gl[layerIdx]) && CustomGlintRenderer.isShaderPackActive()) {
                RenderType rt = CustomGlintRenderer.forEntityGlintOverlay(glint, layerIdx, texture);
                if (rt != null) queueChromaticModel(model, state, pose.last(), rt, light, false);
            } else if (gl[layerIdx].simultaneous() && !CustomGlint.isChromatic(gl[layerIdx])) {
                for (int i = 0; i < colors.length; i++) {
                    RenderType rt = CustomGlintRenderer.forEntityGlint(glint, layerIdx, i);
                    if (rt != null) submitGlintNode(collector, model, state, pose, rt, light, colors[i]);
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                RenderType rt = CustomGlintRenderer.forEntityGlint(glint, layerIdx, 0);
                if (rt != null) submitGlintNode(collector, model, state, pose, rt, light, color);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void submitGlintNode(OrderedSubmitNodeCollector collector, EntityModel model, Object state,
                                        PoseStack pose, RenderType rt, int light, int argb) {
        // Alpha honoured verbatim (A=0 → invisible). Colour sources OR in 0xFF by default, so a 0 alpha
        // byte is only ever a deliberate editor A value and must not be forced opaque.
        collector.submitModel(model, state, pose, rt, light, OverlayTexture.NO_OVERLAY, argb, null, 0, null);
    }

    /**
     * Submits the glint for a special-renderer 3D item that draws via a single {@code ModelPart} (trident)
     *, one {@code submitModelPart} per glint layer/colour into the item glint RenderType ({@code isItem=false}
     * → 3D scale), so the glint follows the model shape. The submit-node analog of the quad-item
     * {@code getFoilBuffer} replacement: vanilla foil is gated on enchantment, but a glinted item need not
     * be enchanted, so we draw our own glint geometry independently. Called from {@code SubmitNodeStorageMixin}
     * during the special renderer's submit (item glint context active). Animated colour rides the node's
     * {@code tintedColor} (read by {@code customglint:core/glint_color}).
     */
    public static void submitSpecialPartGlint(SubmitNodeCollector collector,
            ModelPart part, PoseStack pose, int light, CustomGlint.Data glint) {
        CustomGlint.Layer[] gl = glint.layers();
        for (int layerIdx = 0; layerIdx < gl.length; layerIdx++) {
            int[] colors = gl[layerIdx].colors();
            if (colors.length == 0) colors = new int[]{0xFFFFFFFF}; // unchosen layer → white placeholder
            // Under a pack, queue chromatic for the post-Iris overlay drain (white-dummy cutout = full model
            // shape, like the glow part path); first-person hand items route to the hand drain.
            if (CustomGlint.isChromatic(gl[layerIdx]) && CustomGlintRenderer.isShaderPackActive()) {
                RenderType rt = CustomGlintRenderer.forSpecialItemGlintOverlay(glint, layerIdx);
                if (rt != null) queueChromaticPart(part, pose.last(), rt, light, inFirstPersonHand());
            } else if (gl[layerIdx].simultaneous() && !CustomGlint.isChromatic(gl[layerIdx])) {
                for (int i = 0; i < colors.length; i++) {
                    RenderType rt = CustomGlintRenderer.forGlint(glint, layerIdx, false, i);
                    if (rt != null) collector.submitModelPart(part, pose, rt, light, OverlayTexture.NO_OVERLAY,
                            null, false, false, colors[i], null, 0);
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                RenderType rt = CustomGlintRenderer.forGlint(glint, layerIdx, false, 0);
                if (rt != null) collector.submitModelPart(part, pose, rt, light, OverlayTexture.NO_OVERLAY,
                        null, false, false, color, null, 0);
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
        // model. Multiply the user's patternScale so the design reads, the 26.1 analog of the 1.21.1 shield
        // multiplier. The trident path (submitSpecialPartGlint) stays at 1.0.
        glint = cg_scalePattern(glint, SPECIAL_MODEL_PATTERN_SCALE);
        CustomGlint.Layer[] gl = glint.layers();
        for (int layerIdx = 0; layerIdx < gl.length; layerIdx++) {
            int[] colors = gl[layerIdx].colors();
            if (colors.length == 0) colors = new int[]{0xFFFFFFFF}; // unchosen layer → white placeholder
            // Under a pack, queue chromatic for the post-Iris overlay drain. glint is already pattern-scaled
            // above, so the overlay RT (isItem=false → uvScale 1) inherits the ×SPECIAL_MODEL_PATTERN_SCALE
            // density. White-dummy cutout = full model shape. First-person hand items route to the hand drain.
            if (CustomGlint.isChromatic(gl[layerIdx]) && CustomGlintRenderer.isShaderPackActive()) {
                RenderType rt = CustomGlintRenderer.forSpecialItemGlintOverlay(glint, layerIdx);
                if (rt != null) queueChromaticModel(model, state, pose.last(), rt, light, inFirstPersonHand());
            } else if (gl[layerIdx].simultaneous() && !CustomGlint.isChromatic(gl[layerIdx])) {
                for (int i = 0; i < colors.length; i++) {
                    RenderType rt = CustomGlintRenderer.forGlint(glint, layerIdx, false, i);
                    if (rt != null) collector.submitModel(model, state, pose, rt, light, OverlayTexture.NO_OVERLAY,
                            colors[i], null, 0, null);
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                RenderType rt = CustomGlintRenderer.forGlint(glint, layerIdx, false, 0);
                if (rt != null) collector.submitModel(model, state, pose, rt, light, OverlayTexture.NO_OVERLAY,
                        color, null, 0, null);
            }
        }
    }

    // Glint colour rides the node's tintedColor verbatim, alpha honoured (A=0 → invisible). Colour sources
    // OR in 0xFF by default, so a 0 alpha byte is only ever a deliberate editor A value.

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
                    l.patternScale() * mul, l.simultaneous(), l.scrollDir(), l.scrollOffset(), l.seed());
        }
        return new CustomGlint.Data(scaled);
    }

    /** Animated glow-outline colour for an entity: glowColors first, then glint layer 0, else white. */
    public static int outlineColorFor(Resolution r) {
        return resolveOutlineColor(r.data, r.glowColors, 1.0f, true);
    }

    /**
     * One queued held/dropped item glow outline: the item's baked quads, the camera-relative
     * {@code ItemSubmit} pose snapshot, light, and the resolved animated glow colour. Captured during
     * {@code ItemFeatureRenderer.renderItem} (see {@code ItemRendererMixin}) and replayed in the same
     * {@link #drainBodyOutlines()} pass as entity bodies so the item silhouette joins the unioned ring
     * and shares the single fullscreen composite. Items render as a list of {@link BakedQuad}s, not an
     * {@link EntityModel}, so there's no setupAnim to re-apply, the quads are self-contained.
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
    /** First-person held-item outlines, kept OUT of {@link #ITEM_OUTLINES} so the world drain
     *  ({@code allowScissor==true}) never composites them. The held item's pose is in the hand's view
     *  space, so it must be drained only by the hand-projection drain ({@code allowScissor==false}):
     *  {@code ItemInHandRendererMixin} off the shader path, {@code GameRendererMixin} (renderItemInHand
     *  RETURN) under an active Iris pack. Under Iris the hand item is captured DURING the level framegraph
     *  (Iris renders the hand in-pipeline), so the world drain would otherwise consume + clear it with the
     *  world projection, the "outline floats ~1 block off the item, anchored" symptom. A separate queue
     *  the world drain leaves untouched lets it survive to the hand drain. See the TRIED note in
     *  {@code GameRendererMixin}. */
    private static final List<ItemOutlineJob> HELD_FP_OUTLINES = new ArrayList<>();

    /** Full-opaque texture for special/3D item silhouettes: alpha never discards, so the whole model
     *  shape outlines (the 1.21.1 BEWLR "white.png full 3D fill" approach). */
    private static final Identifier WHITE = Identifier.fromNamespaceAndPath("neoforge", "textures/white.png");

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
     * {@link #drainBodyOutlines()} exactly like an entity body, {@code model.setupAnim(state)} re-poses
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
    /** First-person held SPECIAL/3D items (shield → submitModel, trident → submitModelPart), kept OUT of
     *  {@link #MODEL_OUTLINES}/{@link #PART_OUTLINES} for the same reason as {@link #HELD_FP_OUTLINES}:
     *  their pose is hand-local, so the world drain would float the ring ~1 block off the item (worst under
     *  Iris, which renders the hand mid-framegraph). Routed here when {@link #inFirstPersonHand()} (set
     *  across {@code ItemInHandRenderer.renderHandsWithItems}, which both the vanilla and Iris hand paths
     *  call); drained only by the hand-projection drain. See the TRIED history in {@code GameRendererMixin}. */
    private static final List<ModelOutlineJob> HELD_FP_MODEL_OUTLINES = new ArrayList<>();
    private static final List<PartOutlineJob> HELD_FP_PART_OUTLINES = new ArrayList<>();

    /** True only while {@code ItemInHandRenderer.renderHandsWithItems} runs (set by
     *  {@code ItemInHandRendererMixin}). Render thread only, a plain flag like {@code SubmitNodeStorageMixin}'s
     *  {@code cg_addingGlint}. Special-item outline queues consult it to route first-person hand items to the
     *  hand-only queues above. */
    private static boolean inFirstPersonHand = false;
    public static void beginFirstPersonHand() { inFirstPersonHand = true; }
    public static void endFirstPersonHand() { inFirstPersonHand = false; }
    public static boolean inFirstPersonHand() { return inFirstPersonHand; }

    /** One outline group's accumulated jobs, drained into an isolated mask + composite so its ring never
     *  merges with another group's. See the grouping comment in {@link #drainBodyOutlines()}. */
    private static final class Group {
        final List<ModelOutlineJob> models = new ArrayList<>();
        final List<PartOutlineJob> parts = new ArrayList<>();
        final List<ItemOutlineJob> items = new ArrayList<>();
    }

    /**
     * Queues a worn-equipment glow outline (humanoid armor, elytra/cape, barding, all funnel through
     * {@code EquipmentLayerRenderer.renderLayers}). The model + state are re-posed at drain via
     * {@code setupAnim}, matching how {@code ModelFeatureRenderer} draws the armor body, so the
     * silhouette tracks the wearer's animation. The {@code texture} is the armor layer texture so the
     * mask alpha-discard follows the real armor shape. No-op when the item neither glows nor has colours.
     */
    @SuppressWarnings("rawtypes")
    public static void queueArmorOutline(Model model, Object state,
            PoseStack.Pose pose, Identifier texture, int light, @Nullable CustomGlint.Data glint,
            boolean glowing, int[] glowColors, float glowSpeed, boolean glowInterp) {
        if (model == null || state == null || texture == null || pose == null) return;
        if (CustomGlintRenderer.isInShadowPass()) return;   // shadow-pass pose ⇒ detached duplicate ring
        if (!GlintClientConfig.entityOutlines() || beyondOutlineDistance(pose)) return;
        int[] gc = glowColors == null ? new int[0] : glowColors;
        if (!glowing && gc.length == 0) return;
        // Group key = the entity render state, so this piece merges with that SAME entity's body outline
        // (and its other armor pieces) into one ring, but stays separate from other entities. The body
        // outline (LivingEntityRendererMixin) and the armor layer (EquipmentLayerRenderer.renderLayers)
        // receive the same render-state instance per entity, so the identities match.
        MODEL_OUTLINES.add(new ModelOutlineJob(model, state, texture, pose.copy(), light,
                resolveOutlineColor(glint, gc, glowSpeed, glowInterp), state, true));
    }

    /**
     * Queues a special-renderer 3D item's glow outline (shield, etc., items submitted as a
     * {@code submitModel} during {@code ItemStackRenderState} submit rather than as baked quads). Uses a
     * white.png silhouette (full model shape) since these have no single sprite texture to alpha-discard
     * against, the 1.21.1 BEWLR approach. See {@code SubmitNodeStorageMixin}.
     */
    @SuppressWarnings("rawtypes")
    public static void queueSpecialModelOutline(Model model, Object state,
            PoseStack.Pose pose, int light, @Nullable CustomGlint.Data glint, boolean glowing, int[] glowColors,
            float glowSpeed, boolean glowInterp) {
        if (model == null || state == null || pose == null) return;
        if (CustomGlintRenderer.isInShadowPass()) return;   // shadow-pass pose ⇒ detached duplicate ring
        if (!GlintClientConfig.itemOutlines() || beyondOutlineDistance(pose)) return;
        int[] gc = glowColors == null ? new int[0] : glowColors;
        if (!glowing && gc.length == 0) return;
        ModelOutlineJob job = new ModelOutlineJob(model, state, WHITE, pose.copy(), light,
                resolveOutlineColor(glint, gc, glowSpeed, glowInterp), cg_itemGroup(), false);
        (inFirstPersonHand ? HELD_FP_MODEL_OUTLINES : MODEL_OUTLINES).add(job);
    }

    /** {@link net.minecraft.client.model.geom.ModelPart} variant of {@link #queueSpecialModelOutline}
     *  for special items that submit a single part (e.g. trident). White.png silhouette. */
    public static void queueSpecialPartOutline(ModelPart part,
            PoseStack.Pose pose, int light, @Nullable CustomGlint.Data glint, boolean glowing, int[] glowColors,
            float glowSpeed, boolean glowInterp) {
        if (part == null || pose == null) return;
        if (CustomGlintRenderer.isInShadowPass()) return;   // shadow-pass pose ⇒ detached duplicate ring
        if (!GlintClientConfig.itemOutlines() || beyondOutlineDistance(pose)) return;
        int[] gc = glowColors == null ? new int[0] : glowColors;
        if (!glowing && gc.length == 0) return;
        PartOutlineJob job = new PartOutlineJob(part, pose.copy(), light, resolveOutlineColor(glint, gc, glowSpeed, glowInterp),
                cg_itemGroup());
        (inFirstPersonHand ? HELD_FP_PART_OUTLINES : PART_OUTLINES).add(job);
    }

    /** Group key for a special item's sub-model submits: the per-item submit token (so one shield's
     *  base + patterns + foil merge into one ring), or a fresh token if none is active. */
    private static Object cg_itemGroup() {
        Object token = GlintCarrier.SUBMIT_TOKEN.get();
        return token != null ? token : new Object();
    }

    /**
     * Queues a held/dropped item's glow outline for the {@link #drainBodyOutlines()} pass. Called from
     * {@code ItemRendererMixin} at the draw of each glowing {@code ItemSubmit} node (3rd-person held
     * items, dropped item entities, item frames, anything routed through {@code ItemFeatureRenderer}
     * in the framegraph main pass). The glow colour resolves like the entity ring: glowColors first,
     * then glint layer 0, else white. No-op when the item neither glows nor has glow colours.
     *
     * @param heldFirstPerson true for the first-person hand item (the {@code ItemSubmit}'s
     *     {@code displayContext.firstPerson()}). Such jobs go to {@link #HELD_FP_OUTLINES} so the world
     *     drain never composites their view-space pose against the world projection.
     */
    public static void queueItemOutline(List<BakedQuad> quads, PoseStack.Pose pose, int light,
            @Nullable CustomGlint.Data glint, boolean glowing, int[] glowColors, float glowSpeed, boolean glowInterp,
            boolean heldFirstPerson) {
        if (quads == null || quads.isEmpty() || pose == null) return;
        if (CustomGlintRenderer.isInShadowPass()) return;   // shadow-pass pose ⇒ detached duplicate ring
        if (!GlintClientConfig.itemOutlines() || beyondOutlineDistance(pose)) return;
        int[] gc = glowColors == null ? new int[0] : glowColors;
        if (!glowing && gc.length == 0) return;
        ItemOutlineJob job = new ItemOutlineJob(quads, pose.copy(), light, resolveOutlineColor(glint, gc, glowSpeed, glowInterp));
        (heldFirstPerson ? HELD_FP_OUTLINES : ITEM_OUTLINES).add(job);
    }

    /** Our own isolated outline targets (never touch vanilla's entity_outline target), all at 1/DOWNSCALE
     *  resolution for ~DOWNSCALE² less fill + composite cost. {@code maskTarget} holds the single combined
     *  mask (shape + per-fragment visibility encoded in alpha, see core/glow_silhouette); {@code ringTarget}
     *  holds the composed ring before it's bilinear-upscaled onto the main target. Lazily created and
     *  resized. The mask target keeps a depth attachment only so its size matches the colour attachment for
     *  the ALWAYS_PASS mask pipeline, the depth content is never read or written. */
    // The outline resolution divisor is the client config's outlineRenderScale (1 = full res; higher =
    // softer outline, less GPU fill, the weak-GPU lever). Read fresh each drain so the in-game config
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
    /** Sets the captured first-person hand projection, see GameRendererMixin. */
    public static void setFirstPersonProjection(Matrix4f m) { firstPersonProjection = m; }

    /** Frees the GPU textures backing the glow/chromatic composite targets and drops the handles. Called from
     *  {@link CustomGlintRenderer#clearTextures()} on resource reload, without this they survive every reload
     *  for the process lifetime (the resize path frees old textures internally, but nothing ever closed the
     *  targets outright). They are rebuilt lazily on the next drain. */
    public static void releaseTargets() {
        if (maskTarget != null)      { maskTarget.destroyBuffers();      maskTarget = null; }
        if (ringTarget != null)      { ringTarget.destroyBuffers();      ringTarget = null; }
        if (chromaticTarget != null) { chromaticTarget.destroyBuffers(); chromaticTarget = null; }
    }

    // ── Post-Iris chromatic overlay (shader-pack path only) ──────────────────────────────────────
    //
    // The procedural chromatic glint can't draw in-phase under an active pack, Iris swaps our procedural
    // program for one of its own and the glint goes flat white (the chromatic analog of the GLINT_COLOR
    // white bug, but unfixable by IrisCompat.assignPipeline: no pack program can recreate an oil-slick).
    // So under a pack the chromatic chokepoints queue the model here instead of submitting it, and the
    // queue is re-rendered AFTER Iris finishes the frame (renderLevel TAIL, via LevelRendererMixin, inside
    // the same camera-view modelview push the glow drain uses) onto an isolated target, then composited
    // back with the GLINT blend. Occlusion is decided in-shader against the committed scene depth
    // (GlintPipelines.CHROMATIC_OVERLAY / core/chromatic_overlay.fsh), so the re-render doesn't need its
    // depth to match Iris's gbuffer exactly. Off the shader path none of this runs, chromatic draws
    // in-phase as normal.

    /** One queued chromatic model overlay: a posed model (worn equipment or an entity body) + its render
     *  {@code state} (re-applied via {@code setupAnim} at drain), the captured chromatic-overlay RenderType,
     *  and light. Colour is unused (white), the palette carries every colour. */
    @SuppressWarnings("rawtypes")
    private static final class ChromaModelJob {
        final Model model;
        final Object state;
        final PoseStack.Pose pose;
        final RenderType rt;
        final int light;
        ChromaModelJob(Model model, Object state, PoseStack.Pose pose, RenderType rt, int light) {
            this.model = model; this.state = state; this.pose = pose; this.rt = rt; this.light = light;
        }
    }

    /** One queued chromatic ITEM overlay: the item's baked quads, the camera-relative {@code ItemSubmit}
     *  pose snapshot (hand-local for the first-person queue), light, and the chromatic layer to draw. The
     *  cutout RenderType is resolved per quad at drain time from the quad's own sprite atlas (item sprites
     *  can live on different atlases), so the job carries the glint + layer index, not a prebuilt RT. */
    private static final class ChromaItemJob {
        final List<BakedQuad> quads;
        final PoseStack.Pose pose;
        final int light;
        final CustomGlint.Data glint;
        final int layerIdx;
        ChromaItemJob(List<BakedQuad> quads, PoseStack.Pose pose, int light, CustomGlint.Data glint, int layerIdx) {
            this.quads = quads; this.pose = pose; this.light = light; this.glint = glint; this.layerIdx = layerIdx;
        }
    }

    /** One queued chromatic special-item part overlay (trident, a single {@code ModelPart}). White-dummy
     *  cutout (full model shape), like the glow part path. */
    private static final class ChromaPartJob {
        final ModelPart part;
        final PoseStack.Pose pose;
        final RenderType rt;
        final int light;
        ChromaPartJob(ModelPart part, PoseStack.Pose pose, RenderType rt, int light) {
            this.part = part; this.pose = pose; this.rt = rt; this.light = light;
        }
    }

    private static final List<ChromaModelJob> CHROMA_MODELS = new ArrayList<>();
    /** First-person held chromatic special MODELS (shield), hand-local pose; drained only at the hand point. */
    private static final List<ChromaModelJob> HELD_FP_CHROMA_MODELS = new ArrayList<>();
    private static final List<ChromaPartJob> CHROMA_PARTS = new ArrayList<>();
    /** First-person held chromatic special PARTS (trident), hand-local pose; drained only at the hand point. */
    private static final List<ChromaPartJob> HELD_FP_CHROMA_PARTS = new ArrayList<>();
    private static final List<ChromaItemJob> CHROMA_ITEMS = new ArrayList<>();
    /** First-person held chromatic items, kept OUT of {@link #CHROMA_ITEMS} for the same reason as
     *  {@link #HELD_FP_OUTLINES}: their pose is hand-local, so the world drain would project it against the
     *  world matrix and the slick would float off the item. Drained only by the hand-projection drain
     *  ({@code GameRendererMixin} renderItemInHand RETURN, under a pack). */
    private static final List<ChromaItemJob> HELD_FP_CHROMA_ITEMS = new ArrayList<>();
    private static TextureTarget chromaticTarget;

    /** Queues a chromatic model (worn equipment or entity body) for the post-Iris overlay drain. Called from
     *  the chromatic chokepoints (EquipmentLayerRendererMixin, {@link #submitEntityGlint}) only when a shader
     *  pack is active. {@code rt} is the {@code CustomGlintRenderer.forXxxGlintOverlay} RenderType. */
    @SuppressWarnings("rawtypes")
    public static void queueChromaticModel(Model model, Object state, PoseStack.Pose pose, RenderType rt, int light,
                                           boolean heldFirstPerson) {
        if (model == null || state == null || pose == null || rt == null) return;
        if (CustomGlintRenderer.isInShadowPass()) return;   // shadow-pass pose ⇒ detached duplicate overlay
        ChromaModelJob job = new ChromaModelJob(model, state, pose.copy(), rt, light);
        (heldFirstPerson ? HELD_FP_CHROMA_MODELS : CHROMA_MODELS).add(job);
    }

    /** Queues a chromatic special-item part (trident) for the post-Iris overlay drain. Called from
     *  {@link #submitSpecialPartGlint} only when a shader pack is active. */
    public static void queueChromaticPart(ModelPart part, PoseStack.Pose pose, RenderType rt, int light,
                                          boolean heldFirstPerson) {
        if (part == null || pose == null || rt == null) return;
        if (CustomGlintRenderer.isInShadowPass()) return;   // shadow-pass pose ⇒ detached duplicate overlay
        ChromaPartJob job = new ChromaPartJob(part, pose.copy(), rt, light);
        (heldFirstPerson ? HELD_FP_CHROMA_PARTS : CHROMA_PARTS).add(job);
    }

    /** Queues a chromatic flat/quad item (held or dropped) for the post-Iris overlay drain. Called from
     *  {@code ItemRendererMixin} only when a shader pack is active. {@code heldFirstPerson} routes the
     *  first-person hand item to the hand-projection drain instead of the world drain. */
    public static void queueChromaticItem(List<BakedQuad> quads, PoseStack.Pose pose, CustomGlint.Data glint,
                                          int layerIdx, int light, boolean heldFirstPerson) {
        if (quads == null || quads.isEmpty() || pose == null || glint == null) return;
        if (CustomGlintRenderer.isInShadowPass()) return;   // shadow-pass pose ⇒ detached duplicate overlay
        ChromaItemJob job = new ChromaItemJob(quads, pose.copy(), light, glint, layerIdx);
        (heldFirstPerson ? HELD_FP_CHROMA_ITEMS : CHROMA_ITEMS).add(job);
    }

    /**
     * Re-renders the queued chromatic models + items AFTER Iris has finished the frame, onto an isolated
     * full-res target, then composites the result onto the main target. The world drain
     * ({@code firstPerson == false}, {@code LevelRendererMixin} renderLevel TAIL) draws worn equipment,
     * entity bodies, and 3rd-person/dropped items, inside the camera-view modelview push. The hand drain
     * ({@code firstPerson == true}, {@code GameRendererMixin} renderItemInHand RETURN) draws only the
     * first-person held item(s), with the hand projection + bob modelview in place. Always clears the
     * queues it owns.
     */
    public static void drainChromaticOverlays(boolean firstPerson) {
        List<ChromaModelJob> modelJobs = firstPerson ? HELD_FP_CHROMA_MODELS : CHROMA_MODELS;
        List<ChromaPartJob> partJobs = firstPerson ? HELD_FP_CHROMA_PARTS : CHROMA_PARTS;
        List<ChromaItemJob> itemJobs = firstPerson ? HELD_FP_CHROMA_ITEMS : CHROMA_ITEMS;
        if (modelJobs.isEmpty() && partJobs.isEmpty() && itemJobs.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (main == null) { modelJobs.clear(); partJobs.clear(); itemJobs.clear(); return; }
        int w = Math.max(1, main.width), h = Math.max(1, main.height);
        if (chromaticTarget == null) {
            chromaticTarget = new TextureTarget("customglint chromatic overlay", w, h, true);
        } else if (chromaticTarget.width != w || chromaticTarget.height != h) {
            chromaticTarget.resize(w, h);
        }
        // The overlay shader samples the committed scene depth for its per-fragment occlusion test.
        CustomGlintRenderer.bindSceneDepth(main.getDepthTextureView());
        // Fresh target each frame (colour cleared to 0 so the GLINT-blend composite adds only the slick;
        // depth to far, unused by the ALWAYS-test pipeline but kept consistent with the attachment).
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                chromaticTarget.getColorTexture(), 0, chromaticTarget.getDepthTexture(), 1.0);
        try {
            RenderSystem.outputColorTextureOverride = chromaticTarget.getColorTextureView();
            RenderSystem.outputDepthTextureOverride = chromaticTarget.getDepthTextureView();
            MultiBufferSource.BufferSource bs = mc.renderBuffers().bufferSource();
            Set<RenderType> used = new LinkedHashSet<>();
            // One scratch PoseStack reused across every job: each iteration fully overwrites last() before
            // rendering synchronously (the model/part renderers push/pop balanced and don't retain it), so a
            // fresh allocation per job is pure churn in this per-frame drain.
            PoseStack scratch = new PoseStack();
            for (ChromaModelJob job : modelJobs) {
                scratch.last().set(job.pose);
                cg_setupAnim(job.model, job.state);
                job.model.renderToBuffer(scratch, bs.getBuffer(job.rt), job.light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
                used.add(job.rt);
            }
            for (ChromaPartJob job : partJobs) {
                scratch.last().set(job.pose);
                job.part.render(scratch, bs.getBuffer(job.rt), job.light, OverlayTexture.NO_OVERLAY);
                used.add(job.rt);
            }
            // Reused across jobs: color + overlay are constant, only lightCoords varies per job, and
            // putBakedQuad reads it synchronously.
            QuadInstance qi = new QuadInstance();
            qi.setColor(0xFFFFFFFF);
            qi.setOverlayCoords(OverlayTexture.NO_OVERLAY);
            for (ChromaItemJob job : itemJobs) {
                qi.setLightCoords(job.light);
                // Resolve the cutout RT per quad from its OWN sprite atlas (mirrors accumulateItemGlowMask):
                // a fixed atlas samples the wrong texels and the slick fills the whole quad or vanishes.
                for (BakedQuad quad : job.quads) {
                    Identifier atlas = quad.materialInfo().sprite().atlasLocation();
                    RenderType rt = CustomGlintRenderer.forItemGlintOverlay(job.glint, job.layerIdx, atlas);
                    if (rt == null) continue;
                    bs.getBuffer(rt).putBakedQuad(job.pose, quad, qi);
                    used.add(rt);
                }
            }
            for (RenderType rt : used) bs.endBatch(rt);
        } finally {
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
            modelJobs.clear();
            partJobs.clear();
            itemJobs.clear();
        }
        CustomGlintRenderer.compositeChromatic(chromaticTarget.getColorTextureView(), main.getColorTextureView());
    }

    /**
     * Drains the per-frame body-outline queue using the isolated silhouette-target pipeline (the 26.1
     * replacement for the dead-end per-pixel-depth stencil band ring the 1.20.1/1.21.1 builds used).
     * Called from {@code RenderLevelStageEvent.AfterWeather} off the shader path, or {@code
     * LevelRendererMixin} (renderLevel TAIL) under an active Iris pack
     * (all solid bodies committed to depth, no open render pass).
     *
     * Per entity, ONE un-dilated silhouette render into {@code maskTarget} (always-pass depth → whole
     * outer shape). core/glow_silhouette decides occlusion per-fragment by sampling the full-res scene
     * depth and encodes shape + visibility + distance thickness into alpha (replacing the earlier separate
     * full-shape + visible passes and the depth-downsample pass). The composite then rings a pixel only
     * when it is OUTSIDE the full shape (so internal gap edges from occluders like leaves are never traced)
     * AND has a VISIBLE neighbour (so occluded outer edges stay hidden). Thickness is a 2D screen-space
     * dilation in the composite (no depth band, no flicker).
     *
     * Always clears the queue, on early return AND on a mid-drain exception (the group loop is wrapped
     * in try/finally), so a frame that queued but never finished draining can't leak into the next.
     *
     * TRIED (session 7, made it WORSE, do not re-add to the OLD stencil-ring path): clearMainStencil()
     * at this drain point. clearStencilTexture is raw GL that binds the scratch drawFbo then framebuffer 0
     * mid-framegraph-pass; doing it between endBatch and the draws corrupted the render. (Moot for this
     * path, it uses no main-target stencil.)
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
        // World drain (allowScissor) composites dropped/3rd-person items + worn armor; the hand drain
        // composites the first-person held item(s) only, their hand-local pose can't be projected with
        // the world matrix. Quad items, special 3D models (shields), and parts (tridents) each split into
        // a world queue and a hand-only queue.
        List<ItemOutlineJob> itemJobs = allowScissor ? ITEM_OUTLINES : HELD_FP_OUTLINES;
        List<ModelOutlineJob> modelJobs = allowScissor ? MODEL_OUTLINES : HELD_FP_MODEL_OUTLINES;
        List<PartOutlineJob> partJobs = allowScissor ? PART_OUTLINES : HELD_FP_PART_OUTLINES;
        if (itemJobs.isEmpty()
                && modelJobs.isEmpty() && partJobs.isEmpty() && !hasBodyGlow) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (main == null) { clearBodyOutlineQueue(); return; }
        // Read the client outline-resolution divisor fresh each frame so config changes apply live.
        final int DOWNSCALE = GlintClientConfig.outlineRenderScale();
        int w = Math.max(1, main.width / DOWNSCALE), h = Math.max(1, main.height / DOWNSCALE);
        // maskTarget (single combined mask): keeps a depth attachment only to match the colour attachment
        // size for the ALWAYS_PASS mask pipeline, its depth content is never read or written.
        if (maskTarget == null) {
            maskTarget = new TextureTarget("customglint glow mask", w, h, true);
        } else if (maskTarget.width != w || maskTarget.height != h) {
            maskTarget.resize(w, h);
        }
        // ringTarget (composed ring, colour only), only needed when DOWNSCALE>1, where the ring is
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
        // Expose the live full-res scene depth to the mask shader (sampled per-fragment for occlusion,
        // replaces the old separate downsample pass). Cleared per group below.
        CustomGlintRenderer.bindSceneDepth(main.getDepthTextureView());

        // GROUPING. ALL glowing ENTITIES (every body + its worn armor) share ONE mask + ONE composite,
        // the per-entity composite was the O(N) "dozens of glowing mobs tank the fps" regression, and a
        // shared union ring is exactly what vanilla's entity_outline glow (and the 1.21.1 build) did:
        // overlapping glowing entities read as one merged outline. DROPPED/HELD ITEMS keep their own
        // group each (they're few, and adjacent dropped items reading as separate rings is the nicety the
        // grouping was added for). So entity cost collapses to O(1); item cost stays O(few).
        Map<Object, Group> groups = new LinkedHashMap<>();
        for (ModelOutlineJob j : modelJobs)
            groups.computeIfAbsent(j.entityBound ? ENTITY_GROUP : j.group, k -> new Group()).models.add(j);
        for (PartOutlineJob j : partJobs) groups.computeIfAbsent(j.group, k -> new Group()).parts.add(j);
        for (ItemOutlineJob j : itemJobs) groups.computeIfAbsent(j, k -> new Group()).items.add(j);
        // Entity bodies are captured in-phase (ModelFeatureRendererMixin → CustomGlintRenderer.fanBodyGlow),
        // not as jobs, ensure the shared entity group is processed so its silhouette buffer gets flushed,
        // even for a lone glowing mob with no worn armor.
        if (hasBodyGlow) groups.computeIfAbsent(ENTITY_GROUP, k -> new Group());
        Group entityGroup = groups.get(ENTITY_GROUP);
        try {

        // Combined Projection*ViewRotation: maps a camera-relative world-axis point (an entity/item
        // pose's translation column) to clip space, the matrix the mask draws themselves project with.
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
            // At >1 (low-end half-res lever) skip the scissor, the reduced-res mask + composite already
            // cut fill, and a full-res rect on a half-res target would clear/compose the wrong region.
            int[] rect = (vrp != null && DOWNSCALE == 1)
                    ? computeGroupScissor(g, vrp, main.width, main.height, bodyBox) : null;
            // Fresh mask per group so its ring is computed in isolation (no cross-group merge). Depth
            // cleared to far so the LEQUAL inter-mob early-Z test (GLOW_MASK_PIPE) has a clean reference.
            // Region-clear to the scissor rect when we have one: the composite scissors its WRITES to the
            // rect, so we only need the mask clean where it READS, but the composite samples a ±SEARCH
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
            // scissor to the real shape instead of the loose pose-origin box, near-fullscreen for a close
            // object such as a 3rd-person player in glowing armor.
            CustomGlintRenderer.resetGlowMaskBox();
            Set<Identifier> textures = new LinkedHashSet<>();
            // One scratch PoseStack reused across both job loops in this group: each iteration overwrites
            // last() before the accumulate* call renders it synchronously (those helpers don't retain the
            // PoseStack), so a fresh allocation per job is avoidable churn.
            PoseStack scratch = new PoseStack();
            // Worn equipment + special-renderer 3D items: re-pose via setupAnim like the body so multiple
            // wearers don't share a stale pose.
            for (ModelOutlineJob job : g.models) {
                scratch.last().set(job.pose);
                cg_setupAnim(job.model, job.state);
                // Worn armor merges with its wearer's body + other pieces (identity = the entity render
                // state, category ARMOR); a special-renderer item's sub-models merge with each other
                // (identity = its submit-token group, category ITEM). Shared id ⇒ no doubled ring on overlap.
                int itemCat = allowScissor ? CustomGlintRenderer.CAT_ITEM : CustomGlintRenderer.CAT_HELD_FP;
                int glowKey = job.entityBound
                        ? CustomGlintRenderer.glowKeyFor(job.state, CustomGlintRenderer.CAT_ARMOR)
                        : CustomGlintRenderer.glowKeyFor(job.group, itemCat);
                CustomGlintRenderer.accumulateGlowMask(scratch, job.model,
                        CustomGlintRenderer.glowMaskRT(job.texture), job.light, job.color, glowKey);
                textures.add(job.texture);
            }
            // Special-renderer items (e.g. trident), white.png full-shape silhouette. Every part of one
            // item submit shares the submit-token group, so they merge into ONE outline (not boxes).
            for (PartOutlineJob job : g.parts) {
                scratch.last().set(job.pose);
                CustomGlintRenderer.accumulatePartGlowMask(scratch, job.part,
                        CustomGlintRenderer.glowMaskRT(WHITE), job.light, job.color,
                        CustomGlintRenderer.glowKeyFor(job.group,
                                allowScissor ? CustomGlintRenderer.CAT_ITEM : CustomGlintRenderer.CAT_HELD_FP));
                textures.add(WHITE);
            }
            // Quad items: re-emit per sprite atlas; no setupAnim, item quads are self-contained.
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
                // box here, the FP drain only carries the hand item(s).
                float[] tb = CustomGlintRenderer.glowMaskBox();
                if (tb != null) {
                    int[] tr = projectBoxToRect(tb[0], tb[1], tb[2], tb[3], tb[4], tb[5],
                            fpMvp, main.width, main.height);
                    if (tr != null) compRect = tr;
                }
            }
            if (DOWNSCALE == 1) {
                // Full-res: composite the ring straight onto the main target, no ring buffer + upscale.
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
        } finally {
            // Clear only the queues this drain owned, the world drain must NOT wipe the hand-only queues
            // (queued during the framegraph under Iris, drained later at the hand point). In a finally so a
            // mid-drain exception can't leave stale jobs/buffers for the next frame.
            itemJobs.clear();
            modelJobs.clear();
            partJobs.clear();
            CustomGlintRenderer.resetBodyGlow();
        }
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
     *  just gets a looser-than-needed box, never a clipped one. */
    private static final float NONBODY_RADIUS = 3.0f;

    /**
     * Projects a group's camera-relative bounding box to a framebuffer scissor rect, bottom-left
     * origin, full-res pixels (the convention {@code glScissor}/{@code _scissorBox} and the region
     * clear use). Returns null for "no scissor, run full-screen": either the box crosses the near plane
     * (perspective divide unstable) or it clamps to nothing, where a tight rect could wrongly clip the
     * ring. The box is intentionally generous, the captured pose sits in entity-local space (offset by
     * the renderer's {@code scale(-1,-1,1)}/{@code translate(0,-1.5,0)} model setup), so each body uses
     * radius {@code bbWidth + bbHeight + 2} to stay safely larger than the real silhouette.
     */
    @Nullable
    private static int[] computeGroupScissor(Group g, Matrix4f vrp, int mainW, int mainH, @Nullable float[] extraBox) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        // Glowing entity bodies are captured in-phase (fanBodyGlow); their union bbox arrives via extraBox.
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
     * padded by {@link #SCISSOR_PAD} (>> the ring's MAX_THICKNESS), then overlapping rects are merged, so
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
     *  disjoint, no double-composite, no ring clipped at a seam). O(n²) per merge; n is capped by
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
        ITEM_OUTLINES.clear();
        HELD_FP_OUTLINES.clear();
        MODEL_OUTLINES.clear();
        PART_OUTLINES.clear();
        HELD_FP_MODEL_OUTLINES.clear();
        HELD_FP_PART_OUTLINES.clear();
        CHROMA_MODELS.clear();
        HELD_FP_CHROMA_MODELS.clear();
        CHROMA_PARTS.clear();
        HELD_FP_CHROMA_PARTS.clear();
        CHROMA_ITEMS.clear();
        HELD_FP_CHROMA_ITEMS.clear();
        // A first-person hand render that threw would skip endFirstPersonHand() and leave this stuck true,
        // routing later world special-items to the never-drained-in-world HELD_FP queues (glow vanishes).
        // This runs at frame start, so a thrown hand frame self-heals.
        inFirstPersonHand = false;
        // A LivingEntityRenderer.submit that threw between HEAD and RETURN would skip its own clear and leave
        // the current body model / entity state bound to the render thread. This frame-start reset self-heals
        // it (parity with inFirstPersonHand above), so a stale identity can't suppress the next entity's glint.
        CURRENT_BODY_MODEL.remove();
        CURRENT_ENTITY.remove();
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
     * Cheap-gate version of glow lookup. Returns true iff the entity has a glow/glowColors signal that
     * would trigger an outline.
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
     * True for a vanilla entity body / {@code RenderLayer} surface RenderType (entity_cutout,
     * entity_solid, entity_translucent, entity_cutout_no_cull, …). Excludes armor (armor_*), our own
     * glint/mask RTs, eyes, and any non-surface type, so the per-layer glint ({@code SubmitNodeStorageMixin})
     * and per-layer outline tee ({@code ModelFeatureRendererMixin}) only augment real entity geometry.
     */
    /** Identity cache for {@link #isEntitySurface}: RenderTypes are interned singletons drawn every frame,
     *  so the {@code toString()} + prefix scan is computed once per distinct RenderType, not per submit. */
    private static final Map<RenderType, Boolean> SURFACE_CACHE = new IdentityHashMap<>();

    /** Drops the entity-surface identity cache on resource reload (called from {@code clearTextures}). */
    public static void clearSurfaceCache() { SURFACE_CACHE.clear(); }

    public static boolean isEntitySurface(RenderType rt) {
        Boolean cached = SURFACE_CACHE.get(rt);
        if (cached != null) return cached;
        String name = rt.toString();
        boolean surface = name.startsWith("entity_") || name.startsWith("RenderType[entity_");
        SURFACE_CACHE.put(rt, surface);
        return surface;
    }

    /**
     * Identity of the entity body model currently being submitted, set by {@code LivingEntityRendererMixin}
     * for the span of {@code LivingEntityRenderer.submit}. The body is glinted there directly; the per-layer
     * glint hook ({@code SubmitNodeStorageMixin}) reads this back to skip the body's own {@code submitModel}
     * so it isn't glinted twice. Null outside an entity submit (and for non-living renderers).
     */
    private static final ThreadLocal<Object> CURRENT_BODY_MODEL = new ThreadLocal<>();

    public static void setCurrentBodyModel(@Nullable Object model) {
        if (model == null) CURRENT_BODY_MODEL.remove(); else CURRENT_BODY_MODEL.set(model);
    }

    @Nullable
    public static Object currentBodyModel() { return CURRENT_BODY_MODEL.get(); }

    /** The render state of the entity currently being submitted, published by {@code LivingEntityRendererMixin}
     *  for the span of {@code LivingEntityRenderer.submit}. Block-model layers (mooshroom mushrooms, snow-golem
     *  pumpkin) submit through {@code SubmitNodeCollection.submitBlockModel}, which carries no entity state, so
     *  the block-layer glow hook reads the owning entity back from here. Null outside an entity submit. */
    private static final ThreadLocal<LivingEntityRenderState> CURRENT_ENTITY = new ThreadLocal<>();

    public static void setCurrentEntity(@Nullable LivingEntityRenderState state) {
        if (state == null) CURRENT_ENTITY.remove(); else CURRENT_ENTITY.set(state);
    }

    /** The entity render state of the active submit span, or null outside one. Lets the block-submit hook
     *  bail in its HEAD (the common no-entity block submit) without a cross-class call. */
    @Nullable
    public static LivingEntityRenderState currentEntity() { return CURRENT_ENTITY.get(); }

    /**
     * Glint + glow for a block-model entity layer (mooshroom mushrooms, snow-golem pumpkin, anything an
     * entity submits via {@code BlockModelRenderState.submit} → {@code submitBlockModel}). These are not
     * {@code EntityModel}s, so the {@link SubmitNodeCollectionMixin} model-layer hook misses them; this is
     * called from a separate {@code submitBlockModel} hook. Block parts expose {@link BakedQuad}s with
     * block-atlas UVs, exactly like flat item sprites.
     *
     * <p><b>Glint:</b> the {@code submitBlockModel} sink can't carry our per-vertex glint colour (it has no
     * tintedColor, and block quads are untinted), so we emit the quads ourselves through
     * {@link OrderedSubmitNodeCollector#submitCustomGeometry} into the item glint RenderType
     * ({@code forGlint(isItem=true)}, atlas-calibrated like flat items), forcing the layer's animated colour
     * onto every vertex via {@link QuadInstance}. The callback runs in the deferred translucent pass with the
     * block's own depth committed, so EQUAL-depth glint overlays correctly. The {@code parts} list is the
     * copy {@code BlockModelRenderState.submitModel} already made, so capturing it in the callback is safe.
     *
     * <p><b>Glow:</b> the parts' quads route through the existing item-quad outline path
     * ({@link #queueItemOutline}) into the same mask + composite as everything else.
     */
    public static void submitBlockLayerGlintGlow(OrderedSubmitNodeCollector collector, PoseStack poseStack,
            List<BlockStateModelPart> parts, int light, int overlay) {
        LivingEntityRenderState state = CURRENT_ENTITY.get();
        if (state == null || state.isInvisible || parts == null || parts.isEmpty()) return;
        Resolution r = state.getRenderData(RENDER_DATA);
        if (r == null) return;

        // Flatten the parts' quads ONCE and share the list across every pass (chromatic overlay, each
        // glint color in simultaneous mode, and the glow outline), the previous code re-walked the parts
        // per pass, copying the quad list up to several times per block-layer entity per frame.
        List<BakedQuad> quads = cg_blockQuads(parts);
        if (quads.isEmpty()) return;

        if (r.data != null) {
            CustomGlint.Layer[] gl = r.data.layers();
            for (int layerIdx = 0; layerIdx < gl.length; layerIdx++) {
                int[] colors = gl[layerIdx].colors();
                if (colors.length == 0) colors = new int[]{0xFFFFFFFF}; // unchosen layer → white placeholder
                if (CustomGlint.isChromatic(gl[layerIdx])) {
                    if (CustomGlintRenderer.isShaderPackActive()) {
                        // Under a pack, queue the block parts' quads for the post-Iris overlay drain (block
                        // atlas drives both the noise scale and the cutout, like flat items). Block-model
                        // entity layers are always world (never first-person).
                        queueChromaticItem(quads, poseStack.last(), r.data, layerIdx, light, false);
                    } else {
                        cg_blockGlint(collector, poseStack, quads, light, overlay, r.data, layerIdx, 0, 0xFFFFFFFF);
                    }
                } else if (gl[layerIdx].simultaneous()) {
                    for (int i = 0; i < colors.length; i++)
                        cg_blockGlint(collector, poseStack, quads, light, overlay, r.data, layerIdx, i, colors[i]);
                } else {
                    int color = CustomGlintRenderer.computeAnimatedColor(r.data, layerIdx);
                    cg_blockGlint(collector, poseStack, quads, light, overlay, r.data, layerIdx, 0, color);
                }
            }
        }

        if (r.glowing || r.glowColors.length > 0)
            queueItemOutline(quads, poseStack.last(), light, r.data, r.glowing, r.glowColors, 1.0f, true, false);
    }

    /** Flattens a block-model layer's parts into their {@link BakedQuad}s (all directions + the null/general
     *  bucket), the shared collection used by the glow outline and the post-Iris chromatic overlay. */
    private static List<BakedQuad> cg_blockQuads(List<BlockStateModelPart> parts) {
        List<BakedQuad> quads = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            if (part == null) continue;
            quads.addAll(part.getQuads(null));
            for (Direction d : Direction.values()) quads.addAll(part.getQuads(d));
        }
        return quads;
    }

    /** Submits one glint pass over a block-model layer's parts: the parts' quads drawn through {@code rt} with
     *  {@code argb} forced onto every vertex. Deferred via {@code submitCustomGeometry} so it draws in-phase. */
    private static void cg_blockGlint(OrderedSubmitNodeCollector collector, PoseStack poseStack,
            List<BakedQuad> quads, int light, int overlay, CustomGlint.Data glint,
            int layerIdx, int colorIdx, int argb) {
        RenderType rt = CustomGlintRenderer.forBlockGlint(glint, layerIdx, colorIdx);
        if (rt == null) return;
        final int color = (argb >>> 24) == 0 ? (argb | 0xFF000000) : argb; // A=0 from a non-editor source → opaque
        collector.submitCustomGeometry(poseStack, rt, (pose, buffer) -> {
            QuadInstance qi = new QuadInstance();
            qi.setColor(color);
            qi.setLightCoords(light);
            qi.setOverlayCoords(overlay);
            for (BakedQuad q : quads) buffer.putBakedQuad(pose, q, qi);
        });
    }

    private static int resolveOutlineColor(@Nullable CustomGlint.Data data, int[] glowColors,
            float glowSpeed, boolean glowInterp) {
        if (glowColors.length > 0) return CustomGlintRenderer.computeAnimatedGlowColor(glowColors, glowSpeed, glowInterp);
        if (data != null) return CustomGlintRenderer.computeAnimatedColor(data, 0);
        return 0xFFFFFFFF;
    }

}
