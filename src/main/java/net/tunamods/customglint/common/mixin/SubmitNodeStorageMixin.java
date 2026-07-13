package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.EntityGlintRender;
import net.tunamods.customglint.common.client.GlintCarrier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Glow outline for special-renderer 3D items (shield, trident, and other items drawn through a
 * {@code SpecialModelRenderer} rather than as baked quads).
 *
 * <p>Quad items go through {@code submitItem} → {@code ItemFeatureRenderer.renderItem}, which
 * {@code ItemRendererMixin} hooks to queue their outline. Special items instead submit a
 * {@code submitModel} (shield) or {@code submitModelPart} (trident) from inside
 * {@code ItemStackRenderState.LayerRenderState.submit}, so they never reach that hook, which is why
 * their glow showed nothing. Every {@code submitModel}/{@code submitModelPart} overload funnels through
 * the two abstract methods on {@link SubmitNodeStorage}, so hooking those at HEAD catches all of them.
 *
 * <p>The capture is gated on the item-glow context published by {@code ItemStackRenderStateMixin} for
 * the duration of {@code ItemStackRenderState.submit}: {@link GlintCarrier#SUBMIT_GLOWING} /
 * {@link GlintCarrier#SUBMIT_GLOW_COLORS} are non-null only inside an item submit, and a special
 * renderer's model/part submissions are the only model nodes produced there, so entity and armor model
 * submits (which happen outside item submit) are never captured. The silhouette uses white.png (full
 * model shape), matching the 1.21.1 BEWLR outline; it's queued for the shared AfterWeather /
 * hand-render drain in {@link EntityGlintRender}.
 *
 * <p>The same hooks also draw the item's animated GLINT (not just the glow outline). In 26.1 the foil is
 * gated on enchantment ({@code SpecialModelWrapper.update} reads {@code ItemStack.hasFoil()}), so a glinted
 * but unenchanted trident/shield never reaches the foil draw. Rather than force the foil on and replace it
 * (the quad-item path, which has no analog here, the trident routes through a 4-arg {@code getFoilBuffer}
 * and the shield through a separate {@code entityGlint} node), we draw our own glint geometry directly:
 * when an item glint is present we submit extra model/part nodes through our glint RenderTypes, mirroring
 * how entity/armor glint is submitted. Re-entrancy is guarded ({@link #cg_addingGlint}) and the glint is
 * added once per item submit ({@link #cg_firstGlintForToken()}), so a shield's base + pattern + foil
 * submits don't stack several glint passes.
 *
 * <p>ENTITY per-layer glint is NOT here, RenderLayers submit via {@code collector.order(n).submitModel},
 * which lands on {@code SubmitNodeCollection.submitModel}, not this {@code SubmitNodeStorage.submitModel}
 * (the latter just delegates to {@code order(0)}). See {@code SubmitNodeCollectionMixin}.
 */
@Mixin(SubmitNodeStorage.class)
public class SubmitNodeStorageMixin {

    @Inject(method = "submitModel", at = @At("HEAD"), cancellable = true, require = 0)
    private void cg_captureSpecialModel(Model model, Object state, PoseStack poseStack, RenderType renderType,
            int lightCoords, int overlayCoords, int tintedColor, TextureAtlasSprite sprite, int outlineColor,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay, CallbackInfo ci) {
        if (cg_addingGlint) return; // our own glint nodes, submitted below, don't recurse
        // Consume vanilla foil: a special renderer (shield) submits its enchantment glint as a SEPARATE
        // model node in a vanilla glint RenderType. When our glint is present we draw our own, so drop
        // vanilla's node here to avoid a doubled shimmer. (The shield's base/pattern nodes use cutout
        // RenderTypes, not glint, so only the foil node is cancelled.)
        if (GlintCarrier.SUBMIT_GLINT.get() != null && cg_isVanillaGlint(renderType)) {
            ci.cancel();
            return;
        }
        if (cg_glowingItem()) {
            EntityGlintRender.queueSpecialModelOutline(model, state, poseStack.last(), lightCoords,
                    GlintCarrier.SUBMIT_GLINT.get(), cg_glowingFlag(), GlintCarrier.SUBMIT_GLOW_COLORS.get(),
                    cg_glowSpeed(), cg_glowInterp());
        }
        CustomGlint.Data glint = GlintCarrier.SUBMIT_GLINT.get();
        if (glint != null && cg_firstGlintForToken()) {
            cg_addingGlint = true;
            try {
                EntityGlintRender.submitSpecialModelGlint((SubmitNodeCollector) (Object) this, model, state,
                        poseStack, lightCoords, glint);
            } finally {
                cg_addingGlint = false;
            }
        }
    }

    @Inject(method = "submitModelPart", at = @At("HEAD"), require = 0)
    private void cg_captureSpecialPart(ModelPart modelPart, PoseStack poseStack, RenderType renderType,
            int lightCoords, int overlayCoords, TextureAtlasSprite sprite, boolean sheeted, boolean hasFoil,
            int tintedColor, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay, int outlineColor,
            CallbackInfo ci) {
        if (cg_addingGlint) return; // our own glint nodes, submitted below, don't recurse
        if (cg_glowingItem()) {
            EntityGlintRender.queueSpecialPartOutline(modelPart, poseStack.last(), lightCoords,
                    GlintCarrier.SUBMIT_GLINT.get(), cg_glowingFlag(), GlintCarrier.SUBMIT_GLOW_COLORS.get(),
                    cg_glowSpeed(), cg_glowInterp());
        }
        CustomGlint.Data glint = GlintCarrier.SUBMIT_GLINT.get();
        if (glint != null && cg_firstGlintForToken()) {
            cg_addingGlint = true;
            try {
                EntityGlintRender.submitSpecialPartGlint((SubmitNodeCollector) (Object) this, modelPart,
                        poseStack, lightCoords, glint);
            } finally {
                cg_addingGlint = false;
            }
        }
    }

    /**
     * Consume vanilla foil on the trident: its foil rides the {@code hasFoil} flag of its single
     * {@code submitModelPart} (not a separate node), so when our glint is present we force the flag false,
     * the base part still renders but {@code ModelPartFeatureRenderer} skips the vanilla glint buffer.
     * {@code argsOnly} ordinal 1 = {@code hasFoil} (ordinal 0 is {@code sheeted}). Skipped while we submit
     * our own glint parts (those pass {@code hasFoil=false} already).
     */
    @ModifyVariable(method = "submitModelPart", at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0)
    private boolean cg_consumeTridentFoil(boolean hasFoil) {
        if (cg_addingGlint) return hasFoil;
        return hasFoil && GlintCarrier.SUBMIT_GLINT.get() == null;
    }

    /** True for vanilla's enchantment-glint RenderTypes (the foil sheets). Identity compare against the
     *  three memoized singletons, our own glint RTs are never these. */
    @Unique
    private static boolean cg_isVanillaGlint(RenderType rt) {
        return rt == RenderTypes.entityGlint() || rt == RenderTypes.glint() || rt == RenderTypes.glintTranslucent();
    }

    /** Guards against re-entering these hooks while submitting our own glint nodes (which call
     *  {@code submitModel}/{@code submitModelPart} again). Render thread only. */
    @Unique
    private static boolean cg_addingGlint = false;

    /** Per-item-submit token of the last submit we added glint for. A special renderer can submit several
     *  model/part nodes per item (shield: base + patterns + foil); glinting only the first keeps one glint
     *  pass over the item shape instead of stacking one per node. */
    @Unique
    private static final ThreadLocal<Object> cg_glintedToken = new ThreadLocal<>();

    @Unique
    private static boolean cg_firstGlintForToken() {
        Object tok = GlintCarrier.SUBMIT_TOKEN.get();
        if (tok == null) return true;            // no token (shouldn't happen inside an item submit)
        if (tok == cg_glintedToken.get()) return false;
        cg_glintedToken.set(tok);
        return true;
    }

    @Unique
    private static boolean cg_glowingFlag() {
        Boolean g = GlintCarrier.SUBMIT_GLOWING.get();
        return g != null && g;
    }

    /** True only inside an {@code ItemStackRenderState.submit} for a glowing item (glow flag or colours). */
    @Unique
    private static boolean cg_glowingItem() {
        int[] gc = GlintCarrier.SUBMIT_GLOW_COLORS.get();
        return cg_glowingFlag() || (gc != null && gc.length > 0);
    }

    /** The submitting item's stored glow-cycle speed / interpolation (default 1.0 / true when unset). */
    @Unique
    private static float cg_glowSpeed() {
        Float s = GlintCarrier.SUBMIT_GLOW_SPEED.get();
        return s != null ? s : 1.0f;
    }

    @Unique
    private static boolean cg_glowInterp() {
        Boolean i = GlintCarrier.SUBMIT_GLOW_INTERP.get();
        return i == null || i;
    }
}
