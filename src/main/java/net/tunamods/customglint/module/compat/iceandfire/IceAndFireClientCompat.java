package net.tunamods.customglint.module.compat.iceandfire;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.client.CustomGlintRenderer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
                "com.iafenvoy.iceandfire.item.tool.TideTridentItem",
                new float[] { 0.0f, 0.0f, 0.0f,   0.0f, 0.0f, 0.0f });

        // IaF tide trident BEWLR swaps to a flat tide_trident_inventory sprite in
        // GROUND/FIXED — match vanilla trident behavior so the outline uses the flat-sprite
        // pixel-translate path instead of the 3D AABB scale-dilation path.
        CustomGlintRenderer.FLAT_ON_GROUND_ITEMS.add("com.iafenvoy.iceandfire.item.tool.TideTridentItem");

        // IaF tide trident BEWLR outline: use the actual trident texture instead of white.png so
        // the outline shader alpha-discards transparent texels — otherwise model cubes whose UVs
        // sit over empty texture regions get filled as opaque colored squares.
        CustomGlintRenderer.BEWLR_OUTLINE_TEXTURES.put(
                "com.iafenvoy.iceandfire.item.tool.TideTridentItem",
                ResourceLocation.fromNamespaceAndPath("iceandfire", "textures/entity/misc/tide_trident.png"));

        // IaF troll weapons (axe, hammer, column, trunk, frost/forest variants) all render through
        // ONE TrollWeaponRenderer with ONE shared TrollWeaponModel — only the texture changes per
        // weapon (TrollWeaponItem.weapon.getTexture()). With white.png the BEWLR outline traces the
        // full shared geometry, so every weapon's glow looked identical. Resolve the per-weapon
        // texture so the outline alpha-discards to just that weapon's silhouette.
        CustomGlintRenderer.BEWLR_OUTLINE_TEXTURE_RESOLVERS.put(
                "com.iafenvoy.iceandfire.item.tool.TrollWeaponItem",
                IceAndFireClientCompat::trollWeaponTexture);
    }

    private static volatile Method TROLL_GET_TEXTURE;

    /** Reads {@code TrollWeaponItem.weapon.getTexture()} reflectively (IaF is not a compile dep). */
    private static ResourceLocation trollWeaponTexture(ItemStack stack) {
        try {
            Object item = stack.getItem();
            Field weaponField = item.getClass().getField("weapon");
            Object weapon = weaponField.get(item);
            if (weapon == null) return null;
            Method m = TROLL_GET_TEXTURE;
            if (m == null || !m.getDeclaringClass().isInstance(weapon)) {
                m = weapon.getClass().getMethod("getTexture");
                m.setAccessible(true);
                TROLL_GET_TEXTURE = m;
            }
            return (ResourceLocation) m.invoke(weapon);
        } catch (Throwable t) {
            return null;
        }
    }
}
