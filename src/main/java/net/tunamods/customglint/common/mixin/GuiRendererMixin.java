package net.tunamods.customglint.common.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
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
 * <p>Instead we add ONE blit of the slot texture through {@link GlintPipelines#GUI_ITEM_OUTLINE} on a quad
 * GROWN by {@link #OUTLINE_MARGIN} item-pixels (scissor grown to match), whose shader does a uniform outward
 * dilation of the item: it emits the flat glow colour in the {@code OUTLINE_MARGIN}-px border around the
 * silhouette and discards over the icon itself, so drawing on top is fine. Every sample maps back into the
 * item's own slot, so the halo can't read a neighbouring icon. Order-independent — works despite the 26.1
 * GUI sorting blits by pipeline rather than submission order.
 *
 * <p>Glow comes off the item's render state (set by {@code ItemModelResolverMixin}); the colour resolves
 * like everywhere else: glow colours first (animated), then glint layer 0, else white.
 */
@Mixin(GuiRenderer.class)
public class GuiRendererMixin {

    /** Halo thickness in ITEM pixels — the blit quad is grown by this much on every side to give the outward
     *  halo room outside the 16x16 icon. MUST equal {@code MARGIN} in {@code core/gui_item_outline.fsh}
     *  (the shader derives its SCALE and dilation radius from the same value). Tune both together. */
    private static final int OUTLINE_MARGIN = 1;

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

        // Pack guiScale into the colour's alpha byte. The shader needs the slot UV size s; the atlas slot is
        // ALWAYS 16*guiScale texels, so s = 16*guiScale/atlasSize can be computed EXACTLY shader-side from just
        // guiScale — no fwidth, no rounding, no clamp. (The earlier scheme packed the quad's on-screen px size
        // and recovered s via fwidth; on the big zoomed wand preview that value overflowed 7 bits, clamped, and
        // corrupted s, which tore the outline into cross lines. guiScale is small and exact.)
        int guiScale = Math.max(1, Math.min(127, (int) Minecraft.getInstance().getWindow().getGuiScale()));
        color = (color & 0x00FFFFFF) | (guiScale << 24);

        TextureSetup tex = TextureSetup.singleTexture(slotView.textureView(),
                RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
        // Grow the blit quad by OUTLINE_MARGIN item-pixels on every side so the outward halo has room to draw
        // OUTSIDE the 16x16 icon. UVs stay pinned to the atlas slot, so the icon appears at SCALE inside the
        // bigger quad and the shader fills the surrounding border. (x/y are pose-local; the pose scales them.)
        int x0 = itemState.x() - OUTLINE_MARGIN, y0 = itemState.y() - OUTLINE_MARGIN;
        int x1 = itemState.x() + 16 + OUTLINE_MARGIN, y1 = itemState.y() + 16 + OUTLINE_MARGIN;
        // Clip the halo to the item's on-screen SLOT BOUNDS so it can't bleed past the slot into neighbouring
        // slots or other UI. bounds() is the 16x16 item rect transformed by the pose and already intersected
        // with the active menu scissor — i.e. exactly the slot the icon occupies on screen. The outward halo
        // still shows because item sprites carry a transparent border inside their 16x16, leaving room within
        // the slot; a sprite that bleeds to the very edge simply loses the halo on that edge rather than
        // spilling over. Falls back to scissorArea() if bounds() is somehow absent.
        ScreenRectangle scissor = itemState.bounds() != null ? itemState.bounds() : itemState.scissorArea();
        renderState.addBlitToCurrentLayer(new BlitRenderState(
                GlintPipelines.GUI_ITEM_OUTLINE, tex, itemState.pose(),
                x0, y0, x1, y1,
                slotView.u0(), slotView.u1(), slotView.v0(), slotView.v1(), color, scissor));
    }
}
