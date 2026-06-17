package net.tunamods.customglint.common;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.tunamods.customglint.common.client.CustomGlintRenderer;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static net.tunamods.customglint.CustomGlintMod.MOD_ID;

/**
 * Server-safe data API for Custom Glints. NBT read/write, color and design constants, and the
 * auto-apply registries live here. The rendering pipeline lives in {@link CustomGlintRenderer},
 * which is client-only and references this class for {@link Layer}/{@link Data} types and NBT.
 *
 * Split was required because the previous unified class extended {@code RenderStateShard} (a
 * client-only base class) and imported {@code Minecraft}/{@code RenderType}/etc., so any
 * server-reachable reference triggered {@code ClassNotFoundException} on dedicated servers.
 */
public final class CustomGlint {

    private CustomGlint() {}

    // ── Layer ─────────────────────────────────────────────────────────────────

    public record Layer(Identifier design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {}

    // ── Data ─────────────────────────────────────────────────────────────────

    public record Data(Layer[] layers) {}

    // ── Colors ────────────────────────────────────────────────────────────────

    public static int color(String hex) {
        return Integer.parseUnsignedInt(hex.startsWith("#") ? hex.substring(1) : hex, 16) | 0xFF000000;
    }

    public static final int RED        = color("FF0000");
    public static final int ORANGE     = color("FF6600");
    public static final int YELLOW     = color("FFFF00");
    public static final int LIME       = color("00FF00");
    public static final int GREEN      = color("008000");
    public static final int CYAN       = color("00FFFF");
    public static final int LIGHT_BLUE = color("00BFFF");
    public static final int BLUE       = color("0000FF");
    public static final int PURPLE     = color("8800FF");
    public static final int MAGENTA    = color("FF00FF");
    public static final int PINK       = color("FF69B4");
    public static final int BROWN      = color("8B4513");
    public static final int WHITE      = color("FFFFFF");
    public static final int LIGHT_GRAY = color("C0C0C0");
    public static final int GRAY       = color("808080");
    public static final int BLACK      = color("000000");

    // ── Designs ───────────────────────────────────────────────────────────────

    public static final Identifier VANILLA    = Identifier.fromNamespaceAndPath("minecraft", "textures/misc/enchanted_glint_item.png");
    public static final Identifier ARCS      = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/arcs.png");
    public static final Identifier AURORA    = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/aurora.png");
    public static final Identifier BLOBS     = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/blobs.png");
    public static final Identifier CASCADE   = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/cascade.png");
    public static final Identifier CHECKER   = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/checker.png");
    public static final Identifier CHEVRON   = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/chevron.png");
    public static final Identifier CORAL     = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/coral.png");
    public static final Identifier CRACKS    = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/cracks.png");
    public static final Identifier CROSSHATCH = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/crosshatch.png");
    public static final Identifier CRYSTAL   = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/crystal.png");
    public static final Identifier DEBRIS    = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/debris.png");
    public static final Identifier DIAMONDS  = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/diamonds.png");
    public static final Identifier DUNES     = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/dunes.png");
    public static final Identifier EMBER     = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/ember.png");
    public static final Identifier FEATHER   = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/feather.png");
    public static final Identifier FIRE      = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/fire.png");
    public static final Identifier FROST     = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/frost.png");
    public static final Identifier GLITCH    = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/glitch.png");
    public static final Identifier GLOW      = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/glow.png");
    public static final Identifier GRID      = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/grid.png");
    public static final Identifier HALO      = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/halo.png");
    public static final Identifier HEXAGON   = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/hexagon.png");
    public static final Identifier LIGHTNING = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/lightning.png");
    public static final Identifier MARBLE    = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/marble.png");
    public static final Identifier MATRIX    = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/matrix.png");
    public static final Identifier MESH      = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/mesh.png");
    public static final Identifier MOSAIC    = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/mosaic.png");
    public static final Identifier NET       = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/net.png");
    public static final Identifier OIL       = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/oil.png");
    public static final Identifier PETAL     = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/petal.png");
    public static final Identifier PLASMA    = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/plasma.png");
    public static final Identifier PLATE     = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/plate.png");
    public static final Identifier PRISM     = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/prism.png");
    public static final Identifier PULSE     = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/pulse.png");
    public static final Identifier RIPPLE    = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/ripple.png");
    public static final Identifier SAND      = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/sand.png");
    public static final Identifier SCALES    = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/scales.png");
    public static final Identifier SHEEN     = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/sheen.png");
    public static final Identifier SHIMMER   = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/shimmer.png");
    public static final Identifier SILK      = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/silk.png");
    public static final Identifier SLASH     = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/slash.png");
    public static final Identifier SMOKE     = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/smoke.png");
    public static final Identifier SOLID     = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/solid.png");
    public static final Identifier SPARKLE   = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/sparkle.png");
    public static final Identifier STARS     = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/stars.png");
    public static final Identifier STATIC    = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/static.png");
    public static final Identifier STRIPES   = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/stripes.png");
    public static final Identifier SWIRL     = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/swirl.png");
    public static final Identifier TIDE      = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/tide.png");
    public static final Identifier TILE      = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/tile.png");
    public static final Identifier VEIN      = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/vein.png");
    public static final Identifier WAVE      = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/wave.png");
    public static final Identifier WEAVE     = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/weave.png");
    public static final Identifier ZIGZAG    = Identifier.fromNamespaceAndPath(MOD_ID,"textures/glint/zigzag.png");

    public static final Identifier[] PATTERNS = {
            VANILLA,
            ARCS, AURORA, BLOBS, CASCADE, CHECKER, CHEVRON, CORAL, CRACKS,
            CROSSHATCH, CRYSTAL, DEBRIS, DIAMONDS, DUNES, EMBER, FEATHER, FIRE,
            FROST, GLITCH, GLOW, GRID, HALO, HEXAGON, LIGHTNING, MARBLE,
            MATRIX, MESH, MOSAIC, NET, OIL, PETAL, PLASMA, PLATE,
            PRISM, PULSE, RIPPLE, SAND, SCALES, SHEEN, SHIMMER, SILK,
            SLASH, SMOKE, SOLID, SPARKLE, STARS, STATIC, STRIPES, SWIRL,
            TIDE, TILE, VEIN, WAVE, WEAVE, ZIGZAG
    };

    /** Saturated colors only — used by JEI plugin and trim creative tab for preset display. */
    public static final int[] VIBRANT_COLORS = {
            RED, ORANGE, YELLOW, LIME, GREEN, CYAN, LIGHT_BLUE, BLUE, PURPLE, MAGENTA, PINK
    };

    /** All 16 named colors including neutrals — full palette for downstream mod use. */
    public static final int[] ALL_COLORS = {
            RED, ORANGE, YELLOW, LIME, GREEN, CYAN, LIGHT_BLUE, BLUE, PURPLE, MAGENTA, PINK,
            BROWN, WHITE, LIGHT_GRAY, GRAY, BLACK
    };

    // ── NBT ──────────────────────────────────────────────────────────────────

    private static final String TAG              = MOD_ID;
    private static final String LAYERS_KEY       = "layers";
    private static final String GLOWING_KEY      = "glowing";
    private static final String GLOW_COLORS_KEY  = "glowColors";
    private static final String GLOW_SEE_THROUGH_KEY = "glowSeeThrough";
    private static final String DESIGN_KEY      = "design";
    private static final String COLORS_KEY      = "colors";
    private static final String SPEED_KEY       = "speed";
    private static final String INTERPOLATE_KEY = "interpolate";
    private static final String SCALE_KEY         = "scale";
    private static final String SIMULTANEOUS_KEY  = "simultaneous";

    // Item glint state lives in the CUSTOM_DATA component (the 1.20.5+ migration path for
    // arbitrary item NBT). We keep the legacy schema — a CompoundTag stored under TAG inside the
    // component's root — so the on-disk format and entity/item symmetry are unchanged.

    /** The inner glint tag stored under TAG in the item's CUSTOM_DATA, or null if absent. */
    @Nullable
    private static CompoundTag glintTagOrNull(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || !cd.contains(TAG)) return null;
        return cd.copyTag().getCompound(TAG).orElse(null);
    }

    /** Overwrites the inner glint tag wholesale (other CUSTOM_DATA keys are preserved). */
    private static void putGlintTag(ItemStack stack, CompoundTag glintTag) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> root.put(TAG, glintTag));
    }

    /** Mutates the inner glint tag in place, creating it if absent. */
    private static void mutateGlintTag(ItemStack stack, Consumer<CompoundTag> mutator) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> {
            CompoundTag glintTag = root.getCompoundOrEmpty(TAG);
            mutator.accept(glintTag);
            root.put(TAG, glintTag);
        });
    }

    @Nullable
    public static Data read(ItemStack stack) {
        CompoundTag tag = glintTagOrNull(stack);
        return tag == null ? null : decode(tag);
    }

    /** Decodes an inner glint CompoundTag (the value stored under TAG) into Data, or null if invalid. */
    @Nullable
    private static Data decode(CompoundTag tag) {
        float globalSpeed = tag.getFloatOr(SPEED_KEY, 1.0f);
        if (globalSpeed <= 0) globalSpeed = 1.0f;
        boolean globalInterpolate = tag.getBooleanOr(INTERPOLATE_KEY, true);
        float globalScale = tag.getFloatOr(SCALE_KEY, 1.0f);
        if (globalScale <= 0) globalScale = 1.0f;
        boolean globalSimultaneous = tag.getBooleanOr(SIMULTANEOUS_KEY, true);

        Layer[] layers;
        if (tag.contains(LAYERS_KEY)) {
            ListTag list = tag.getListOrEmpty(LAYERS_KEY);
            if (list.isEmpty()) return null;
            layers = new Layer[list.size()];
            for (int i = 0; i < list.size(); i++) {
                CompoundTag lt = list.getCompoundOrEmpty(i);
                String design = lt.getStringOr(DESIGN_KEY, "");
                if (design.isEmpty()) return null;
                if (!lt.contains(COLORS_KEY)) return null;
                int[] colors = lt.getIntArray(COLORS_KEY).orElse(new int[0]);
                if (colors.length == 0) return null;
                float speed = lt.getFloatOr(SPEED_KEY, globalSpeed);
                if (speed <= 0) speed = 1.0f;
                boolean interpolate = lt.getBooleanOr(INTERPOLATE_KEY, globalInterpolate);
                float patternScale = lt.getFloatOr(SCALE_KEY, globalScale);
                if (patternScale <= 0) patternScale = 1.0f;
                boolean simultaneous = lt.getBooleanOr(SIMULTANEOUS_KEY, globalSimultaneous);
                layers[i] = new Layer(Identifier.parse(design), colors, speed, interpolate, patternScale, simultaneous);
            }
        } else {
            // backward compat: old single-layer format
            String design = tag.getStringOr(DESIGN_KEY, "");
            if (design.isEmpty()) return null;
            if (!tag.contains(COLORS_KEY)) return null;
            int[] colors = tag.getIntArray(COLORS_KEY).orElse(new int[0]);
            if (colors.length == 0) return null;
            layers = new Layer[]{ new Layer(Identifier.parse(design), colors, globalSpeed, globalInterpolate, globalScale, globalSimultaneous) };
        }

        return new Data(layers);
    }

    /** Encodes a Layer[] into a fresh inner glint CompoundTag (the value placed under TAG). */
    private static CompoundTag encodeLayers(Layer[] layers) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Layer layer : layers) {
            CompoundTag lt = new CompoundTag();
            lt.putString(DESIGN_KEY, layer.design().toString());
            lt.putIntArray(COLORS_KEY, layer.colors());
            lt.putFloat(SPEED_KEY, layer.speed());
            lt.putBoolean(INTERPOLATE_KEY, layer.interpolate());
            lt.putFloat(SCALE_KEY, layer.patternScale());
            lt.putBoolean(SIMULTANEOUS_KEY, layer.simultaneous());
            list.add(lt);
        }
        tag.put(LAYERS_KEY, list);
        return tag;
    }

    public static boolean has(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        return cd != null && cd.contains(TAG);
    }

    public static void write(ItemStack stack, Layer[] layers) {
        CompoundTag existing = glintTagOrNull(stack);
        CompoundTag tag = encodeLayers(layers);
        if (existing != null && existing.contains(GLOWING_KEY))
            tag.putBoolean(GLOWING_KEY, existing.getBooleanOr(GLOWING_KEY, false));
        if (existing != null && existing.contains(GLOW_COLORS_KEY))
            tag.putIntArray(GLOW_COLORS_KEY, existing.getIntArray(GLOW_COLORS_KEY).orElse(new int[0]));
        putGlintTag(stack, tag);
    }

    public static void write(ItemStack stack, Identifier design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        write(stack, new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous) });
    }

    public static void remove(ItemStack stack) {
        if (!has(stack)) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> root.remove(TAG));
    }

    public static boolean isGlowing(ItemStack stack) {
        if (stack.isEmpty()) return false;
        CompoundTag tag = glintTagOrNull(stack);
        return tag != null && tag.getBooleanOr(GLOWING_KEY, false);
    }

    public static void setGlowing(ItemStack stack, boolean glowing) {
        mutateGlintTag(stack, t -> t.putBoolean(GLOWING_KEY, glowing));
    }

    /** Glow Trim colors — drive the outline color animation independently of any glint Data. */
    public static int[] getGlowColors(ItemStack stack) {
        if (stack.isEmpty()) return new int[0];
        CompoundTag tag = glintTagOrNull(stack);
        if (tag == null || !tag.contains(GLOW_COLORS_KEY)) return new int[0];
        return tag.getIntArray(GLOW_COLORS_KEY).orElse(new int[0]);
    }

    public static boolean hasGlowColors(ItemStack stack) {
        return getGlowColors(stack).length > 0;
    }

    /** Sets glowColors AND glowing=true. Independent of any glint Data on the stack. */
    public static void setGlowColors(ItemStack stack, int[] colors) {
        mutateGlintTag(stack, t -> {
            t.putIntArray(GLOW_COLORS_KEY, colors);
            t.putBoolean(GLOWING_KEY, true);
        });
    }

    public static void clearGlowColors(ItemStack stack) {
        CompoundTag tag = glintTagOrNull(stack);
        if (tag == null || !tag.contains(GLOW_COLORS_KEY)) return;
        mutateGlintTag(stack, t -> t.remove(GLOW_COLORS_KEY));
    }

    public static ItemStack glinted(Item item, Identifier design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        ItemStack stack = new ItemStack(item);
        write(stack, design, colors, speed, interpolate, patternScale, simultaneous);
        return stack;
    }

    public static void write(ItemStack stack, Identifier design, int[] colors) {
        write(stack, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void write(ItemStack stack, Identifier design, int color) {
        write(stack, design, new int[]{color}, 1.0f, true, 1.0f, true);
    }

    public static ItemStack glinted(Item item, Identifier design, int[] colors) {
        return glinted(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static ItemStack glinted(Item item, Identifier design, int color) {
        return glinted(item, design, new int[]{color}, 1.0f, true, 1.0f, true);
    }

    // ── Auto-apply registries ─────────────────────────────────────────────────

    public static final Map<Item, Data> CRAFT_GLINTS = new HashMap<>();

    public static void registerCraftGlint(Item item, Identifier design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        CRAFT_GLINTS.put(item, new Data(new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous) }));
    }

    public static void registerCraftGlint(Item item, Identifier design, int[] colors) {
        registerCraftGlint(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void applyCraftGlint(ItemStack stack) {
        Data data = CRAFT_GLINTS.get(stack.getItem());
        if (data == null) return;
        write(stack, data.layers());
    }

    public static final Map<Item, Data> FISHING_GLINTS = new HashMap<>();

    public static void registerFishingGlint(Item item, Identifier design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        FISHING_GLINTS.put(item, new Data(new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous) }));
    }

    public static void registerFishingGlint(Item item, Identifier design, int[] colors) {
        registerFishingGlint(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void applyFishingGlint(ItemStack stack) {
        Data data = FISHING_GLINTS.get(stack.getItem());
        if (data == null) return;
        write(stack, data.layers());
    }

    public static final Map<Item, Data> MOB_DROP_GLINTS = new HashMap<>();

    public static void registerMobDropGlint(Item item, Identifier design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        MOB_DROP_GLINTS.put(item, new Data(new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous) }));
    }

    public static void registerMobDropGlint(Item item, Identifier design, int[] colors) {
        registerMobDropGlint(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void applyMobDropGlint(ItemStack stack) {
        Data data = MOB_DROP_GLINTS.get(stack.getItem());
        if (data == null) return;
        write(stack, data.layers());
    }

    // ── Entity glint API ──────────────────────────────────────────────────────
    //
    // Per-instance: NBT lives in the LivingEntity's persistent data under TAG (same schema as
    // items). Server writes; a client-side sync packet (GlintEntitySyncPacket) pushes the tag to
    // tracking players and the client renderer (EntityGlintRender) reads from the cache.
    //
    // Type-wide: ENTITY_GLINTS is a server-safe registry; the client renderer falls back to it
    // when no per-instance NBT exists, so all entities of the type render with the same glint
    // with no per-entity storage or sync.

    public static final Map<EntityType<?>, Data> ENTITY_GLINTS = new HashMap<>();

    public static void registerEntityGlint(EntityType<?> type, Data data) {
        ENTITY_GLINTS.put(type, data);
    }

    public static void registerEntityGlint(EntityType<?> type, Identifier design, int[] colors) {
        registerEntityGlint(type, new Data(new Layer[]{ new Layer(design, colors, 1.0f, true, 1.0f, true) }));
    }

    @Nullable
    public static Data getEntityGlint(EntityType<?> type) {
        return ENTITY_GLINTS.get(type);
    }

    /** Reads the per-instance entity glint tag (server: from persistentData; client: caller passes the synced tag). */
    @Nullable
    public static Data readEntity(LivingEntity entity) {
        CompoundTag pd = entity.getPersistentData();
        if (!pd.contains(TAG)) return null;
        return fromTag(pd.getCompound(TAG).orElse(null));
    }

    public static boolean hasEntity(LivingEntity entity) {
        return entity.getPersistentData().contains(TAG);
    }

    public static void writeEntity(LivingEntity entity, Layer[] layers) {
        CompoundTag pd = entity.getPersistentData();
        CompoundTag existing = pd.getCompound(TAG).orElse(null);
        CompoundTag glintTag = toTag(layers);
        if (existing != null && existing.contains(GLOWING_KEY))
            glintTag.putBoolean(GLOWING_KEY, existing.getBooleanOr(GLOWING_KEY, false));
        if (existing != null && existing.contains(GLOW_COLORS_KEY))
            glintTag.putIntArray(GLOW_COLORS_KEY, existing.getIntArray(GLOW_COLORS_KEY).orElse(new int[0]));
        if (existing != null && existing.contains(GLOW_SEE_THROUGH_KEY))
            glintTag.putBoolean(GLOW_SEE_THROUGH_KEY, existing.getBooleanOr(GLOW_SEE_THROUGH_KEY, false));
        pd.put(TAG, glintTag);
    }

    public static void removeEntity(LivingEntity entity) {
        entity.getPersistentData().remove(TAG);
    }

    public static boolean isEntityGlowing(LivingEntity entity) {
        CompoundTag pd = entity.getPersistentData();
        if (!pd.contains(TAG)) return false;
        return pd.getCompoundOrEmpty(TAG).getBooleanOr(GLOWING_KEY, false);
    }

    public static void setEntityGlowing(LivingEntity entity, boolean glowing) {
        CompoundTag pd = entity.getPersistentData();
        CompoundTag glintTag = pd.getCompoundOrEmpty(TAG);
        glintTag.putBoolean(GLOWING_KEY, glowing);
        pd.put(TAG, glintTag);
    }

    /**
     * Per-entity glow-outline see-through-walls flag. When true, this entity's glow outline draws ON TOP
     * of world geometry (visible through walls) instead of being occluded — useful for tracker/marker
     * style highlights. Default false (occluded, like a normal outline). Developer-facing: set it
     * server-side then call {@code EntityGlintEvents.broadcast(entity)} to sync, exactly like the glow
     * colors API.
     */
    public static boolean isEntityGlowSeeThrough(LivingEntity entity) {
        CompoundTag pd = entity.getPersistentData();
        if (!pd.contains(TAG)) return false;
        return pd.getCompoundOrEmpty(TAG).getBooleanOr(GLOW_SEE_THROUGH_KEY, false);
    }

    public static void setEntityGlowSeeThrough(LivingEntity entity, boolean seeThrough) {
        CompoundTag pd = entity.getPersistentData();
        CompoundTag glintTag = pd.getCompoundOrEmpty(TAG);
        glintTag.putBoolean(GLOW_SEE_THROUGH_KEY, seeThrough);
        pd.put(TAG, glintTag);
    }

    /** Per-entity Glow Trim colors — drive the outline color animation independently of any
     *  glint Data, identical semantics to {@link #getGlowColors(ItemStack)} but on a mob. */
    public static int[] getEntityGlowColors(LivingEntity entity) {
        CompoundTag pd = entity.getPersistentData();
        if (!pd.contains(TAG)) return new int[0];
        CompoundTag tag = pd.getCompoundOrEmpty(TAG);
        if (!tag.contains(GLOW_COLORS_KEY)) return new int[0];
        return tag.getIntArray(GLOW_COLORS_KEY).orElse(new int[0]);
    }

    public static boolean hasEntityGlowColors(LivingEntity entity) {
        return getEntityGlowColors(entity).length > 0;
    }

    /** Sets glowColors AND glowing=true on the entity. Call
     *  {@code EntityGlintEvents.broadcast(entity)} afterwards to push the change to tracking
     *  clients (the api jar registers the sync channel — no extra wiring needed). */
    public static void setEntityGlowColors(LivingEntity entity, int[] colors) {
        CompoundTag pd = entity.getPersistentData();
        CompoundTag glintTag = pd.getCompoundOrEmpty(TAG);
        glintTag.putIntArray(GLOW_COLORS_KEY, colors);
        glintTag.putBoolean(GLOWING_KEY, true);
        pd.put(TAG, glintTag);
    }

    public static void clearEntityGlowColors(LivingEntity entity) {
        CompoundTag pd = entity.getPersistentData();
        if (!pd.contains(TAG)) return;
        CompoundTag tag = pd.getCompoundOrEmpty(TAG);
        tag.remove(GLOW_COLORS_KEY);
    }

    /** Returns the raw inner glint tag stored on an ItemStack (or empty CompoundTag if none). */
    public static CompoundTag itemGlintTag(ItemStack stack) {
        CompoundTag tag = glintTagOrNull(stack);
        return tag == null ? new CompoundTag() : tag.copy();
    }

    /** Returns the raw inner glint tag for sync packets (or empty CompoundTag if none). */
    public static CompoundTag entityGlintTag(LivingEntity entity) {
        CompoundTag pd = entity.getPersistentData();
        return pd.getCompoundOrEmpty(TAG).copy();
    }

    /** Replaces the per-instance entity glint tag in one shot (used by the sync packet handler on the server side and by full overwrites). */
    public static void writeEntityTag(LivingEntity entity, CompoundTag glintTag) {
        if (glintTag == null || glintTag.isEmpty()) entity.getPersistentData().remove(TAG);
        else entity.getPersistentData().put(TAG, glintTag.copy());
    }

    /** Replaces the per-item glint tag in one shot. Symmetric with {@link #writeEntityTag} —
     *  useful for transferring glint state between item and entity (e.g. capturing a mob's
     *  glint onto an item via {@code writeItemTag(stack, entityGlintTag(entity))}) or
     *  restoring from a stored tag in bulk. Empty/null tag clears the glint. */
    public static void writeItemTag(ItemStack stack, CompoundTag glintTag) {
        if (glintTag == null || glintTag.isEmpty()) {
            remove(stack);
            return;
        }
        putGlintTag(stack, glintTag.copy());
    }

    // ── NBT serialization helpers (decoupled from ItemStack) ──────────────────

    /** Decodes the inner glint CompoundTag (the value stored under TAG) into a Data record, or null if invalid/missing. */
    @Nullable
    public static Data fromTag(@Nullable CompoundTag glintTag) {
        if (glintTag == null || glintTag.isEmpty()) return null;
        return decode(glintTag);
    }

    /** Returns true if the inner glint tag has glowing=true. */
    public static boolean tagGlowing(@Nullable CompoundTag glintTag) {
        return glintTag != null && glintTag.getBooleanOr(GLOWING_KEY, false);
    }

    /** Returns true if the inner glint tag marks the glow outline see-through (default false). */
    public static boolean tagGlowSeeThrough(@Nullable CompoundTag glintTag) {
        return glintTag != null && glintTag.getBooleanOr(GLOW_SEE_THROUGH_KEY, false);
    }

    /** Returns the glowColors int[] from the inner glint tag (empty if absent). */
    public static int[] tagGlowColors(@Nullable CompoundTag glintTag) {
        if (glintTag == null || !glintTag.contains(GLOW_COLORS_KEY)) return new int[0];
        return glintTag.getIntArray(GLOW_COLORS_KEY).orElse(new int[0]);
    }

    /** Encodes a Layer[] into a fresh inner glint CompoundTag (the value placed under TAG). */
    public static CompoundTag toTag(Layer[] layers) {
        return encodeLayers(layers);
    }

    public static final Map<Identifier, Map<Item, Data>> LOOT_GLINTS = new HashMap<>();

    public static void registerLootGlint(Identifier lootTable, Item item, Identifier design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        LOOT_GLINTS.computeIfAbsent(lootTable, k -> new HashMap<>()).put(item, new Data(new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous) }));
    }

    public static void registerLootGlint(Identifier lootTable, Item item, Identifier design, int[] colors) {
        registerLootGlint(lootTable, item, design, colors, 1.0f, true, 1.0f, true);
    }
}
