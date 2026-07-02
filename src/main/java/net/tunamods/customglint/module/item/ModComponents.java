package net.tunamods.customglint.module.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.tunamods.customglint.common.CustomGlint;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static net.tunamods.customglint.CustomGlintMod.MOD_ID;

/**
 * Full-jar item data components for the Trim items. The api-side item-glint component lives in
 * {@link net.tunamods.customglint.common.CustomGlintComponents}; these are standalone-only because the
 * Trim items are. Colors use {@code List<Integer>} so the components are value-equal (items stack and
 * {@code ItemStack.matches} works, matching the old NBT behaviour).
 */
public final class ModComponents {
    private ModComponents() {}

    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MOD_ID);

    /** The Glint Trim's editable config, pattern, color list, speed, scale, scroll direction + static
     *  offset, glowing flag. {@code scroll}/{@code offset} mirror {@link CustomGlint.Layer}. */
    public record TrimConfig(Optional<Identifier> pattern, List<Integer> colors, float speed, float scale,
                             int scroll, float offset, boolean glowing, int seed) {
        /** Back-compat constructor for the seven-field call sites: {@code seed} defaults to 0 (only
         *  {@link CustomGlint#CHROMATIC} trims roll a real one). */
        public TrimConfig(Optional<Identifier> pattern, List<Integer> colors, float speed, float scale,
                          int scroll, float offset, boolean glowing) {
            this(pattern, colors, speed, scale, scroll, offset, glowing, 0);
        }

        public static final TrimConfig EMPTY = new TrimConfig(Optional.empty(), List.of(), 1.0f, 1.0f,
                CustomGlint.SCROLL_E, 0.0f, false);

        public static final Codec<TrimConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
                Identifier.CODEC.optionalFieldOf("pattern").forGetter(TrimConfig::pattern),
                Codec.INT.sizeLimitedListOf(CustomGlint.MAX_COLORS_PER_LAYER).optionalFieldOf("colors", List.of()).forGetter(TrimConfig::colors),
                Codec.FLOAT.optionalFieldOf("speed", 1.0f).forGetter(TrimConfig::speed),
                Codec.FLOAT.optionalFieldOf("scale", 1.0f).forGetter(TrimConfig::scale),
                Codec.INT.optionalFieldOf("scroll", CustomGlint.SCROLL_E).forGetter(TrimConfig::scroll),
                Codec.FLOAT.optionalFieldOf("offset", 0.0f).forGetter(TrimConfig::offset),
                Codec.BOOL.optionalFieldOf("glowing", false).forGetter(TrimConfig::glowing),
                Codec.INT.optionalFieldOf("seed", 0).forGetter(TrimConfig::seed)
        ).apply(i, TrimConfig::new));

        public static final StreamCodec<ByteBuf, TrimConfig> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);
    }

    public static final Supplier<DataComponentType<TrimConfig>> TRIM =
            DATA_COMPONENTS.registerComponentType("trim", b -> b
                    .persistent(TrimConfig.CODEC)
                    .networkSynchronized(TrimConfig.STREAM_CODEC));

    /** The Glow Trim's color list (drives the glow-color animation on smithing). Capped at 8 to match
     *  every input path (GlowTrimItem.addColor / mergeColors) so a crafted component can't store more. */
    public static final Supplier<DataComponentType<List<Integer>>> GLOW_TRIM =
            DATA_COMPONENTS.registerComponentType("glow_trim", b -> b
                    .persistent(Codec.INT.sizeLimitedListOf(CustomGlint.MAX_COLORS_PER_LAYER))
                    .networkSynchronized(ByteBufCodecs.fromCodec(Codec.INT.sizeLimitedListOf(CustomGlint.MAX_COLORS_PER_LAYER))));

    /** Marks a printed-library trim that was imported from a config file but not yet crafted: it renders
     *  dimmed in the Glint Table's right grid and can't be withdrawn until the player prints a matching
     *  trim, which clears the flag. Present (true) = locked; absent = a normal, owned printed trim. */
    public static final Supplier<DataComponentType<Boolean>> IMPORT_LOCKED =
            DATA_COMPONENTS.registerComponentType("import_locked", b -> b
                    .persistent(Codec.BOOL)
                    .networkSynchronized(ByteBufCodecs.BOOL));

    public static void register(IEventBus modEventBus) {
        DATA_COMPONENTS.register(modEventBus);
    }
}
