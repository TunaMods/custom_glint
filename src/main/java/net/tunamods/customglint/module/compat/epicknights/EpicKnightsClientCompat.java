package net.tunamods.customglint.module.compat.epicknights;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.tunamods.customglint.common.client.CustomGlintRenderer;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Client-only EK wiring; isolated from {@link EpicKnightsCompat} so dedicated servers
 *  never resolve {@code CustomGlintRenderer} transitively. */
public final class EpicKnightsClientCompat {
    private EpicKnightsClientCompat() {}

    /**
     * EK chest textures that need a symmetrized outline-mask variant. Each entry is the
     * source texture; the synthesized variant copies cols [srcU, srcU+w) into [dstU, dstU+w)
     * over rows [v, v+h), so a flat 0-width cuboid whose two faces sample (dstU, v)-(dstU+w, v+h)
     * and (srcU, v)-(srcU+w, v+h) will see identical alpha on both faces.
     *
     * WingedHussar: wing_r1/wing_r2 cuboids are size 0×32×14 at UV (36, -14). One face samples
     * cols 36-49 (transparent) and the other samples cols 50-63 (feather shape). Mirroring
     * cols 50-63 into 36-49 produces a mask where both faces stencil-write the feather.
     */
    private record SymmetrizeOp(int srcU, int dstU, int v, int w, int h) {}
    private static final Map<ResourceLocation, SymmetrizeOp> SYMMETRIZE = Map.of(
            ResourceLocation.fromNamespaceAndPath("magistuarmory", "textures/models/armor/wingedhussarchestplate_layer_1.png"),
            new SymmetrizeOp(50, 36, 0, 14, 32)
    );

    /** Pre-baked variants, keyed by source ResourceLocation. Populated on first lookup. */
    private static final Map<ResourceLocation, ResourceLocation> CACHE = new ConcurrentHashMap<>();

    public static void wire() {
        // Halfarmor: hide arm cuboids in the outline pass.
        CustomGlintRenderer.chestArmorHidesArmsInOutline = tex ->
                tex != null
                && "magistuarmory".equals(tex.getNamespace())
                && tex.getPath().contains("halfarmor");

        // WingedHussar: substitute a symmetrized variant of the chest texture in the outline
        // RT so the wings' asymmetric-UV flat cuboids alpha-discard correctly on both faces.
        CustomGlintRenderer.armorOutlineTextureRemap = EpicKnightsClientCompat::remapForOutline;

        // WingedHussar: pull wing_r1/wing_r2 (body children) out of the standard chest outline
        // dilation — they're flat 0-width planes too far from the chest pivot, so 1.04× scale
        // ghosts them. The mixin hides them for the dilated pass and runs doTranslateOutline
        // (slot-stencil WRITE + 8× small ±dx/±dy renders) on them instead, giving a tight ring.
        CustomGlintRenderer.armorExtraOutlineParts = EpicKnightsClientCompat::resolveExtraOutlineParts;

        // Release pre-baked DynamicTextures on resource reload (source textures may have
        // changed; stale cached mask would otherwise diverge from the live armor texture).
        CustomGlintRenderer.additionalReloadCleanup.add(EpicKnightsClientCompat::releaseCache);
    }

    private static void releaseCache() {
        Minecraft mc = Minecraft.getInstance();
        for (ResourceLocation loc : CACHE.values()) {
            try { mc.getTextureManager().release(loc); } catch (Throwable ignored) {}
        }
        CACHE.clear();
    }

    private static final ResourceLocation WH_CHEST_TEX =
            ResourceLocation.fromNamespaceAndPath("magistuarmory", "textures/models/armor/wingedhussarchestplate_layer_1.png");

    private static ModelPart[] resolveExtraOutlineParts(
            HumanoidModel<?> model,
            ResourceLocation tex) {
        if (!WH_CHEST_TEX.equals(tex)) return null;
        try {
            ModelPart wr1 = model.body.getChild("wing_r1");
            ModelPart wr2 = model.body.getChild("wing_r2");
            return new ModelPart[]{wr1, wr2};
        } catch (Exception ignored) {
            return null;
        }
    }

    private static ResourceLocation remapForOutline(ResourceLocation original) {
        if (original == null) return null;
        SymmetrizeOp op = SYMMETRIZE.get(original);
        if (op == null) return original;
        return CACHE.computeIfAbsent(original, src -> bake(src, op));
    }

    private static ResourceLocation bake(ResourceLocation src, SymmetrizeOp op) {
        Minecraft mc = Minecraft.getInstance();
        NativeImage img;
        try {
            var res = mc.getResourceManager().getResource(src);
            if (res.isEmpty()) return src;
            try (InputStream in = res.get().open()) {
                img = NativeImage.read(in);
            }
        } catch (Throwable t) {
            return src;
        }
        // Mirror src column range into dst column range over the given v..v+h rows.
        // Guard against texture variants whose dims don't accommodate the op.
        if (op.srcU + op.w > img.getWidth() || op.dstU + op.w > img.getWidth()
                || op.v + op.h > img.getHeight()) {
            img.close();
            return src;
        }
        for (int dy = 0; dy < op.h; dy++) {
            for (int dx = 0; dx < op.w; dx++) {
                img.setPixelRGBA(op.dstU + dx, op.v + dy,
                        img.getPixelRGBA(op.srcU + dx, op.v + dy));
            }
        }
        String safe = src.getNamespace() + "_" + src.getPath().replace('/', '_').replace('.', '_');
        ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("customglint", "ek_outline_mask/" + safe);
        DynamicTexture dt = new DynamicTexture(img);
        mc.getTextureManager().register(loc, dt);
        return loc;
    }
}
