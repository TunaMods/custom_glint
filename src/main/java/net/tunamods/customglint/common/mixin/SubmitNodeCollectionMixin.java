package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlintCarrier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ENTITY per-layer glint. Every entity model — the base body AND every {@code RenderLayer} surface (sheep
 * wool, slime outer cube, saddle, stray clothing, …) — is submitted through {@code submitModel}, but
 * RenderLayers go via {@code collector.order(n).submitModel(...)}, which lands on
 * {@link SubmitNodeCollection#submitModel} (NOT {@code SubmitNodeStorage.submitModel}, which merely
 * delegates to {@code order(0)} — see {@code SubmitNodeStorageMixin}). So the universal sink for body +
 * layers is here.
 *
 * <p>We submit matching glint nodes for each entity-surface layer model — the 26.1 replacement for the
 * 1.21.1 {@code GlintWrappingBufferSource} fan-out, which wrapped every {@code entity_*} RenderType. The
 * BASE body is glinted directly by {@code LivingEntityRendererMixin}, so it's skipped here via the
 * current-body-model guard (it also flows through this sink). Gated on a real entity surface RT
 * ({@link EntityGlintRender#isEntitySurface}, excludes armor + our own glint RTs) and on the entity
 * carrying glint data. Re-entrancy guarded so the glint nodes we submit (which call {@code submitModel}
 * again) don't loop.
 *
 * <p>Per-layer glow OUTLINE rides the in-phase tee in {@code ModelFeatureRendererMixin}, not this hook.
 */
@Mixin(SubmitNodeCollection.class)
public class SubmitNodeCollectionMixin {

    @Inject(method = "submitModel", at = @At("HEAD"), require = 0)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void cg_layerGlint(Model model, Object state, PoseStack poseStack, RenderType renderType,
            int lightCoords, int overlayCoords, int tintedColor, TextureAtlasSprite sprite, int outlineColor,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay, CallbackInfo ci) {
        if (cg_addingGlint) return;                              // our own glint nodes — don't recurse
        if (GlintCarrier.SUBMIT_GLINT.get() != null) return;     // inside an item submit — handled elsewhere
        if (!(state instanceof EntityRenderState ers) || !(model instanceof EntityModel)) return;
        if (model == EntityGlintRender.currentBodyModel()) return; // body is glinted by LivingEntityRendererMixin
        if (!EntityGlintRender.isEntitySurface(renderType)) return;
        EntityGlintRender.Resolution res = ers.getRenderData(EntityGlintRender.RENDER_DATA);
        if (res == null || res.data == null) return;
        cg_addingGlint = true;
        try {
            EntityGlintRender.submitEntityGlint((OrderedSubmitNodeCollector) (Object) this,
                    (EntityModel<?>) model, state, poseStack, lightCoords, res.data);
        } finally {
            cg_addingGlint = false;
        }
    }

    /** Guards against re-entering this hook while submitting our own glint nodes (which call
     *  {@code submitModel} again). Render thread only. */
    @Unique
    private static boolean cg_addingGlint = false;
}
