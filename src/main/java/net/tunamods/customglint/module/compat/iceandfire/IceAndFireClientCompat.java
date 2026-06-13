package net.tunamods.customglint.module.compat.iceandfire;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.RegistryObject;
import net.tunamods.customglint.common.client.CustomGlintRenderer;

import java.lang.reflect.Field;

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
                new ResourceLocation("iceandfire", "textures/models/misc/tide_trident.png"));

        // IaF troll weapons (axe, hammer, column, trunk, ...) all render through ONE RenderTrollWeapon
        // with ONE shared ModelTrollWeapon — only the texture changes per weapon (EnumTroll.Weapon.TEXTURE).
        // white.png / untextured dilation would trace the full shared geometry and cover the whole
        // weapon in glow, so resolve the active weapon's texture for per-variant alpha-discard.
        CustomGlintRenderer.BEWLR_OUTLINE_TEXTURE_RESOLVERS.put(
                "com.github.alexthe666.iceandfire.item.ItemTrollWeapon",
                IceAndFireClientCompat::trollWeaponTexture);

        // IaF death worm gauntlet: one ItemDeathwormGauntlet class, three registry instances
        // (red/white/yellow) → RenderDeathWorm.TEXTURE_RED/WHITE/YELLOW. Same shared-model problem.
        CustomGlintRenderer.BEWLR_OUTLINE_TEXTURE_RESOLVERS.put(
                "com.github.alexthe666.iceandfire.item.ItemDeathwormGauntlet",
                IceAndFireClientCompat::deathWormGauntletTexture);
    }

    private static volatile Field TROLL_WEAPON_FIELD;
    private static volatile Field TROLL_TEXTURE_FIELD;

    /** Reads {@code ((ItemTrollWeapon) item).weapon.TEXTURE} reflectively (IaF is not a compile dep). */
    private static ResourceLocation trollWeaponTexture(ItemStack stack) {
        try {
            Field wf = TROLL_WEAPON_FIELD;
            if (wf == null) {
                wf = Class.forName("com.github.alexthe666.iceandfire.item.ItemTrollWeapon").getDeclaredField("weapon");
                wf.setAccessible(true);
                TROLL_WEAPON_FIELD = wf;
            }
            Object weapon = wf.get(stack.getItem());
            if (weapon == null) return null;
            Field tf = TROLL_TEXTURE_FIELD;
            if (tf == null) {
                tf = Class.forName("com.github.alexthe666.iceandfire.enums.EnumTroll$Weapon").getDeclaredField("TEXTURE");
                tf.setAccessible(true);
                TROLL_TEXTURE_FIELD = tf;
            }
            return (ResourceLocation) tf.get(weapon);
        } catch (Throwable t) {
            return null;
        }
    }

    private static volatile Object[] GAUNTLET_ITEMS;
    private static volatile ResourceLocation[] GAUNTLET_TEXTURES;

    /** Maps each death worm gauntlet item instance to its RenderDeathWorm texture (reflective). */
    private static ResourceLocation deathWormGauntletTexture(ItemStack stack) {
        try {
            if (GAUNTLET_ITEMS == null) {
                Class<?> reg = Class.forName("com.github.alexthe666.iceandfire.item.IafItemRegistry");
                Object red    = ((RegistryObject<?>) reg.getField("DEATHWORM_GAUNTLET_RED").get(null)).get();
                Object white  = ((RegistryObject<?>) reg.getField("DEATHWORM_GAUNTLET_WHITE").get(null)).get();
                Object yellow = ((RegistryObject<?>) reg.getField("DEATHWORM_GAUNTLET_YELLOW").get(null)).get();
                Class<?> rdw = Class.forName("com.github.alexthe666.iceandfire.client.render.entity.RenderDeathWorm");
                GAUNTLET_ITEMS = new Object[] { red, white, yellow };
                GAUNTLET_TEXTURES = new ResourceLocation[] {
                        staticField(rdw, "TEXTURE_RED"),
                        staticField(rdw, "TEXTURE_WHITE"),
                        staticField(rdw, "TEXTURE_YELLOW") };
            }
            Object item = stack.getItem();
            for (int i = 0; i < GAUNTLET_ITEMS.length; i++) {
                if (GAUNTLET_ITEMS[i] == item) return GAUNTLET_TEXTURES[i];
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static ResourceLocation staticField(Class<?> cls, String name) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return (ResourceLocation) f.get(null);
    }
}
