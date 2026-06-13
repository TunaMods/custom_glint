package net.tunamods.customglint.common;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.tunamods.customglint.common.client.CustomGlintRenderer;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

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

    public record Layer(ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {}

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

    public static final ResourceLocation VANILLA    = new ResourceLocation("minecraft", "textures/misc/enchanted_glint_item.png");
    public static final ResourceLocation ARCS      = new ResourceLocation(MOD_ID, "textures/glint/arcs.png");
    public static final ResourceLocation AURORA    = new ResourceLocation(MOD_ID, "textures/glint/aurora.png");
    public static final ResourceLocation BLOBS     = new ResourceLocation(MOD_ID, "textures/glint/blobs.png");
    public static final ResourceLocation CASCADE   = new ResourceLocation(MOD_ID, "textures/glint/cascade.png");
    public static final ResourceLocation CHECKER   = new ResourceLocation(MOD_ID, "textures/glint/checker.png");
    public static final ResourceLocation CHEVRON   = new ResourceLocation(MOD_ID, "textures/glint/chevron.png");
    public static final ResourceLocation CORAL     = new ResourceLocation(MOD_ID, "textures/glint/coral.png");
    public static final ResourceLocation CRACKS    = new ResourceLocation(MOD_ID, "textures/glint/cracks.png");
    public static final ResourceLocation CROSSHATCH = new ResourceLocation(MOD_ID, "textures/glint/crosshatch.png");
    public static final ResourceLocation CRYSTAL   = new ResourceLocation(MOD_ID, "textures/glint/crystal.png");
    public static final ResourceLocation DEBRIS    = new ResourceLocation(MOD_ID, "textures/glint/debris.png");
    public static final ResourceLocation DIAMONDS  = new ResourceLocation(MOD_ID, "textures/glint/diamonds.png");
    public static final ResourceLocation DUNES     = new ResourceLocation(MOD_ID, "textures/glint/dunes.png");
    public static final ResourceLocation EMBER     = new ResourceLocation(MOD_ID, "textures/glint/ember.png");
    public static final ResourceLocation FEATHER   = new ResourceLocation(MOD_ID, "textures/glint/feather.png");
    public static final ResourceLocation FIRE      = new ResourceLocation(MOD_ID, "textures/glint/fire.png");
    public static final ResourceLocation FROST     = new ResourceLocation(MOD_ID, "textures/glint/frost.png");
    public static final ResourceLocation GLITCH    = new ResourceLocation(MOD_ID, "textures/glint/glitch.png");
    public static final ResourceLocation GLOW      = new ResourceLocation(MOD_ID, "textures/glint/glow.png");
    public static final ResourceLocation GRID      = new ResourceLocation(MOD_ID, "textures/glint/grid.png");
    public static final ResourceLocation HALO      = new ResourceLocation(MOD_ID, "textures/glint/halo.png");
    public static final ResourceLocation HEXAGON   = new ResourceLocation(MOD_ID, "textures/glint/hexagon.png");
    public static final ResourceLocation LIGHTNING = new ResourceLocation(MOD_ID, "textures/glint/lightning.png");
    public static final ResourceLocation MARBLE    = new ResourceLocation(MOD_ID, "textures/glint/marble.png");
    public static final ResourceLocation MATRIX    = new ResourceLocation(MOD_ID, "textures/glint/matrix.png");
    public static final ResourceLocation MESH      = new ResourceLocation(MOD_ID, "textures/glint/mesh.png");
    public static final ResourceLocation MOSAIC    = new ResourceLocation(MOD_ID, "textures/glint/mosaic.png");
    public static final ResourceLocation NET       = new ResourceLocation(MOD_ID, "textures/glint/net.png");
    public static final ResourceLocation OIL       = new ResourceLocation(MOD_ID, "textures/glint/oil.png");
    public static final ResourceLocation PETAL     = new ResourceLocation(MOD_ID, "textures/glint/petal.png");
    public static final ResourceLocation PLASMA    = new ResourceLocation(MOD_ID, "textures/glint/plasma.png");
    public static final ResourceLocation PLATE     = new ResourceLocation(MOD_ID, "textures/glint/plate.png");
    public static final ResourceLocation PRISM     = new ResourceLocation(MOD_ID, "textures/glint/prism.png");
    public static final ResourceLocation PULSE     = new ResourceLocation(MOD_ID, "textures/glint/pulse.png");
    public static final ResourceLocation RIPPLE    = new ResourceLocation(MOD_ID, "textures/glint/ripple.png");
    public static final ResourceLocation SAND      = new ResourceLocation(MOD_ID, "textures/glint/sand.png");
    public static final ResourceLocation SCALES    = new ResourceLocation(MOD_ID, "textures/glint/scales.png");
    public static final ResourceLocation SHEEN     = new ResourceLocation(MOD_ID, "textures/glint/sheen.png");
    public static final ResourceLocation SHIMMER   = new ResourceLocation(MOD_ID, "textures/glint/shimmer.png");
    public static final ResourceLocation SILK      = new ResourceLocation(MOD_ID, "textures/glint/silk.png");
    public static final ResourceLocation SLASH     = new ResourceLocation(MOD_ID, "textures/glint/slash.png");
    public static final ResourceLocation SMOKE     = new ResourceLocation(MOD_ID, "textures/glint/smoke.png");
    public static final ResourceLocation SOLID     = new ResourceLocation(MOD_ID, "textures/glint/solid.png");
    public static final ResourceLocation SPARKLE   = new ResourceLocation(MOD_ID, "textures/glint/sparkle.png");
    public static final ResourceLocation STARS     = new ResourceLocation(MOD_ID, "textures/glint/stars.png");
    public static final ResourceLocation STATIC    = new ResourceLocation(MOD_ID, "textures/glint/static.png");
    public static final ResourceLocation STRIPES   = new ResourceLocation(MOD_ID, "textures/glint/stripes.png");
    public static final ResourceLocation SWIRL     = new ResourceLocation(MOD_ID, "textures/glint/swirl.png");
    public static final ResourceLocation TIDE      = new ResourceLocation(MOD_ID, "textures/glint/tide.png");
    public static final ResourceLocation TILE      = new ResourceLocation(MOD_ID, "textures/glint/tile.png");
    public static final ResourceLocation VEIN      = new ResourceLocation(MOD_ID, "textures/glint/vein.png");
    public static final ResourceLocation WAVE      = new ResourceLocation(MOD_ID, "textures/glint/wave.png");
    public static final ResourceLocation WEAVE     = new ResourceLocation(MOD_ID, "textures/glint/weave.png");
    public static final ResourceLocation ZIGZAG    = new ResourceLocation(MOD_ID, "textures/glint/zigzag.png");

    public static final ResourceLocation[] PATTERNS = {
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
    private static final String DESIGN_KEY      = "design";
    private static final String COLORS_KEY      = "colors";
    private static final String SPEED_KEY       = "speed";
    private static final String INTERPOLATE_KEY = "interpolate";
    private static final String SCALE_KEY         = "scale";
    private static final String SIMULTANEOUS_KEY  = "simultaneous";

    @Nullable
    public static Data read(ItemStack stack) {
        if (!stack.hasTag()) return null;
        CompoundTag root = stack.getTag();
        if (!root.contains(TAG)) return null;
        CompoundTag tag = root.getCompound(TAG);

        float globalSpeed = tag.contains(SPEED_KEY) ? tag.getFloat(SPEED_KEY) : 1.0f;
        if (globalSpeed <= 0) globalSpeed = 1.0f;
        boolean globalInterpolate = !tag.contains(INTERPOLATE_KEY) || tag.getBoolean(INTERPOLATE_KEY);
        float globalScale = tag.contains(SCALE_KEY) ? tag.getFloat(SCALE_KEY) : 1.0f;
        if (globalScale <= 0) globalScale = 1.0f;
        boolean globalSimultaneous = !tag.contains(SIMULTANEOUS_KEY) || tag.getBoolean(SIMULTANEOUS_KEY);

        Layer[] layers;
        if (tag.contains(LAYERS_KEY)) {
            ListTag list = tag.getList(LAYERS_KEY, Tag.TAG_COMPOUND);
            if (list.isEmpty()) return null;
            layers = new Layer[list.size()];
            for (int i = 0; i < list.size(); i++) {
                CompoundTag lt = list.getCompound(i);
                String design = lt.getString(DESIGN_KEY);
                if (design.isEmpty()) return null;
                if (!lt.contains(COLORS_KEY)) return null;
                int[] colors = lt.getIntArray(COLORS_KEY);
                if (colors.length == 0) return null;
                float speed = lt.contains(SPEED_KEY) ? lt.getFloat(SPEED_KEY) : globalSpeed;
                if (speed <= 0) speed = 1.0f;
                boolean interpolate = lt.contains(INTERPOLATE_KEY) ? lt.getBoolean(INTERPOLATE_KEY) : globalInterpolate;
                float patternScale = lt.contains(SCALE_KEY) ? lt.getFloat(SCALE_KEY) : globalScale;
                if (patternScale <= 0) patternScale = 1.0f;
                boolean simultaneous = lt.contains(SIMULTANEOUS_KEY) ? lt.getBoolean(SIMULTANEOUS_KEY) : globalSimultaneous;
                layers[i] = new Layer(new ResourceLocation(design), colors, speed, interpolate, patternScale, simultaneous);
            }
        } else {
            // backward compat: old single-layer format
            String design = tag.getString(DESIGN_KEY);
            if (design.isEmpty()) return null;
            if (!tag.contains(COLORS_KEY)) return null;
            int[] colors = tag.getIntArray(COLORS_KEY);
            if (colors.length == 0) return null;
            layers = new Layer[]{ new Layer(new ResourceLocation(design), colors, globalSpeed, globalInterpolate, globalScale, globalSimultaneous) };
        }

        return new Data(layers);
    }

    public static boolean has(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains(TAG);
    }

    public static void write(ItemStack stack, Layer[] layers) {
        CompoundTag tag = new CompoundTag();
        CompoundTag existing = stack.hasTag() ? stack.getTag().getCompound(TAG) : null;
        if (existing != null && existing.contains(GLOWING_KEY))
            tag.putBoolean(GLOWING_KEY, existing.getBoolean(GLOWING_KEY));
        if (existing != null && existing.contains(GLOW_COLORS_KEY))
            tag.putIntArray(GLOW_COLORS_KEY, existing.getIntArray(GLOW_COLORS_KEY));
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
        stack.getOrCreateTag().put(TAG, tag);
    }

    public static void write(ItemStack stack, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        write(stack, new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous) });
    }

    public static void remove(ItemStack stack) {
        if (stack.hasTag()) stack.getTag().remove(TAG);
    }

    public static boolean isGlowing(ItemStack stack) {
        if (stack.isEmpty() || !stack.hasTag()) return false;
        CompoundTag root = stack.getTag();
        if (!root.contains(TAG)) return false;
        return root.getCompound(TAG).getBoolean(GLOWING_KEY);
    }

    public static void setGlowing(ItemStack stack, boolean glowing) {
        CompoundTag root = stack.getOrCreateTag();
        CompoundTag glintTag = root.contains(TAG) ? root.getCompound(TAG) : new CompoundTag();
        glintTag.putBoolean(GLOWING_KEY, glowing);
        root.put(TAG, glintTag);
    }

    /** Glow Trim colors — drive the outline color animation independently of any glint Data. */
    public static int[] getGlowColors(ItemStack stack) {
        if (stack.isEmpty() || !stack.hasTag()) return new int[0];
        CompoundTag root = stack.getTag();
        if (!root.contains(TAG)) return new int[0];
        CompoundTag tag = root.getCompound(TAG);
        if (!tag.contains(GLOW_COLORS_KEY)) return new int[0];
        return tag.getIntArray(GLOW_COLORS_KEY);
    }

    public static boolean hasGlowColors(ItemStack stack) {
        return getGlowColors(stack).length > 0;
    }

    /** Sets glowColors AND glowing=true. Independent of any glint Data on the stack. */
    public static void setGlowColors(ItemStack stack, int[] colors) {
        CompoundTag root = stack.getOrCreateTag();
        CompoundTag glintTag = root.contains(TAG) ? root.getCompound(TAG) : new CompoundTag();
        glintTag.putIntArray(GLOW_COLORS_KEY, colors);
        glintTag.putBoolean(GLOWING_KEY, true);
        root.put(TAG, glintTag);
    }

    public static void clearGlowColors(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains(TAG)) return;
        CompoundTag tag = stack.getTag().getCompound(TAG);
        tag.remove(GLOW_COLORS_KEY);
    }

    public static ItemStack glinted(Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        ItemStack stack = new ItemStack(item);
        write(stack, design, colors, speed, interpolate, patternScale, simultaneous);
        return stack;
    }

    public static void write(ItemStack stack, ResourceLocation design, int[] colors) {
        write(stack, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void write(ItemStack stack, ResourceLocation design, int color) {
        write(stack, design, new int[]{color}, 1.0f, true, 1.0f, true);
    }

    public static ItemStack glinted(Item item, ResourceLocation design, int[] colors) {
        return glinted(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static ItemStack glinted(Item item, ResourceLocation design, int color) {
        return glinted(item, design, new int[]{color}, 1.0f, true, 1.0f, true);
    }

    // ── Auto-apply registries ─────────────────────────────────────────────────

    public static final Map<Item, Data> CRAFT_GLINTS = new HashMap<>();

    public static void registerCraftGlint(Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        CRAFT_GLINTS.put(item, new Data(new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous) }));
    }

    public static void registerCraftGlint(Item item, ResourceLocation design, int[] colors) {
        registerCraftGlint(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void applyCraftGlint(ItemStack stack) {
        Data data = CRAFT_GLINTS.get(stack.getItem());
        if (data == null) return;
        write(stack, data.layers());
    }

    public static final Map<Item, Data> FISHING_GLINTS = new HashMap<>();

    public static void registerFishingGlint(Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        FISHING_GLINTS.put(item, new Data(new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous) }));
    }

    public static void registerFishingGlint(Item item, ResourceLocation design, int[] colors) {
        registerFishingGlint(item, design, colors, 1.0f, true, 1.0f, true);
    }

    public static void applyFishingGlint(ItemStack stack) {
        Data data = FISHING_GLINTS.get(stack.getItem());
        if (data == null) return;
        write(stack, data.layers());
    }

    public static final Map<Item, Data> MOB_DROP_GLINTS = new HashMap<>();

    public static void registerMobDropGlint(Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        MOB_DROP_GLINTS.put(item, new Data(new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous) }));
    }

    public static void registerMobDropGlint(Item item, ResourceLocation design, int[] colors) {
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

    public static void registerEntityGlint(EntityType<?> type, ResourceLocation design, int[] colors) {
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
        return fromTag(pd.getCompound(TAG));
    }

    public static boolean hasEntity(LivingEntity entity) {
        return entity.getPersistentData().contains(TAG);
    }

    public static void writeEntity(LivingEntity entity, Layer[] layers) {
        CompoundTag pd = entity.getPersistentData();
        CompoundTag existing = pd.contains(TAG) ? pd.getCompound(TAG) : null;
        CompoundTag glintTag = toTag(layers);
        if (existing != null && existing.contains(GLOWING_KEY))
            glintTag.putBoolean(GLOWING_KEY, existing.getBoolean(GLOWING_KEY));
        if (existing != null && existing.contains(GLOW_COLORS_KEY))
            glintTag.putIntArray(GLOW_COLORS_KEY, existing.getIntArray(GLOW_COLORS_KEY));
        pd.put(TAG, glintTag);
    }

    public static void removeEntity(LivingEntity entity) {
        entity.getPersistentData().remove(TAG);
    }

    public static boolean isEntityGlowing(LivingEntity entity) {
        CompoundTag pd = entity.getPersistentData();
        if (!pd.contains(TAG)) return false;
        return pd.getCompound(TAG).getBoolean(GLOWING_KEY);
    }

    public static void setEntityGlowing(LivingEntity entity, boolean glowing) {
        CompoundTag pd = entity.getPersistentData();
        CompoundTag glintTag = pd.contains(TAG) ? pd.getCompound(TAG) : new CompoundTag();
        glintTag.putBoolean(GLOWING_KEY, glowing);
        pd.put(TAG, glintTag);
    }

    /** Per-entity Glow Trim colors — drive the outline color animation independently of any
     *  glint Data, identical semantics to {@link #getGlowColors(ItemStack)} but on a mob. */
    public static int[] getEntityGlowColors(LivingEntity entity) {
        CompoundTag pd = entity.getPersistentData();
        if (!pd.contains(TAG)) return new int[0];
        CompoundTag tag = pd.getCompound(TAG);
        if (!tag.contains(GLOW_COLORS_KEY)) return new int[0];
        return tag.getIntArray(GLOW_COLORS_KEY);
    }

    public static boolean hasEntityGlowColors(LivingEntity entity) {
        return getEntityGlowColors(entity).length > 0;
    }

    /** Sets glowColors AND glowing=true on the entity. Call
     *  {@code EntityGlintEvents.broadcast(entity)} afterwards to push the change to tracking
     *  clients (the api jar registers the sync channel — no extra wiring needed). */
    public static void setEntityGlowColors(LivingEntity entity, int[] colors) {
        CompoundTag pd = entity.getPersistentData();
        CompoundTag glintTag = pd.contains(TAG) ? pd.getCompound(TAG) : new CompoundTag();
        glintTag.putIntArray(GLOW_COLORS_KEY, colors);
        glintTag.putBoolean(GLOWING_KEY, true);
        pd.put(TAG, glintTag);
    }

    public static void clearEntityGlowColors(LivingEntity entity) {
        CompoundTag pd = entity.getPersistentData();
        if (!pd.contains(TAG)) return;
        CompoundTag tag = pd.getCompound(TAG);
        tag.remove(GLOW_COLORS_KEY);
    }

    /** Returns the raw inner glint tag stored on an ItemStack (or empty CompoundTag if none). */
    public static CompoundTag itemGlintTag(ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().contains(TAG)) return new CompoundTag();
        return stack.getTag().getCompound(TAG).copy();
    }

    /** Returns the raw inner glint tag for sync packets (or empty CompoundTag if none). */
    public static CompoundTag entityGlintTag(LivingEntity entity) {
        CompoundTag pd = entity.getPersistentData();
        return pd.contains(TAG) ? pd.getCompound(TAG).copy() : new CompoundTag();
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
            if (stack.hasTag()) stack.getTag().remove(TAG);
            return;
        }
        stack.getOrCreateTag().put(TAG, glintTag.copy());
    }

    // ── NBT serialization helpers (decoupled from ItemStack) ──────────────────

    /** Decodes the inner glint CompoundTag (the value stored under TAG) into a Data record, or null if invalid/missing. */
    @Nullable
    public static Data fromTag(@Nullable CompoundTag glintTag) {
        if (glintTag == null || glintTag.isEmpty()) return null;
        ItemStack vehicle = new ItemStack(Items.STONE);
        CompoundTag root = new CompoundTag();
        root.put(TAG, glintTag.copy());
        vehicle.setTag(root);
        return read(vehicle);
    }

    /** Returns true if the inner glint tag has glowing=true. */
    public static boolean tagGlowing(@Nullable CompoundTag glintTag) {
        return glintTag != null && glintTag.getBoolean(GLOWING_KEY);
    }

    /** Returns the glowColors int[] from the inner glint tag (empty if absent). */
    public static int[] tagGlowColors(@Nullable CompoundTag glintTag) {
        if (glintTag == null || !glintTag.contains(GLOW_COLORS_KEY)) return new int[0];
        return glintTag.getIntArray(GLOW_COLORS_KEY);
    }

    /** Encodes a Layer[] into a fresh inner glint CompoundTag (the value placed under TAG). */
    public static CompoundTag toTag(Layer[] layers) {
        ItemStack vehicle = new ItemStack(Items.STONE);
        write(vehicle, layers);
        return vehicle.getTag().getCompound(TAG).copy();
    }

    public static final Map<ResourceLocation, Map<Item, Data>> LOOT_GLINTS = new HashMap<>();

    public static void registerLootGlint(ResourceLocation lootTable, Item item, ResourceLocation design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        LOOT_GLINTS.computeIfAbsent(lootTable, k -> new HashMap<>()).put(item, new Data(new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous) }));
    }

    public static void registerLootGlint(ResourceLocation lootTable, Item item, ResourceLocation design, int[] colors) {
        registerLootGlint(lootTable, item, design, colors, 1.0f, true, 1.0f, true);
    }
}
