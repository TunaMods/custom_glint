package net.tunamods.customglint.module.compat.iceandfire.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-only glue shared by the three IaF armor layer mixins (dragon, hippogryph, hippocampus).
 * All three re-render the layer's parent entity model with our glint render types after IaF has
 * drawn its own base armor pass, and all three have to reach that model and the mount's armor tier
 * reflectively. The mixins keep everything that differs: the injection points, how the armor
 * texture is resolved, and the depth setup of the base draw.
 *
 * EntityModel&lt;?&gt; access: cannot @Shadow getParentEntityModel&lt;?&gt; because the mixin can't declare
 * an extends clause onto RenderLayer&lt;EntityDragonBase, ...&gt; without compile-time access to those
 * types. Resolve via reflection by return-type match on RenderLayer's no-arg method.
 */
public final class IceAndFireArmorGlint {
    private IceAndFireArmorGlint() {}

    private static volatile Method GET_PARENT_MODEL;
    /** getArmor() keyed per concrete entity class: hippogryph and hippocampus declare their own. */
    private static final Map<Class<?>, Method> GET_ARMOR = new ConcurrentHashMap<>();

    /** The RenderLayer's parent model, or null if the reflective lookup failed. */
    public static EntityModel<?> parentModel(Object layer) {
        try {
            Method m = GET_PARENT_MODEL;
            if (m == null) {
                Class<?> cls = Class.forName("net.minecraft.client.renderer.entity.layers.RenderLayer");
                for (Method mm : cls.getDeclaredMethods()) {
                    if (mm.getParameterCount() == 0
                            && mm.getReturnType().getName().equals("net.minecraft.client.model.EntityModel")) {
                        mm.setAccessible(true);
                        m = mm;
                        break;
                    }
                }
                GET_PARENT_MODEL = m;
            }
            return m == null ? null : (EntityModel<?>) m.invoke(layer);
        } catch (Throwable t) {
            return null;
        }
    }

    /** IaF's armor tier for a mount: 0 none, 1 iron, 2 gold, 3 diamond. 0 if the lookup failed. */
    public static int armorTier(Object entity) {
        try {
            Method m = GET_ARMOR.get(entity.getClass());
            if (m == null) {
                m = entity.getClass().getMethod("getArmor");
                GET_ARMOR.put(entity.getClass(), m);
            }
            return (int) m.invoke(entity);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Fan the glint layers out onto {@code model}. Callers pass the UNWRAPPED buffer source so the
     * mount's own body glint can't be re-fanned onto the armor, and must already have drawn the base
     * armor through armorCutoutNoCull so forArmorGlint's EQUAL test lands on the armor texels.
     */
    public static void drawArmorGlint(CustomGlint.Data glint, EntityModel<?> model, PoseStack pose,
            MultiBufferSource flush, int light) {
        CustomGlint.Layer[] layers = glint.layers();
        float[] buf = CustomGlintRenderer.COLOR_BUF.get();
        List<VertexConsumer> list = new ArrayList<>();
        for (int li = 0; li < layers.length; li++) {
            // Chromatic carries no design PNG, so forArmorGlint's texture lookup returns null and the
            // layer would drop with no warning. forChromaticArmorGlint is its depth twin: EQUAL +
            // VIEW_OFFSET_Z_LAYERING, matching the armorCutoutNoCull the base armor draws through.
            if (CustomGlint.isChromatic(layers[li])) {
                RenderType crt = CustomGlintRenderer.forChromaticArmorGlint(glint, li);
                if (crt != null) list.add(CustomGlintRenderer.chromaticWorldBuffer(flush, crt));
                continue;
            }
            int[] colors = layers[li].colors();
            if (layers[li].simultaneous()) {
                for (int i = 0; i < colors.length; i++) {
                    CustomGlintRenderer.fillPremul(buf, colors[i]);
                    RenderType rt = CustomGlintRenderer.forArmorGlint(glint, li, buf, i);
                    if (rt != null) list.add(flush.getBuffer(rt));
                }
            } else {
                CustomGlintRenderer.fillPremul(buf, CustomGlintRenderer.computeAnimatedColor(glint, li));
                RenderType rt = CustomGlintRenderer.forArmorGlint(glint, li, buf, 0);
                if (rt != null) list.add(flush.getBuffer(rt));
            }
        }
        if (list.isEmpty()) return;
        VertexConsumer combined = list.size() == 1 ? list.get(0)
                : VertexMultiConsumer.create(list.toArray(new VertexConsumer[0]));
        model.renderToBuffer(pose, combined, light, OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f);
    }
}
