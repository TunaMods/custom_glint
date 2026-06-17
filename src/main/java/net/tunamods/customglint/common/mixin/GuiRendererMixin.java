package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CgGlintHolder;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.common.client.GlintPipelines;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory-icon glow halo: a ring around a glowing item's GUI icon, in the item's glow colour(s). The
 * 26.1 analog of 1.21.1's {@code doGuiItemOutline} (which drew 4 one-pixel-offset copies of the item via
 * {@code ItemRenderer.render}). That immediate path is gone — GUI items render into a cached
 * {@link GuiItemAtlas} slot and are blitted — AND the 26.1 GUI sorts blits by (scissor, pipeline), not
 * submission order, so a "behind the item" copy can't be guaranteed.
 *
 * <p>Instead we add ONE blit of the slot texture through {@link GlintPipelines#GUI_ITEM_OUTLINE}, whose
 * shader emits the flat glow colour only in a 1-px ring JUST OUTSIDE the item silhouette (interior texels
 * discard). Since it never covers the icon, drawing on top is fine. It is scissored to the item's 16×16
 * slot so the ring can't bleed into neighbouring slots.
 *
 * <p>Glow comes off the item's render state (set by {@code ItemModelResolverMixin}); the colour resolves
 * like everywhere else: glow colours first (animated), then glint layer 0, else white.
 */
@Mixin(GuiRenderer.class)
public class GuiRendererMixin {

    @Shadow @Final private GuiRenderState renderState;

    @Inject(method = "submitBlitFromItemAtlas", at = @At("HEAD"), require = 0)
    private void cg_itemGlowHalo(GuiItemRenderState itemState, GuiItemAtlas.SlotView slotView, CallbackInfo ci) {
        ItemStackRenderState rs = itemState.itemStackRenderState();
        if (!(rs instanceof CgGlintHolder holder)) return;
        boolean glowing = holder.customglint$isGlowing();
        int[] gc = holder.customglint$getGlowColors();
        boolean hasColors = gc != null && gc.length > 0;
        if (!glowing && !hasColors) return;

        int color;
        if (hasColors) {
            color = CustomGlintRenderer.computeAnimatedGlowColor(gc);
        } else {
            CustomGlint.Data glint = holder.customglint$getGlint();
            color = glint != null ? CustomGlintRenderer.computeAnimatedColor(glint, 0) : 0xFFFFFFFF;
        }

        // Pack the atlas SLOT pixel size (= 16 * guiScale, GuiRenderer.prepareItemAtlas) into the colour's
        // alpha byte so the outline shader can clamp its taps to THIS item's slot and never sample the
        // neighbouring icon (the GUI atlas packs slots edge-to-edge, no gutter). The shader reconstructs
        // slotUvSize = slotTextureSize * (1 atlas texel in UV) exactly — robust at ANY guiScale, unlike
        // packing 1/slotUvSize, which is NON-integer at e.g. guiScale 3 (48px slots in a power-of-two atlas)
        // and produced the slot-edge bleed. The halo is drawn opaque, so the alpha channel is free to carry it.
        int slotTextureSize = Math.max(1, Math.min(255, 16 * (int) Minecraft.getInstance().getWindow().getGuiScale()));
        color = (color & 0x00FFFFFF) | (slotTextureSize << 24);

        TextureSetup tex = TextureSetup.singleTexture(slotView.textureView(),
                RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
        int x0 = itemState.x(), y0 = itemState.y(), x1 = x0 + 16, y1 = y0 + 16;
        // Clip EXACTLY like vanilla's own item blit (GuiRenderer.submitBlitFromItemAtlas): pass the item's
        // raw scissorArea (SCREEN space). The earlier code intersected a slot rect built from the RAW x/y,
        // but those are POSE-RELATIVE — a container screen translates the pose to its top-left, so against
        // the screen-space scissor they mismatched and the clip came out empty for MENU items. That's why
        // the ring showed on the HUD hotbar (identity pose) but not in the inventory. The 16×16 blit
        // geometry + the shader's per-slot sample clamp already confine the ring; the scissor only needs to
        // match the item, so reuse the item's own scissorArea.
        renderState.addBlitToCurrentLayer(new BlitRenderState(
                GlintPipelines.GUI_ITEM_OUTLINE, tex, itemState.pose(),
                x0, y0, x1, y1,
                slotView.u0(), slotView.u1(), slotView.v0(), slotView.v1(), color, itemState.scissorArea()));
    }
}
