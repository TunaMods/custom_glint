package net.tunamods.customglint.module.compat.artifacts.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
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
import java.util.List;

/**
 * Glue for the Artifacts compat. Artifacts worn in Curios slots (belts, necklaces, gloves, boots, …) are
 * drawn by their own {@code ArtifactRenderer.render} through Curios, never the vanilla armor layer, so
 * our core mixins never see them. Each renderer obtains its draw buffer from
 * {@code ItemRenderer.getFoilBuffer} with an {@code entityCutoutNoCull} render type.
 *
 * {@code ArtifactGlintMixin} arms this glue for the span of one {@code ArtifactRenderer.render} (so only
 * artifacts are touched), and {@code FoilBufferMixin} calls {@link #wrap} on that method's return while
 * armed, fanning the same mesh across our entity-depth glint render types and (when glowing) a record-only
 * silhouette. {@link #flush} queues the recorded silhouettes as one glow ring keyed on the wearer +
 * {@code CAT_ARMOR}. The glint uses {@code forHorseArmorGlint}/{@code forChromaticEntityGlint} (EQUAL +
 * NO_LAYERING) to match {@code entityCutoutNoCull}'s depth — {@code forArmorGlint} would test against the
 * wrong offset and vanish. All references are vanilla types, so there is no dep on Artifacts.
 */
public final class ArtifactGlint {
    private ArtifactGlint() {}

    private static final ThreadLocal<ItemStack> CURRENT_STACK = new ThreadLocal<>();
    private static final ThreadLocal<LivingEntity> CURRENT_ENTITY = new ThreadLocal<>();
    private static final ThreadLocal<List<Pending>> PENDING = ThreadLocal.withInitial(ArrayList::new);

    /** A recorded silhouette awaiting its glow-ring queue, with the texture its draw traced against. */
    private static final class Pending {
        final EntityGlintRender.CapturingModelConsumer cap;
        final ResourceLocation tex;
        Pending(EntityGlintRender.CapturingModelConsumer cap, ResourceLocation tex) {
            this.cap = cap; this.tex = tex;
        }
    }

    /** Arm for one {@code ArtifactRenderer.render}. Every {@code getFoilBuffer} call until {@link #flush}
     *  belongs to this artifact, so {@link #wrap} may fan its glint without catching unrelated draws. */
    public static void arm(LivingEntity entity, ItemStack stack) {
        // Only fan glint / capture glow while the 3D world is rendering. The inventory player preview and
        // GUI slot icons also route artifacts through ArtifactRenderer.render, but under the GUI ortho our
        // entity-space glint/glow would stretch into a giant projected ray, so skip them.
        if (!CustomGlintRenderer.isRenderingWorld()) return;
        CURRENT_STACK.set(stack);
        CURRENT_ENTITY.set(entity);
    }

    public static boolean isArmed() {
        return CURRENT_STACK.get() != null;
    }

    /** Wrap the artifact foil buffer so the same mesh also feeds our glint render types and, when glowing,
     *  a record-only silhouette. Returns {@code base} unchanged when the stack has no glint/glow. */
    public static VertexConsumer wrap(VertexConsumer base, RenderType rt) {
        ItemStack stack = CURRENT_STACK.get();
        if (stack == null || stack.isEmpty()) return base;
        CustomGlint.Data glint = CustomGlint.readCached(stack);
        boolean glowing = CustomGlint.isGlowing(stack);
        if (glint == null && !glowing) return base;

        MultiBufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        List<VertexConsumer> list = new ArrayList<>();
        list.add(base);

        if (glint != null) {
            CustomGlint.Layer[] layers = glint.layers();
            float[] buf = CustomGlintRenderer.COLOR_BUF.get();
            for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
                if (CustomGlint.isChromatic(layers[layerIdx])) {
                    RenderType crt = CustomGlintRenderer.forChromaticEntityGlint(glint, layerIdx);
                    if (crt != null) list.add(bufferSource.getBuffer(crt));
                    continue;
                }
                int[] colors = layers[layerIdx].colors();
                if (layers[layerIdx].simultaneous()) {
                    for (int i = 0; i < colors.length; i++) {
                        float a = ((colors[i] >> 24) & 0xFF) / 255.0f;
                        buf[0] = ((colors[i] >> 16) & 0xFF) / 255.0f * a;
                        buf[1] = ((colors[i] >>  8) & 0xFF) / 255.0f * a;
                        buf[2] = ( colors[i]        & 0xFF) / 255.0f * a;
                        buf[3] = 1.0f;
                        RenderType rt2 = CustomGlintRenderer.forHorseArmorGlint(glint, layerIdx, buf, i);
                        if (rt2 != null) list.add(bufferSource.getBuffer(rt2));
                    }
                } else {
                    int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                    float a = ((color >> 24) & 0xFF) / 255.0f;
                    buf[0] = ((color >> 16) & 0xFF) / 255.0f * a;
                    buf[1] = ((color >>  8) & 0xFF) / 255.0f * a;
                    buf[2] = ( color        & 0xFF) / 255.0f * a;
                    buf[3] = 1.0f;
                    RenderType rt2 = CustomGlintRenderer.forHorseArmorGlint(glint, layerIdx, buf, 0);
                    if (rt2 != null) list.add(bufferSource.getBuffer(rt2));
                }
            }
        }

        if (glowing) {
            EntityGlintRender.CapturingModelConsumer cap = new EntityGlintRender.CapturingModelConsumer();
            cap.delegate = null;
            PENDING.get().add(new Pending(cap, GlowOutlineRenderer.resolveRenderTypeTexture(rt)));
            list.add(cap);
        }

        if (list.size() == 1) return base;
        return VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
    }

    /** Queue every recorded silhouette from this artifact as one glow ring (wearer + CAT_ARMOR, so it
     *  folds into the body ring) and disarm. Called at {@code ArtifactRenderer.render} RETURN. */
    public static void flush() {
        List<Pending> pending = PENDING.get();
        LivingEntity entity = CURRENT_ENTITY.get();
        ItemStack stack = CURRENT_STACK.get();
        CURRENT_STACK.set(null);
        CURRENT_ENTITY.set(null);
        if (pending.isEmpty()) return;
        try {
            if (entity == null || stack == null) return;
            Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
            int color = CustomGlintRenderer.resolveGlowColor(stack);
            int key = GlowOutlineRenderer.glowKeyFor(entity, GlowOutlineRenderer.CAT_ARMOR);
            for (Pending p : pending) {
                if (p.cap.count > 0 && p.tex != null) {
                    GlowOutlineRenderer.queueModelOutline(p.cap.data, p.cap.count, p.tex, modelView, color,
                            key, GlowOutlineRenderer.CAT_ARMOR);
                }
            }
        } finally {
            pending.clear();
        }
    }
}
