package net.tunamods.customglint.common.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side rendering settings for Custom Glints (personal, NOT synced, what glows is server
 * authoritative; this only controls how THIS client draws it). Registered as a {@code ModConfig.Type
 * .CLIENT} spec in {@link CustomGlintClientInit}.
 *
 * <p><b>Live, no restart:</b> every accessor reads the current spec value, and the render code calls
 * these accessors fresh each frame. NeoForge updates the values both when the in-game config screen
 * saves and when the TOML is edited on disk, so changes apply on the next rendered frame, the mask
 * targets resize and the gates flip without relaunching.
 */
public final class GlintClientConfig {
    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.IntValue OUTLINE_RENDER_SCALE;
    private static final ModConfigSpec.IntValue OUTLINE_MAX_DISTANCE;
    private static final ModConfigSpec.IntValue OUTLINE_MAX_ENTITIES;
    private static final ModConfigSpec.BooleanValue ENTITY_OUTLINES;
    private static final ModConfigSpec.BooleanValue ITEM_OUTLINES;
    private static final ModConfigSpec.IntValue GLINT_TABLE_SKIN;
    private static final ModConfigSpec.IntValue GLINT_WAND_SKIN;
    private static final ModConfigSpec.BooleanValue GLINT_TABLE_SOUND;

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
        GLINT_TABLE_SKIN = b
                .comment("Glint Table GUI skin index (0 = default). Cycled by the skin button in the table.")
                .translation("customglint.configuration.glintTableSkin")
                .defineInRange("glintTableSkin", 0, 0, 31);
        GLINT_WAND_SKIN = b
                .comment("Glint Wand editor GUI skin index (0 = default). Cycled by the skin button in the wand editor.")
                .translation("customglint.configuration.glintWandSkin")
                .defineInRange("glintWandSkin", 0, 0, 31);
        GLINT_TABLE_SOUND = b
                .comment("Play the click sound when pressing Glint Table / Glint Wand buttons. Toggled by the button in either menu.")
                .translation("customglint.configuration.glintTableSound")
                .define("glintTableSound", true);
        SPEC = b.build();
    }

    private GlintClientConfig() {}

    // Skin/sound toggles update the spec in memory (live, read fresh each frame) but defer the disk write.
    // ModConfigSpec.save() writes the TOML AND fires the config-reload event, too heavy to run on the render
    // thread on every button click (the toggle lag). set() alone is in-memory only; we flush once on close.
    private static boolean dirty = false;

    /** Persist any pending in-memory config changes to disk. Call when a Glint menu closes. No-op if clean. */
    public static void flush() {
        if (dirty && SPEC.isLoaded()) {
            SPEC.save();
            dirty = false;
        }
    }

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

    /** Selected Glint Table GUI skin index (0 = default / before load). */
    public static int glintTableSkin() {
        return SPEC.isLoaded() ? GLINT_TABLE_SKIN.get() : 0;
    }

    /** Set the chosen Glint Table skin index in memory (live immediately; persisted by {@link #flush()} on
     *  menu close). No-op before the config loads. */
    public static void setGlintTableSkin(int idx) {
        if (!SPEC.isLoaded()) return;
        GLINT_TABLE_SKIN.set(idx);
        dirty = true;
    }

    /** Selected Glint Wand editor GUI skin index (0 = default / before load). */
    public static int glintWandSkin() {
        return SPEC.isLoaded() ? GLINT_WAND_SKIN.get() : 0;
    }

    /** Set the chosen Glint Wand skin index in memory (live immediately; persisted by {@link #flush()} on
     *  menu close). No-op before the config loads. */
    public static void setGlintWandSkin(int idx) {
        if (!SPEC.isLoaded()) return;
        GLINT_WAND_SKIN.set(idx);
        dirty = true;
    }

    /** Whether Glint menu buttons play their click sound (default on / before load). */
    public static boolean glintTableSound() {
        return !SPEC.isLoaded() || GLINT_TABLE_SOUND.get();
    }

    /** Set the Glint Table button-sound toggle in memory (live immediately; persisted by {@link #flush()} on
     *  menu close). No-op before the config loads. */
    public static void setGlintTableSound(boolean on) {
        if (!SPEC.isLoaded()) return;
        GLINT_TABLE_SOUND.set(on);
        dirty = true;
    }
}
