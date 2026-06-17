package net.tunamods.customglint.common.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side rendering settings for Custom Glints (personal, NOT synced — what glows is server
 * authoritative; this only controls how THIS client draws it). Registered as a {@code ModConfig.Type
 * .CLIENT} spec in {@link CustomGlintClientInit}.
 *
 * <p><b>Live, no restart:</b> every accessor reads the current spec value, and the render code calls
 * these accessors fresh each frame. NeoForge updates the values both when the in-game config screen
 * saves and when the TOML is edited on disk, so changes apply on the next rendered frame — the mask
 * targets resize and the gates flip without relaunching.
 */
public final class GlintClientConfig {
    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.IntValue OUTLINE_RENDER_SCALE;
    private static final ModConfigSpec.IntValue OUTLINE_MAX_DISTANCE;
    private static final ModConfigSpec.IntValue OUTLINE_MAX_ENTITIES;
    private static final ModConfigSpec.BooleanValue ENTITY_OUTLINES;
    private static final ModConfigSpec.BooleanValue ITEM_OUTLINES;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        OUTLINE_RENDER_SCALE = b
                .comment("Outline render scale. 1 = full resolution (sharpest).",
                        "2 or 3 = softer outline, much faster on weak GPUs. Applies instantly.")
                .translation("customglint.configuration.outlineRenderScale")
                .defineInRange("outlineRenderScale", 1, 1, 3);
        OUTLINE_MAX_DISTANCE = b
                .comment("Max distance (blocks) to draw glow outlines. 0 = no limit.",
                        "Lower values skip outlines on far entities/items: fewer shapes to draw, less GPU.")
                .translation("customglint.configuration.outlineMaxDistance")
                .defineInRange("outlineMaxDistance", 0, 0, 512);
        OUTLINE_MAX_ENTITIES = b
                .comment("Max number of entities outlined at once. 0 = no limit.",
                        "Caps the worst case when huge numbers of glowing mobs are crammed on screen.")
                .translation("customglint.configuration.outlineMaxEntities")
                .defineInRange("outlineMaxEntities", 0, 0, 1024);
        ENTITY_OUTLINES = b
                .comment("Show the glowing outline on mobs and players.")
                .translation("customglint.configuration.entityGlowOutlines")
                .define("entityGlowOutlines", true);
        ITEM_OUTLINES = b
                .comment("Show the glowing outline on dropped and held items.")
                .translation("customglint.configuration.itemGlowOutlines")
                .define("itemGlowOutlines", true);
        SPEC = b.build();
    }

    private GlintClientConfig() {}

    /** Outline mask/composite resolution divisor (1..3). Falls back to 1 before the config loads. */
    public static int outlineRenderScale() {
        return SPEC.isLoaded() ? OUTLINE_RENDER_SCALE.get() : 1;
    }

    /** Squared max outline distance (camera-relative), for cheap comparison. {@link Double#MAX_VALUE}
     *  when unlimited (config value 0) or before the config loads. */
    public static double outlineMaxDistanceSq() {
        int d = SPEC.isLoaded() ? OUTLINE_MAX_DISTANCE.get() : 0;
        return d <= 0 ? Double.MAX_VALUE : (double) d * d;
    }

    /** Cap on simultaneously-outlined entities (0 = unlimited / before load). */
    public static int outlineMaxEntities() {
        return SPEC.isLoaded() ? OUTLINE_MAX_ENTITIES.get() : 0;
    }

    public static boolean entityOutlines() {
        return !SPEC.isLoaded() || ENTITY_OUTLINES.get();
    }

    public static boolean itemOutlines() {
        return !SPEC.isLoaded() || ITEM_OUTLINES.get();
    }
}
