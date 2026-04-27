package com.example.examplemod.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import com.example.examplemod.glint.CustomGlintData;
import com.example.examplemod.glint.CustomGlintNbt;
import com.example.examplemod.glint.CustomGlintRenderTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Core mixin — intercepts {@link ItemRenderer#render} to inject custom per-item glint rendering.
 *
 * <h3>Full render flow</h3>
 * <ol>
 *   <li>{@link ItemRenderer#render} is called by Minecraft to draw an item.</li>
 *   <li>{@code @Inject HEAD} captures the {@link ItemStack} into {@link #CURRENT_STACK} so it is
 *       accessible inside the redirected helper calls below (which don't receive the stack).</li>
 *   <li>Vanilla internally calls {@code getFoilBuffer} or {@code getFoilBufferDirect} to build the
 *       composite {@link VertexConsumer} for the item geometry.</li>
 *   <li>{@code @Redirect} on those calls routes them to {@link #applyGlint}, which:
 *     <ul>
 *       <li>Reads {@link CustomGlintData} from the captured stack's NBT.</li>
 *       <li>If a custom glint is present: computes the current animated color, writes the texture
 *           and color into {@link CustomGlintRenderTypes}'s ThreadLocals, acquires the pre-allocated
 *           glint {@link com.mojang.blaze3d.vertex.BufferBuilder} via {@code buffer.getBuffer(FIXED)},
 *           and returns a {@link VertexMultiConsumer} that writes item geometry to both the base
 *           layer and the glint layer simultaneously.</li>
 *       <li>If no custom glint: falls through to vanilla {@code getFoilBuffer}/{@code getFoilBufferDirect}
 *           unmodified.</li>
 *     </ul>
 *   </li>
 *   <li>{@code @Inject RETURN} clears {@link #CURRENT_STACK} after the render call completes.</li>
 * </ol>
 *
 * <h3>Dual SRG / named targets</h3>
 * Every inject and redirect targets both the obfuscated SRG method name (e.g. {@code m_115143_})
 * and the deobfuscated named signature. {@code require=0} on all of them means neither target is
 * mandatory — if one doesn't match (e.g. you're in a dev environment where only the named form exists,
 * or in production where only the SRG form exists), the other handles it. This prevents the mod from
 * crashing if either mapping is absent.
 */
@Mixin(ItemRenderer.class)
public class ItemRendererMixin {

    /**
     * Holds the {@link ItemStack} currently being rendered, set at the entry of
     * {@link ItemRenderer#render} and cleared on exit.
     *
     * <p>ThreadLocal because rendering can theoretically be called from multiple threads
     * (though in practice it runs on the render thread). Using ThreadLocal avoids any
     * static-field race condition.
     */
    private static final ThreadLocal<ItemStack> CURRENT_STACK = new ThreadLocal<>();

    /**
     * Reusable float[4] RGBA buffer per thread — avoids allocating a new array every frame
     * for every item that has a custom glint.
     */
    private static final ThreadLocal<float[]> COLOR_BUF = ThreadLocal.withInitial(() -> new float[4]);

    // ── Stack capture (HEAD) ──────────────────────────────────────────────────

    /** SRG target: captures the stack before render begins (obfuscated environments). */
    @Inject(method = "m_115143_", at = @At("HEAD"), require = 0)
    private void cg_captureStack_srg(ItemStack pItemStack, ItemDisplayContext pDisplayContext,
            boolean pLeftHand, PoseStack pPoseStack, MultiBufferSource pBuffer,
            int pCombinedLight, int pCombinedOverlay, BakedModel pModel, CallbackInfo ci) {
        CURRENT_STACK.set(pItemStack);
    }

    /** Named target: captures the stack before render begins (dev/deobf environments). */
    @Inject(
        method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
        at = @At("HEAD"), require = 0, remap = false
    )
    private void cg_captureStack_named(ItemStack pItemStack, ItemDisplayContext pDisplayContext,
            boolean pLeftHand, PoseStack pPoseStack, MultiBufferSource pBuffer,
            int pCombinedLight, int pCombinedOverlay, BakedModel pModel, CallbackInfo ci) {
        CURRENT_STACK.set(pItemStack);
    }

    // ── Stack clear (RETURN) ─────────────────────────────────────────────────

    /** SRG target: clears the captured stack after render completes. */
    @Inject(method = "m_115143_", at = @At("RETURN"), require = 0)
    private void cg_clearStack_srg(CallbackInfo ci) {
        CURRENT_STACK.remove();
    }

    /** Named target: clears the captured stack after render completes. */
    @Inject(
        method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
        at = @At("RETURN"), require = 0, remap = false
    )
    private void cg_clearStack_named(CallbackInfo ci) {
        CURRENT_STACK.remove();
    }

    // ── getFoilBuffer redirects ───────────────────────────────────────────────
    // getFoilBuffer is called for batched (indirect) rendering — items in world, item frames, etc.

    /** SRG target: redirects getFoilBuffer in obfuscated environments. */
    @Redirect(
        method = "m_115143_", require = 0,
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;m_115211_(Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/renderer/RenderType;ZZ)Lcom/mojang/blaze3d/vertex/VertexConsumer;")
    )
    private VertexConsumer cg_redirectFoilBuffer_srg(MultiBufferSource buffer, RenderType renderType,
            boolean isItem, boolean hasFoil) {
        return applyGlint(buffer, renderType, isItem, hasFoil, false);
    }

    /** Named target: redirects getFoilBuffer in dev/deobf environments. */
    @Redirect(
        method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
        require = 0, remap = false,
        at = @At(value = "INVOKE", remap = false,
            target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;getFoilBuffer(Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/renderer/RenderType;ZZ)Lcom/mojang/blaze3d/vertex/VertexConsumer;")
    )
    private VertexConsumer cg_redirectFoilBuffer_named(MultiBufferSource buffer, RenderType renderType,
            boolean isItem, boolean hasFoil) {
        return applyGlint(buffer, renderType, isItem, hasFoil, false);
    }

    // ── getFoilBufferDirect redirects ─────────────────────────────────────────
    // getFoilBufferDirect is called for direct (GUI / immediate-mode) rendering.

    /** SRG target: redirects getFoilBufferDirect in obfuscated environments. */
    @Redirect(
        method = "m_115143_", require = 0,
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;m_115222_(Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/renderer/RenderType;ZZ)Lcom/mojang/blaze3d/vertex/VertexConsumer;")
    )
    private VertexConsumer cg_redirectFoilBufferDirect_srg(MultiBufferSource buffer, RenderType renderType,
            boolean noEntity, boolean withGlint) {
        return applyGlint(buffer, renderType, noEntity, withGlint, true);
    }

    /** Named target: redirects getFoilBufferDirect in dev/deobf environments. */
    @Redirect(
        method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
        require = 0, remap = false,
        at = @At(value = "INVOKE", remap = false,
            target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;getFoilBufferDirect(Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/renderer/RenderType;ZZ)Lcom/mojang/blaze3d/vertex/VertexConsumer;")
    )
    private VertexConsumer cg_redirectFoilBufferDirect_named(MultiBufferSource buffer, RenderType renderType,
            boolean noEntity, boolean withGlint) {
        return applyGlint(buffer, renderType, noEntity, withGlint, true);
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Core logic: if the currently-rendering stack has a {@code custom_glint} tag, injects the
     * custom glint layer. Otherwise delegates to vanilla.
     *
     * <p>When a custom glint IS present:
     * <ol>
     *   <li>Computes the current animated color from the glint's color array + game time.</li>
     *   <li>Calls {@link CustomGlintRenderTypes#forGlint} which returns a {@link RenderType} specific
     *       to this glint config, updating its closed-over color holder with the current frame color.
     *       The RenderType is created on first use and registered into {@code fixedBuffers} so it has
     *       its own dedicated {@link com.mojang.blaze3d.vertex.BufferBuilder}.</li>
     *   <li>Returns {@link VertexMultiConsumer#create(VertexConsumer...)} combining the per-config
     *       glint buffer and the item's base buffer — so geometry lands in both simultaneously.</li>
     * </ol>
     *
     * @param isDirect {@code true} when called from the {@code getFoilBufferDirect} path (GUI rendering);
     *                 {@code false} for the batched {@code getFoilBuffer} path.
     */
    private static VertexConsumer applyGlint(MultiBufferSource buffer, RenderType renderType,
            boolean firstBool, boolean secondBool, boolean isDirect) {
        ItemStack stack = CURRENT_STACK.get();
        if (stack != null) {
            CustomGlintData glint = CustomGlintNbt.read(stack);
            if (glint != null) {
                int color = computeAnimatedColor(glint);

                // Decompose packed ARGB into normalized floats. Alpha is ignored per the glint contract
                // (alpha channel of color ints is unused; the shader renders at full opacity).
                float[] buf = COLOR_BUF.get();
                buf[0] = ((color >> 16) & 0xFF) / 255.0f; // R
                buf[1] = ((color >> 8) & 0xFF) / 255.0f;  // G
                buf[2] = (color & 0xFF) / 255.0f;          // B
                buf[3] = 1.0f;                              // A — always opaque

                VertexConsumer glintConsumer = buffer.getBuffer(CustomGlintRenderTypes.forGlint(glint, buf));
                VertexConsumer itemConsumer = buffer.getBuffer(renderType);

                // Write item geometry to both buffers at once: base layer + glint overlay.
                return VertexMultiConsumer.create(glintConsumer, itemConsumer);
            }
        }

        // No custom glint — delegate to vanilla foil behavior unchanged.
        return isDirect
            ? ItemRenderer.getFoilBufferDirect(buffer, renderType, firstBool, secondBool)
            : ItemRenderer.getFoilBuffer(buffer, renderType, firstBool, secondBool);
    }

    /**
     * Computes the ARGB color to use for the glint this frame based on game time.
     *
     * <ul>
     *   <li>Single color: returned immediately — no time math.</li>
     *   <li>Multi-color, {@code interpolate=false}: selects the color whose slot the current tick falls in.</li>
     *   <li>Multi-color, {@code interpolate=true}: linearly lerps RGB between the current and next color
     *       using the fractional position within the current slot.</li>
     * </ul>
     *
     * <p>Full cycle length = {@code (20 * colors.length) / speed} ticks.
     * At {@code speed=1.0}: 20 ticks per color. {@code speed=2.0}: 10 ticks/color. {@code speed=0.5}: 40 ticks/color.
     */
    private static int computeAnimatedColor(CustomGlintData glint) {
        int[] colors = glint.colors();
        if (colors.length == 1) return colors[0];

        Minecraft mc = Minecraft.getInstance();
        long gameTime = mc.level != null ? mc.level.getGameTime() : 0;

        // Total ticks for one full cycle through all colors.
        float totalTicks = (20.0f * colors.length) / glint.speed();

        // t in [0, colors.length): fractional position across the color array.
        float t = (gameTime % Math.max(1L, (long) totalTicks)) / totalTicks * colors.length;
        int idx = (int) t % colors.length;

        if (!glint.interpolate()) return colors[idx];

        // Lerp RGB between colors[idx] and colors[idx+1] using the fractional part of t.
        float frac = t - (int) t;
        int c1 = colors[idx];
        int c2 = colors[(idx + 1) % colors.length];
        int r = (int)(((c1 >> 16) & 0xFF) * (1 - frac) + ((c2 >> 16) & 0xFF) * frac);
        int g = (int)(((c1 >> 8) & 0xFF) * (1 - frac) + ((c2 >> 8) & 0xFF) * frac);
        int b = (int)((c1 & 0xFF) * (1 - frac) + (c2 & 0xFF) * frac);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
