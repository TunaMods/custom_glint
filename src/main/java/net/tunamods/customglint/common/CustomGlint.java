package net.tunamods.customglint.common;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import static net.tunamods.customglint.CustomGlintMod.MOD_ID;

/**
 * Server-safe data API for Custom Glints. Item/entity glint state read/write (backed by a typed
 * {@code GlintState} data component + a synced attachment, not loose NBT), color and design constants,
 * and the auto-apply registries live here. The rendering pipeline lives in
 * {@code net.tunamods.customglint.common.client.CustomGlintRenderer} (referenced by name, not imported:
 * it is client-only, and a real symbol reference from this server-reachable class would
 * {@code ClassNotFoundException} on a dedicated server), which references this class for
 * {@link Layer}/{@link Data} types and state access.
 *
 * Split was required because the previous unified class extended {@code RenderStateShard} (a
 * client-only base class) and imported {@code Minecraft}/{@code RenderType}/etc., so any
 * server-reachable reference triggered {@code ClassNotFoundException} on dedicated servers.
 */
public final class CustomGlint {

    private CustomGlint() {}

    // ── Layer ─────────────────────────────────────────────────────────────────

    /** {@link Layer#scrollDir} values: the direction the animated glint drifts. {@code STATIC} freezes the
     *  animation and uses {@link Layer#scrollOffset} as a manual position instead. The eight compass values
     *  go counter-clockwise from East. Default is {@code E} (the historical horizontal scroll). */
    public static final int SCROLL_STATIC = 0, SCROLL_E = 1, SCROLL_NE = 2, SCROLL_N = 3, SCROLL_NW = 4,
            SCROLL_W = 5, SCROLL_SW = 6, SCROLL_S = 7, SCROLL_SE = 8;

    /** Max colors a single layer may carry. Enforced at every input boundary (wand editor, packets, NBT
     *  decode via {@link Layer#CODEC}); the renderer cycles through them, so more would never display. */
    public static final int MAX_COLORS_PER_LAYER = 8;

    /** Most-translucent glint alpha (at the 8th glass); 0 glass = fully opaque (255). Shared so the Glint
     *  Table's opacity↔alpha↔glass mapping matches on both the client (preview/cost) and the server (print). */
    public static final int GLINT_ALPHA_MIN = 32;

    /** Glint-Table material cost of a speed / scale value: the number of ± stepper clicks it sits from the 1×
     *  default (0.10 per click below 1×, 0.5 per click above). Each click costs one redstone / slime, so a
     *  fully-tuned layer is a real material sink. Both sides tally this per layer. */
    public static int stepCost(float value) {
        if (value >= 1.0f) return Math.round((value - 1.0f) / 0.5f);
        return Math.round((1.0f - value) / 0.10f);
    }

    /** Glint-Table glass cost for a colour's alpha: the opacity level (0..8) it encodes, i.e. one glass per
     *  opacity click. Inverts the opacity→alpha mapping ({@code alpha = 255 - level*(255-MIN)/8}). */
    public static int glassCost(int alpha) {
        return Math.round((255f - alpha) * 8f / (255f - GLINT_ALPHA_MIN));
    }

    public record Layer(Identifier design, int[] colors, float speed, boolean interpolate, float patternScale,
                        boolean simultaneous, int scrollDir, float scrollOffset, int seed) {
        /** Back-compat constructor for the eight-field call sites (recipes, packets, tear apply): the
         *  procedural-chromatic {@link #seed} defaults to 0 (only {@link #CHROMATIC} layers use it). */
        public Layer(Identifier design, int[] colors, float speed, boolean interpolate, float patternScale,
                     boolean simultaneous, int scrollDir, float scrollOffset) {
            this(design, colors, speed, interpolate, patternScale, simultaneous, scrollDir, scrollOffset, 0);
        }

        public static final Codec<Layer> CODEC = RecordCodecBuilder.create(i -> i.group(
                Identifier.CODEC.fieldOf("design").forGetter(Layer::design),
                // Bound the color list to the same 8-color cap every other path enforces (item NBT, the wand
                // editor, the print/apply/give packets). Without it, a crafted print-packet NBT layer or a
                // future component decode could carry an arbitrarily large color array straight into a stored
                // ItemStack/attachment. sizeLimitedListOf rejects an oversized list during decode.
                Codec.INT.sizeLimitedListOf(MAX_COLORS_PER_LAYER).xmap(
                        list -> { int[] a = new int[list.size()]; for (int n = 0; n < a.length; n++) a[n] = list.get(n); return a; },
                        arr -> Arrays.stream(arr).boxed().toList()
                ).fieldOf("colors").forGetter(Layer::colors),
                Codec.FLOAT.optionalFieldOf("speed", 1.0f).forGetter(Layer::speed),
                Codec.BOOL.optionalFieldOf("interpolate", true).forGetter(Layer::interpolate),
                Codec.FLOAT.optionalFieldOf("scale", 1.0f).forGetter(Layer::patternScale),
                Codec.BOOL.optionalFieldOf("simultaneous", true).forGetter(Layer::simultaneous),
                Codec.INT.optionalFieldOf("scroll", SCROLL_E).forGetter(Layer::scrollDir),
                Codec.FLOAT.optionalFieldOf("offset", 0.0f).forGetter(Layer::scrollOffset),
                Codec.INT.optionalFieldOf("seed", 0).forGetter(Layer::seed)
        ).apply(i, Layer::new));

        // Value equality (the record default would compare the int[] by identity, breaking item
        // stacking / ItemStack.matches / recipe matching, the old NBT was value-equal).
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Layer l)) return false;
            return Float.compare(speed, l.speed) == 0 && interpolate == l.interpolate
                    && Float.compare(patternScale, l.patternScale) == 0 && simultaneous == l.simultaneous
                    && scrollDir == l.scrollDir && Float.compare(scrollOffset, l.scrollOffset) == 0
                    && seed == l.seed
                    && Objects.equals(design, l.design) && Arrays.equals(colors, l.colors);
        }
        @Override public int hashCode() {
            return Objects.hash(design, speed, interpolate, patternScale, simultaneous, scrollDir, scrollOffset, seed) * 31
                    + Arrays.hashCode(colors);
        }
    }

    // ── Data ─────────────────────────────────────────────────────────────────

    public record Data(Layer[] layers) {
        /** Root form: {@code { "layers": [ {design, colors, speed, interpolate, scale, simultaneous}, … ] }}.
         *  Glow state (glowing / glowColors) is stored as sibling keys in the same tag,
         *  outside this codec. */
        public static final Codec<Data> CODEC = Layer.CODEC.listOf().xmap(
                list -> new Data(list.toArray(new Layer[0])),
                data -> List.of(data.layers())
        ).fieldOf("layers").codec();

        @Override public boolean equals(Object o) {
            return this == o || (o instanceof Data d && Arrays.equals(layers, d.layers));
        }
        @Override public int hashCode() { return Arrays.hashCode(layers); }
    }

    // ── Entity glint state (attachment payload) ───────────────────────────────

    /**
     * Per-entity glint state: the glint {@link Data} (nullable) plus the three glow flags. The payload for
     * both glint registries, the {@link CustomGlintComponents#GLINT} item data component and the
     * {@link CustomGlintComponents#ENTITY_GLINT} entity attachment. Codec-serialized; the attachment
     * auto-syncs on write, so there is no manual sync packet.
     */
    public record GlintState(@Nullable Data data, boolean glowing, int[] glowColors, float glowSpeed, boolean glowInterp) {
        public static final GlintState EMPTY = new GlintState(null, false, new int[0], 1.0f, true);

        public boolean isEmpty() {
            // glowSpeed / glowInterp are modifiers of an existing glow, meaningless without it, so they don't
            // count toward emptiness: a stack carrying only a non-default glow speed but no glow is still empty.
            return data == null && !glowing && glowColors.length == 0;
        }

        public static final MapCodec<GlintState> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Data.CODEC.optionalFieldOf("glint").forGetter(s -> Optional.ofNullable(s.data())),
                Codec.BOOL.optionalFieldOf("glowing", false).forGetter(GlintState::glowing),
                // Same 8-color cap as Layer.colors above (every input path, Glow Trim, the print/apply
                // packets, the wand editor, already enforces it). Without it a crafted component/packet
                // could round-trip an arbitrarily large glow-color list into a stored item / synced attachment.
                Codec.INT.sizeLimitedListOf(MAX_COLORS_PER_LAYER).xmap(
                        list -> { int[] a = new int[list.size()]; for (int n = 0; n < a.length; n++) a[n] = list.get(n); return a; },
                        arr -> Arrays.stream(arr).boxed().toList()
                ).optionalFieldOf("glowColors", new int[0]).forGetter(GlintState::glowColors),
                // Glow-outline animation speed + interpolation, kept alongside the glow colors. Default 1.0 /
                // true so any pre-existing item decodes to the vanilla cycle (they're absent from its tag).
                Codec.FLOAT.optionalFieldOf("glowSpeed", 1.0f).forGetter(GlintState::glowSpeed),
                Codec.BOOL.optionalFieldOf("glowInterp", true).forGetter(GlintState::glowInterp)
        ).apply(i, (glint, glowing, colors, speed, interp) -> new GlintState(glint.orElse(null), glowing, colors, speed, interp)));

        public static final Codec<GlintState> CODEC = MAP_CODEC.codec();
        public static final StreamCodec<ByteBuf, GlintState> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GlintState g)) return false;
            return glowing == g.glowing && glowInterp == g.glowInterp && Float.compare(glowSpeed, g.glowSpeed) == 0
                    && Objects.equals(data, g.data) && Arrays.equals(glowColors, g.glowColors);
        }
        @Override public int hashCode() {
            return Objects.hash(data, glowing, glowSpeed, glowInterp) * 31 + Arrays.hashCode(glowColors);
        }
    }

    // ── Colors ────────────────────────────────────────────────────────────────

    /** Parses a {@code "RRGGBB"} / {@code "#RRGGBB"} hex string to an opaque ARGB int. For string color
     *  sources, commands, data packs, the config, where the value isn't a compile-time constant. The
     *  named constants below come from {@link DyeColor}; only external string input flows through here. */
    public static int color(String hex) {
        return Integer.parseUnsignedInt(hex.startsWith("#") ? hex.substring(1) : hex, 16) | 0xFF000000;
    }

    /** Builds an {@link Identifier} under this mod's namespace. */
    public static Identifier res(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    // The 16 named glint colors ARE Minecraft's dye colors, sourced from DyeColor.getTextColor()
    // (the vivid per-dye colour, already opaque ARGB) so they track vanilla instead of drifting.
    public static final int RED        = DyeColor.RED.getTextColor();
    public static final int ORANGE     = DyeColor.ORANGE.getTextColor();
    public static final int YELLOW     = DyeColor.YELLOW.getTextColor();
    public static final int LIME       = DyeColor.LIME.getTextColor();
    public static final int GREEN      = DyeColor.GREEN.getTextColor();
    public static final int CYAN       = DyeColor.CYAN.getTextColor();
    public static final int LIGHT_BLUE = DyeColor.LIGHT_BLUE.getTextColor();
    public static final int BLUE       = DyeColor.BLUE.getTextColor();
    public static final int PURPLE     = DyeColor.PURPLE.getTextColor();
    public static final int MAGENTA    = DyeColor.MAGENTA.getTextColor();
    public static final int PINK       = DyeColor.PINK.getTextColor();
    public static final int BROWN      = DyeColor.BROWN.getTextColor();
    public static final int WHITE      = DyeColor.WHITE.getTextColor();
    public static final int LIGHT_GRAY = DyeColor.LIGHT_GRAY.getTextColor();
    public static final int GRAY       = DyeColor.GRAY.getTextColor();
    public static final int BLACK      = DyeColor.BLACK.getTextColor();

    // ── Designs ───────────────────────────────────────────────────────────────

    public static final Identifier VANILLA    = Identifier.fromNamespaceAndPath("minecraft", "textures/misc/enchanted_glint_item.png");
    /** Procedural chromatic "design", has no PNG. Each {@link Layer} carries a random {@link Layer#seed};
     *  the chromatic shader generates per-seed oil-slick noise tinted by the layer's colors (or a rainbow
     *  hue fallback when none are set). The render factories route it specially and never sample it as a
     *  texture. Because the seed is rolled per trim, no two chromatic trims look alike. */
    public static final Identifier CHROMATIC  = res("chromatic");
    public static final Identifier ARCS      = res("textures/glint/arcs.png");
    public static final Identifier AURORA    = res("textures/glint/aurora.png");
    public static final Identifier BLOBS     = res("textures/glint/blobs.png");
    public static final Identifier CASCADE   = res("textures/glint/cascade.png");
    public static final Identifier CHECKER   = res("textures/glint/checker.png");
    public static final Identifier CHEVRON   = res("textures/glint/chevron.png");
    public static final Identifier CORAL     = res("textures/glint/coral.png");
    public static final Identifier CRACKS    = res("textures/glint/cracks.png");
    public static final Identifier CROSSHATCH = res("textures/glint/crosshatch.png");
    public static final Identifier CRYSTAL   = res("textures/glint/crystal.png");
    public static final Identifier DEBRIS    = res("textures/glint/debris.png");
    public static final Identifier DIAMONDS  = res("textures/glint/diamonds.png");
    public static final Identifier DUNES     = res("textures/glint/dunes.png");
    public static final Identifier EMBER     = res("textures/glint/ember.png");
    public static final Identifier FEATHER   = res("textures/glint/feather.png");
    public static final Identifier FIRE      = res("textures/glint/fire.png");
    public static final Identifier FROST     = res("textures/glint/frost.png");
    public static final Identifier GLITCH    = res("textures/glint/glitch.png");
    public static final Identifier GLOW      = res("textures/glint/glow.png");
    public static final Identifier GRID      = res("textures/glint/grid.png");
    public static final Identifier HALO      = res("textures/glint/halo.png");
    public static final Identifier HEXAGON   = res("textures/glint/hexagon.png");
    public static final Identifier LIGHTNING = res("textures/glint/lightning.png");
    public static final Identifier MARBLE    = res("textures/glint/marble.png");
    public static final Identifier MATRIX    = res("textures/glint/matrix.png");
    public static final Identifier MESH      = res("textures/glint/mesh.png");
    public static final Identifier MOSAIC    = res("textures/glint/mosaic.png");
    public static final Identifier NET       = res("textures/glint/net.png");
    public static final Identifier OIL       = res("textures/glint/oil.png");
    public static final Identifier PETAL     = res("textures/glint/petal.png");
    public static final Identifier PLASMA    = res("textures/glint/plasma.png");
    public static final Identifier PLATE     = res("textures/glint/plate.png");
    public static final Identifier PRISM     = res("textures/glint/prism.png");
    public static final Identifier PULSE     = res("textures/glint/pulse.png");
    public static final Identifier RIPPLE    = res("textures/glint/ripple.png");
    public static final Identifier SAND      = res("textures/glint/sand.png");
    public static final Identifier SCALES    = res("textures/glint/scales.png");
    public static final Identifier SHEEN     = res("textures/glint/sheen.png");
    public static final Identifier SHIMMER   = res("textures/glint/shimmer.png");
    public static final Identifier SILK      = res("textures/glint/silk.png");
    public static final Identifier SLASH     = res("textures/glint/slash.png");
    public static final Identifier SMOKE     = res("textures/glint/smoke.png");
    public static final Identifier SOLID     = res("textures/glint/solid.png");
    public static final Identifier SPARKLE   = res("textures/glint/sparkle.png");
    public static final Identifier STARS     = res("textures/glint/stars.png");
    public static final Identifier STATIC    = res("textures/glint/static.png");
    public static final Identifier STRIPES   = res("textures/glint/stripes.png");
    public static final Identifier SWIRL     = res("textures/glint/swirl.png");
    public static final Identifier TIDE      = res("textures/glint/tide.png");
    public static final Identifier TILE      = res("textures/glint/tile.png");
    public static final Identifier VEIN      = res("textures/glint/vein.png");
    public static final Identifier WAVE      = res("textures/glint/wave.png");
    public static final Identifier WEAVE     = res("textures/glint/weave.png");
    public static final Identifier ZIGZAG    = res("textures/glint/zigzag.png");

    public static final Identifier[] PATTERNS = {
            VANILLA, CHROMATIC,
            ARCS, AURORA, BLOBS, CASCADE, CHECKER, CHEVRON, CORAL, CRACKS,
            CROSSHATCH, CRYSTAL, DEBRIS, DIAMONDS, DUNES, EMBER, FEATHER, FIRE,
            FROST, GLITCH, GLOW, GRID, HALO, HEXAGON, LIGHTNING, MARBLE,
            MATRIX, MESH, MOSAIC, NET, OIL, PETAL, PLASMA, PLATE,
            PRISM, PULSE, RIPPLE, SAND, SCALES, SHEEN, SHIMMER, SILK,
            SLASH, SMOKE, SOLID, SPARKLE, STARS, STATIC, STRIPES, SWIRL,
            TIDE, TILE, VEIN, WAVE, WEAVE, ZIGZAG
    };

    /** Saturated colors only, used by JEI plugin and trim creative tab for preset display. */
    public static final int[] VIBRANT_COLORS = {
            RED, ORANGE, YELLOW, LIME, GREEN, CYAN, LIGHT_BLUE, BLUE, PURPLE, MAGENTA, PINK
    };

    /** All 16 named colors including neutrals, full palette for downstream mod use. */
    public static final int[] ALL_COLORS = {
            RED, ORANGE, YELLOW, LIME, GREEN, CYAN, LIGHT_BLUE, BLUE, PURPLE, MAGENTA, PINK,
            BROWN, WHITE, LIGHT_GRAY, GRAY, BLACK
    };

    /** A fresh nonzero seed for a new {@link #CHROMATIC} layer (0 means "no seed / not chromatic"), so two
     *  chromatic glints never share an oil-slick pattern. */
    public static int randomChromaticSeed() {
        int s;
        do { s = ThreadLocalRandom.current().nextInt(); } while (s == 0);
        return s;
    }

    public static boolean isChromatic(Identifier design) { return CHROMATIC.equals(design); }
    public static boolean isChromatic(Layer layer) { return layer != null && CHROMATIC.equals(layer.design()); }

    /** Returns {@code layers} with a fresh nonzero {@link Layer#seed} rolled into every {@link #CHROMATIC}
     *  layer that arrives unseeded (seed 0). Non-chromatic layers and already-seeded chromatic layers pass
     *  through unchanged. Call this server-side at the point layers are committed (packet handlers, the Glint
     *  Table) so editor / wire paths that build layers without rolling a seed still get unique patterns. */
    public static Layer[] ensureChromaticSeeds(Layer[] layers) {
        Layer[] out = layers;
        for (int i = 0; i < layers.length; i++) {
            Layer l = layers[i];
            if (isChromatic(l) && l.seed() == 0) {
                if (out == layers) out = layers.clone();
                out[i] = new Layer(l.design(), l.colors(), l.speed(), l.interpolate(), l.patternScale(),
                        l.simultaneous(), l.scrollDir(), l.scrollOffset(), randomChromaticSeed());
            }
        }
        return out;
    }

    /** Resolves a design <em>name</em> (as stored on a Trim / typed in a command / shown in the picker)
     *  to its design {@link Identifier}. Handles the {@code vanilla} and {@code chromatic} sentinels and
     *  {@code namespace:name} qualified names; everything else maps to {@code <ns>:textures/glint/<name>.png}.
     *  Single source of truth for the editor, creative tab, and command resolvers. */
    public static Identifier designFromName(String name) {
        if ("vanilla".equals(name)) return VANILLA;
        if ("chromatic".equals(name)) return CHROMATIC;
        // tryParse (not the throwing fromNamespaceAndPath), this runs server-side on remote-controlled
        // strings (e.g. GlintGiveDesignPacket), so a malformed name must fall back, never crash the tick.
        Identifier id;
        if (name.indexOf(':') >= 0) {
            int c = name.indexOf(':');
            id = Identifier.tryParse(name.substring(0, c) + ":textures/glint/" + name.substring(c + 1) + ".png");
        } else {
            id = Identifier.tryParse(MOD_ID + ":textures/glint/" + name + ".png");
        }
        return id != null ? id : VANILLA;
    }

    // ── Item glint storage ────────────────────────────────────────────────────
    // All field names ("layers"/"glint"/"glowing"/"glowColors"/…) live in the codecs
    // (Layer.CODEC / Data.CODEC / GlintState.MAP_CODEC); there are no loose NBT key constants any more.

    // Item glint state is a typed GlintState data component (see GLINT), no more CompoundTag in
    // CUSTOM_DATA. These accessors keep their old signatures so callers (recipes, GUI, packets, the
    // trim items, the renderer) are unaffected by the storage change.

    private static GlintState itemState(ItemStack stack) {
        if (stack.isEmpty()) return GlintState.EMPTY;
        GlintState s = stack.get(CustomGlintComponents.GLINT.get());
        return s == null ? GlintState.EMPTY : s;
    }

    /** Stores the state, or removes the component when the state is empty. */
    private static void setItemState(ItemStack stack, GlintState state) {
        if (state.isEmpty()) stack.remove(CustomGlintComponents.GLINT.get());
        else stack.set(CustomGlintComponents.GLINT.get(), state);
    }

    /** The stack's full glint state (never null; {@link GlintState#EMPTY} when none). This is the
     *  component-era transfer primitive: {@code GlintState} is an immutable value, so copying it to
     *  another holder ({@link #writeState} / {@link #writeEntityState}) needs no serialization. Prefer
     *  this over the {@link #itemGlintTag} CompoundTag bridge for in-memory item↔entity transfer. */
    public static GlintState readState(ItemStack stack) {
        return itemState(stack);
    }

    /** Overwrites the stack's full glint state in one shot ({@link GlintState#EMPTY} clears it). */
    public static void writeState(ItemStack stack, GlintState state) {
        setItemState(stack, state);
    }

    @Nullable
    public static Data read(ItemStack stack) {
        return itemState(stack).data();
    }

    public static boolean has(ItemStack stack) {
        return !itemState(stack).isEmpty();
    }

    /** Sets the glint layers, preserving the stack's existing glow flags. */
    public static void write(ItemStack stack, Layer[] layers) {
        GlintState cur = itemState(stack);
        setItemState(stack, new GlintState(new Data(layers), cur.glowing(), cur.glowColors(), cur.glowSpeed(), cur.glowInterp()));
    }

    public static void write(ItemStack stack, Identifier design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        write(stack, design, colors, speed, interpolate, patternScale, simultaneous, SCROLL_E, 0.0f);
    }

    public static void write(ItemStack stack, Identifier design, int[] colors, float speed, boolean interpolate,
                             float patternScale, boolean simultaneous, int scrollDir, float scrollOffset) {
        write(stack, design, colors, speed, interpolate, patternScale, simultaneous, scrollDir, scrollOffset, 0);
    }

    /** As above plus the procedural-chromatic {@link Layer#seed} (ignored by non-{@link #CHROMATIC} designs). */
    public static void write(ItemStack stack, Identifier design, int[] colors, float speed, boolean interpolate,
                             float patternScale, boolean simultaneous, int scrollDir, float scrollOffset, int seed) {
        write(stack, new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous, scrollDir, scrollOffset, seed) });
    }

    public static void remove(ItemStack stack) {
        stack.remove(CustomGlintComponents.GLINT.get());
    }

    public static boolean isGlowing(ItemStack stack) {
        return itemState(stack).glowing();
    }

    public static void setGlowing(ItemStack stack, boolean glowing) {
        GlintState cur = itemState(stack);
        setItemState(stack, new GlintState(cur.data(), glowing, cur.glowColors(), cur.glowSpeed(), cur.glowInterp()));
    }

    /** Glow Trim colors, drive the outline color animation independently of any glint Data. */
    public static int[] getGlowColors(ItemStack stack) {
        return itemState(stack).glowColors();
    }

    public static boolean hasGlowColors(ItemStack stack) {
        return getGlowColors(stack).length > 0;
    }

    /** Sets glowColors AND glowing=true. Independent of any glint Data on the stack. */
    public static void setGlowColors(ItemStack stack, int[] colors) {
        GlintState cur = itemState(stack);
        setItemState(stack, new GlintState(cur.data(), true, colors, cur.glowSpeed(), cur.glowInterp()));
    }

    public static void clearGlowColors(ItemStack stack) {
        GlintState cur = itemState(stack);
        if (cur.glowColors().length == 0) return;
        setItemState(stack, new GlintState(cur.data(), cur.glowing(), new int[0], cur.glowSpeed(), cur.glowInterp()));
    }

    /** Glow-outline animation speed (how fast the outline cycles its glow colors), default 1.0. */
    public static float getGlowSpeed(ItemStack stack) {
        return itemState(stack).glowSpeed();
    }

    /** Whether the glow outline blends smoothly between its colors (default true) or steps hard between them. */
    public static boolean getGlowInterpolate(ItemStack stack) {
        return itemState(stack).glowInterp();
    }

    /** Sets the glow outline's animation speed + interpolation (kept alongside {@code glowColors}). Only
     *  meaningful on a glowing stack, {@link GlintState#isEmpty()} ignores these, so calling it on an
     *  otherwise-empty stack still stores nothing. */
    public static void setGlowAnim(ItemStack stack, float speed, boolean interpolate) {
        GlintState cur = itemState(stack);
        setItemState(stack, new GlintState(cur.data(), cur.glowing(), cur.glowColors(), speed, interpolate));
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
        CRAFT_GLINTS.put(item, new Data(new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous, SCROLL_E, 0.0f) }));
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
        FISHING_GLINTS.put(item, new Data(new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous, SCROLL_E, 0.0f) }));
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
        MOB_DROP_GLINTS.put(item, new Data(new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous, SCROLL_E, 0.0f) }));
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
    // Per-instance state is the synced {@link CustomGlintComponents#ENTITY_GLINT} attachment; writing it
    // server-side auto-syncs to tracking clients and the renderer reads it directly. Type-wide defaults
    // live in ENTITY_GLINTS, consulted as a fallback when an entity has no per-instance state.

    private static GlintState entityState(LivingEntity entity) {
        GlintState s = entity.getExistingDataOrNull(CustomGlintComponents.ENTITY_GLINT);
        return s == null ? GlintState.EMPTY : s;
    }

    /** Stores the state, or removes the attachment when empty. Either path auto-syncs to trackers. */
    private static void setEntityState(LivingEntity entity, GlintState state) {
        if (state.isEmpty()) entity.removeData(CustomGlintComponents.ENTITY_GLINT);
        else entity.setData(CustomGlintComponents.ENTITY_GLINT, state);
    }

    /** The entity's full glint state (never null; {@link GlintState#EMPTY} when none). The typed
     *  counterpart to {@link #readState}; copy the value straight to an item with {@link #writeState}
     *  for a serialization-free entity→item transfer. */
    public static GlintState readEntityState(LivingEntity entity) {
        return entityState(entity);
    }

    /** Overwrites the entity's full glint state in one shot ({@link GlintState#EMPTY} clears it). Auto-syncs. */
    public static void writeEntityState(LivingEntity entity, GlintState state) {
        setEntityState(entity, state);
    }

    public static final Map<EntityType<?>, Data> ENTITY_GLINTS = new HashMap<>();

    public static void registerEntityGlint(EntityType<?> type, Data data) {
        ENTITY_GLINTS.put(type, data);
    }

    public static void registerEntityGlint(EntityType<?> type, Identifier design, int[] colors) {
        registerEntityGlint(type, new Data(new Layer[]{ new Layer(design, colors, 1.0f, true, 1.0f, true, SCROLL_E, 0.0f) }));
    }

    @Nullable
    public static Data getEntityGlint(EntityType<?> type) {
        return ENTITY_GLINTS.get(type);
    }

    /** The per-instance entity glint Data (null if none). */
    @Nullable
    public static Data readEntity(LivingEntity entity) {
        return entityState(entity).data();
    }

    public static boolean hasEntity(LivingEntity entity) {
        return !entityState(entity).isEmpty();
    }

    /** Sets the glint layers, preserving the entity's existing glow flags. Auto-syncs to trackers. */
    public static void writeEntity(LivingEntity entity, Layer[] layers) {
        GlintState cur = entityState(entity);
        setEntityState(entity, new GlintState(new Data(layers), cur.glowing(), cur.glowColors(), cur.glowSpeed(), cur.glowInterp()));
    }

    public static void removeEntity(LivingEntity entity) {
        entity.removeData(CustomGlintComponents.ENTITY_GLINT);
    }

    public static boolean isEntityGlowing(LivingEntity entity) {
        return entityState(entity).glowing();
    }

    public static void setEntityGlowing(LivingEntity entity, boolean glowing) {
        GlintState cur = entityState(entity);
        setEntityState(entity, new GlintState(cur.data(), glowing, cur.glowColors(), cur.glowSpeed(), cur.glowInterp()));
    }

    /** Per-entity Glow Trim colors, drive the outline color animation independently of any
     *  glint Data, identical semantics to {@link #getGlowColors(ItemStack)} but on a mob. */
    public static int[] getEntityGlowColors(LivingEntity entity) {
        return entityState(entity).glowColors();
    }

    public static boolean hasEntityGlowColors(LivingEntity entity) {
        return getEntityGlowColors(entity).length > 0;
    }

    /** Sets glowColors AND glowing=true on the entity. Auto-syncs to tracking clients (no manual call). */
    public static void setEntityGlowColors(LivingEntity entity, int[] colors) {
        GlintState cur = entityState(entity);
        setEntityState(entity, new GlintState(cur.data(), true, colors, cur.glowSpeed(), cur.glowInterp()));
    }

    public static void clearEntityGlowColors(LivingEntity entity) {
        GlintState cur = entityState(entity);
        if (cur.glowColors().length == 0) return;
        setEntityState(entity, new GlintState(cur.data(), cur.glowing(), new int[0], cur.glowSpeed(), cur.glowInterp()));
    }

    // ── CompoundTag bridge (serialized snapshot / restore) ────────────────────
    // The GlintState codec driven through NbtOps, for the cases that genuinely need a serialized form:
    // a stored snapshot, give NBT, /data, a custom packet. For in-memory item↔entity transfer prefer the
    // typed readState/writeState accessors above, GlintState is an immutable value, so a direct copy
    // skips this encode/decode round-trip. An item tag and an entity tag share one shape (same codec).

    private static CompoundTag stateToTag(GlintState state) {
        if (state.isEmpty()) return new CompoundTag();
        return (CompoundTag) GlintState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
    }

    private static GlintState stateFromTag(@Nullable CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return GlintState.EMPTY;
        return GlintState.CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(GlintState.EMPTY);
    }

    /** The stack's glint state as a CompoundTag (empty if none). */
    public static CompoundTag itemGlintTag(ItemStack stack) {
        return stateToTag(itemState(stack));
    }

    /** The entity's glint state as a CompoundTag (empty if none). Same shape as {@link #itemGlintTag}.
     *  For a live item↔entity copy prefer {@link #readEntityState} + {@link #writeState}; reach for this
     *  only when you actually want the serialized tag. */
    public static CompoundTag entityGlintTag(LivingEntity entity) {
        return stateToTag(entityState(entity));
    }

    /** Overwrites the entity's glint state from a tag in one shot (empty/null clears it). Auto-syncs. */
    public static void writeEntityTag(LivingEntity entity, CompoundTag glintTag) {
        setEntityState(entity, stateFromTag(glintTag));
    }

    /** Overwrites the stack's glint state from a tag in one shot (empty/null clears it). */
    public static void writeItemTag(ItemStack stack, CompoundTag glintTag) {
        setItemState(stack, stateFromTag(glintTag));
    }

    // ── Tag inspectors (pull fields out of a glint CompoundTag) ───────────────

    /** The glint {@link Data} from a glint tag, or null if none. */
    @Nullable
    public static Data fromTag(@Nullable CompoundTag glintTag) {
        return stateFromTag(glintTag).data();
    }

    public static boolean tagGlowing(@Nullable CompoundTag glintTag) {
        return stateFromTag(glintTag).glowing();
    }

    public static int[] tagGlowColors(@Nullable CompoundTag glintTag) {
        return stateFromTag(glintTag).glowColors();
    }

    /** A glint tag holding just these layers (no glow flags). */
    public static CompoundTag toTag(Layer[] layers) {
        return stateToTag(new GlintState(new Data(layers), false, new int[0], 1.0f, true));
    }

    public static final Map<Identifier, Map<Item, Data>> LOOT_GLINTS = new HashMap<>();

    public static void registerLootGlint(Identifier lootTable, Item item, Identifier design, int[] colors, float speed, boolean interpolate, float patternScale, boolean simultaneous) {
        LOOT_GLINTS.computeIfAbsent(lootTable, k -> new HashMap<>()).put(item, new Data(new Layer[]{ new Layer(design, colors, speed, interpolate, patternScale, simultaneous, SCROLL_E, 0.0f) }));
    }

    public static void registerLootGlint(Identifier lootTable, Item item, Identifier design, int[] colors) {
        registerLootGlint(lootTable, item, design, colors, 1.0f, true, 1.0f, true);
    }
}
