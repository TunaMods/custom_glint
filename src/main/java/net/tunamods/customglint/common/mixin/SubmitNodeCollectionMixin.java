package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlintCarrier;

import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ENTITY per-layer glint. Every entity model, the base body AND every {@code RenderLayer} surface (sheep
 * wool, slime outer cube, saddle, stray clothing, …), is submitted through {@code submitModel}, but
 * RenderLayers go via {@code collector.order(n).submitModel(...)}, which lands on
 * {@link SubmitNodeCollection#submitModel} (NOT {@code SubmitNodeStorage.submitModel}, which merely
 * delegates to {@code order(0)}, see {@code SubmitNodeStorageMixin}). So the universal sink for body +
 * layers is here.
 *
 * <p>We submit matching glint nodes for each entity-surface layer model, the 26.1 replacement for the
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
        if (cg_addingGlint) return;                              // our own glint nodes, don't recurse
        if (GlintCarrier.SUBMIT_GLINT.get() != null) return;     // inside an item submit, handled elsewhere
        if (!(state instanceof EntityRenderState ers) || !(model instanceof EntityModel)) return;
        if (model == EntityGlintRender.currentBodyModel()) return; // body is glinted by LivingEntityRendererMixin
        if (!EntityGlintRender.isEntitySurface(renderType)) return;
        EntityGlintRender.Resolution res = ers.getRenderData(EntityGlintRender.RENDER_DATA);
        if (res == null || res.data == null) return;
        cg_addingGlint = true;
        try {
            // null cutout texture → the chromatic overlay draws the full layer mesh, matching how the glow
            // path silhouettes these RenderLayer surfaces (white.png full shape, their hull IS the shape).
            EntityGlintRender.submitEntityGlint((OrderedSubmitNodeCollector) (Object) this,
                    (EntityModel<?>) model, state, poseStack, lightCoords, res.data, null);
        } finally {
            cg_addingGlint = false;
        }
    }

    /** Guards against re-entering this hook while submitting our own glint nodes (which call
     *  {@code submitModel} again). Render thread only. */
    @Unique
    private static boolean cg_addingGlint = false;

    /**
     * GLINT + GLOW for block-model entity layers, mooshroom mushrooms, snow-golem pumpkin, and any modded
     * layer that submits a {@code BlockModelRenderState}. These funnel through {@code submitBlockModel}, the
     * universal block sink, which (unlike {@code submitModel}) carries no entity state, so
     * {@link EntityGlintRender#submitBlockLayerGlintGlow} reads the owning entity from the thread-local
     * published by {@code LivingEntityRendererMixin} for the submit span. Skipped inside an item submit (held
     * block items resolve on the item path). The glint is emitted via {@code submitCustomGeometry} (the block
     * sink can't carry our per-vertex glint colour); the glow rides the shared item-quad outline path.
     */
    @Inject(method = "submitBlockModel", at = @At("HEAD"), require = 0)
    private void cg_blockLayerGlintGlow(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> modelParts,
            int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor, CallbackInfo ci) {
        if (GlintCarrier.SUBMIT_GLINT.get() != null) return; // item submit context, handled on the item path
        if (EntityGlintRender.currentEntity() == null) return; // non-entity block submit (the common case), nothing to do
        EntityGlintRender.submitBlockLayerGlintGlow((OrderedSubmitNodeCollector) (Object) this,
                poseStack, modelParts, lightCoords, overlayCoords);
    }
}
