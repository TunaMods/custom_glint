package net.tunamods.customglint.module.compat.iceandfire;

import net.minecraft.resources.ResourceLocation;
import net.tunamods.customglint.common.client.CustomGlintRenderer;

/**
 * Client-only half of the Ice & Fire compat. Reached from {@link IceAndFireCompat#register()}
 * via {@code DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> IceAndFireClientCompat::run)} so the
 * JVM never resolves {@link CustomGlintRenderer} on a dedicated server.
 */
public final class IceAndFireClientCompat {
    private IceAndFireClientCompat() {}

    public static void run() {
        // IaF tide trident — applies the IaF BEWLR's (0, 0.2, -0.15) + 1P/3P-conditional translate
        // and 160° Z-rotation, so the model sits offset from the BEWLR AABB centroid. Tune below.
        // Format: {cx1P, cy1P, cz1P, cx3P, cy3P, cz3P}
        CustomGlintRenderer.BEWLR_OUTLINE_OFFSETS.put(
                "com.github.alexthe666.iceandfire.item.ItemTideTrident",
                new float[] { 0.0f, 0.0f, 0.0f,   0.0f, 0.0f, 0.0f });

        // IaF tide trident BEWLR swaps to a flat tide_trident_inventory sprite in
        // GROUND/FIXED — match vanilla trident behavior so the outline uses the flat-sprite
        // pixel-translate path instead of the 3D AABB scale-dilation path.
        CustomGlintRenderer.FLAT_ON_GROUND_ITEMS.add("com.github.alexthe666.iceandfire.item.ItemTideTrident");

        // IaF tide trident BEWLR outline: use the actual trident texture instead of white.png so
        // the outline shader alpha-discards transparent texels — otherwise model cubes whose UVs
        // sit over empty texture regions get filled as opaque colored squares.
        CustomGlintRenderer.BEWLR_OUTLINE_TEXTURES.put(
                "com.github.alexthe666.iceandfire.item.ItemTideTrident",
                ResourceLocation.fromNamespaceAndPath("iceandfire", "textures/models/misc/tide_trident.png"));
    }
}
