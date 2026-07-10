package net.tunamods.customglint.module.compat.immersivearmors.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.vertex.PoseStack;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlowOutlineRenderer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Glue for {@code ArmorPieceMixin}. Given an Immersive Armors {@code Piece} that just finished drawing,
 * re-render its (still-posed) model with our glint render types and, when the stack glows, trace the model
 * silhouette into the glow mask keyed on the wearer + {@code CAT_ARMOR} so it composes with the body ring.
 *
 * The piece's drawn model and its resolved texture are private IA members, reached reflectively (cached per
 * concrete piece class) so there is no dep on Immersive Armors:
 * <ul>
 *   <li>{@code LayerPiece} draws a shared {@code HumanoidModel} exposed via {@code getModel()};</li>
 *   <li>{@code ModelPiece} draws a {@code DecoModel} held in a private {@code model} field.</li>
 * </ul>
 * Both extend {@link Model}, so the same re-render + capture works for either. The texture comes from
 * {@code Piece.getTexture(ExtendedArmorItem, boolean)} (base layer, second-layer flag false).
 */
public final class ImmersiveArmorsGlint {
    private ImmersiveArmorsGlint() {}

    /** How to recover the drawn {@link Model} from a concrete piece class: a {@code getModel()} method
     *  (LayerPiece) or a {@code model} field (ModelPiece). {@code NONE} marks classes we can't read. */
    private static final class ModelAccess {
        static final ModelAccess NONE = new ModelAccess(null, null);
        final Method method;
        final Field field;
        ModelAccess(Method method, Field field) { this.method = method; this.field = field; }

        Model get(Object piece) {
            try {
                Object m = method != null ? method.invoke(piece) : field != null ? field.get(piece) : null;
                return m instanceof Model ? (Model) m : null;
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
    }

    private static final Map<Class<?>, ModelAccess> MODEL_ACCESS = new ConcurrentHashMap<>();
    // Resolved lazily off the first piece: Piece.getTexture(ExtendedArmorItem, boolean).
    private static volatile boolean textureLookupTried = false;
    private static Method textureMethod;

    public static void render(Object piece, PoseStack pose, MultiBufferSource buffer, int light,
                              LivingEntity entity, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomGlint.Data glint = CustomGlint.readCached(stack);
        boolean glowing = CustomGlint.isGlowing(stack);
        if (glint == null && !glowing) return;

        Model model = MODEL_ACCESS.computeIfAbsent(piece.getClass(), ImmersiveArmorsGlint::buildAccess).get(piece);
        if (model == null) return;

        if (glint != null) drawGlint(glint, model, pose, buffer, light);

        // Glow ring: re-record the piece silhouette against its real texture, keyed on the wearer +
        // CAT_ARMOR so every piece (and the body) fold into one ring — same path as vanilla armor.
        if (glowing && entity != null) {
            ResourceLocation tex = resolveTexture(piece, stack);
            if (tex != null) {
                EntityGlintRender.captureModelSilhouette(entity, model, tex, pose, light,
                        CustomGlintRenderer.resolveGlowColor(stack), GlowOutlineRenderer.CAT_ARMOR);
            }
        }
    }

    private static void drawGlint(CustomGlint.Data glint, Model model, PoseStack pose,
                                  MultiBufferSource buffer, int light) {
        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();
        List<VertexConsumer> list = new ArrayList<>();
        for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
            if (CustomGlint.isChromatic(layers[layerIdx])) {
                RenderType crt = CustomGlintRenderer.forChromaticArmorGlint(glint, layerIdx);
                if (crt != null) list.add(buffer.getBuffer(crt));
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
                    RenderType rt = CustomGlintRenderer.forArmorGlint(glint, layerIdx, buf, i);
                    if (rt != null) list.add(buffer.getBuffer(rt));
                }
            } else {
                int color = CustomGlintRenderer.computeAnimatedColor(glint, layerIdx);
                float a = ((color >> 24) & 0xFF) / 255.0f;
                buf[0] = ((color >> 16) & 0xFF) / 255.0f * a;
                buf[1] = ((color >>  8) & 0xFF) / 255.0f * a;
                buf[2] = ( color        & 0xFF) / 255.0f * a;
                buf[3] = 1.0f;
                RenderType rt = CustomGlintRenderer.forArmorGlint(glint, layerIdx, buf, 0);
                if (rt != null) list.add(buffer.getBuffer(rt));
            }
        }
        if (list.isEmpty()) return;
        VertexConsumer combined = list.size() == 1
                ? list.get(0) : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
        model.renderToBuffer(pose, combined, light, OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f);
    }

    /** Find how to read the drawn model off this piece class: prefer a no-arg {@code getModel()}
     *  (LayerPiece), fall back to a {@code model} field (ModelPiece). */
    private static ModelAccess buildAccess(Class<?> pieceClass) {
        for (Class<?> c = pieceClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("getModel") && m.getParameterCount() == 0
                        && Model.class.isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true);
                    return new ModelAccess(m, null);
                }
            }
        }
        for (Class<?> c = pieceClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals("model") && Model.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return new ModelAccess(null, f);
                }
            }
        }
        return ModelAccess.NONE;
    }

    /** {@code Piece.getTexture(ExtendedArmorItem, boolean)} → base-layer ResourceLocation. Resolved once
     *  by scanning the piece class hierarchy for the private 2-arg {@code getTexture}. */
    private static ResourceLocation resolveTexture(Object piece, ItemStack stack) {
        Method m = textureMethod;
        if (m == null) {
            if (textureLookupTried) return null;
            textureLookupTried = true;
            for (Class<?> c = piece.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method cand : c.getDeclaredMethods()) {
                    if (cand.getName().equals("getTexture") && cand.getParameterCount() == 2
                            && cand.getReturnType() == ResourceLocation.class) {
                        cand.setAccessible(true);
                        textureMethod = m = cand;
                        break;
                    }
                }
                if (m != null) break;
            }
            if (m == null) return null;
        }
        try {
            return (ResourceLocation) m.invoke(piece, stack.getItem(), Boolean.FALSE);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }
}
