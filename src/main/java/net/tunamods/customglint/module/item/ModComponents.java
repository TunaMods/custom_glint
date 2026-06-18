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

    /** The Glint Trim's editable config — pattern, color list, speed, scale, glowing flag. */
    public record TrimConfig(Optional<Identifier> pattern, List<Integer> colors, float speed, float scale, boolean glowing) {
        public static final TrimConfig EMPTY = new TrimConfig(Optional.empty(), List.of(), 1.0f, 1.0f, false);

        public static final Codec<TrimConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
                Identifier.CODEC.optionalFieldOf("pattern").forGetter(TrimConfig::pattern),
                Codec.INT.listOf().optionalFieldOf("colors", List.of()).forGetter(TrimConfig::colors),
                Codec.FLOAT.optionalFieldOf("speed", 1.0f).forGetter(TrimConfig::speed),
                Codec.FLOAT.optionalFieldOf("scale", 1.0f).forGetter(TrimConfig::scale),
                Codec.BOOL.optionalFieldOf("glowing", false).forGetter(TrimConfig::glowing)
        ).apply(i, TrimConfig::new));

        public static final StreamCodec<ByteBuf, TrimConfig> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);
    }

    public static final Supplier<DataComponentType<TrimConfig>> TRIM =
            DATA_COMPONENTS.registerComponentType("trim", b -> b
                    .persistent(TrimConfig.CODEC)
                    .networkSynchronized(TrimConfig.STREAM_CODEC));

    /** The Glow Trim's color list (drives the glow-color animation on smithing). */
    public static final Supplier<DataComponentType<List<Integer>>> GLOW_TRIM =
            DATA_COMPONENTS.registerComponentType("glow_trim", b -> b
                    .persistent(Codec.INT.listOf())
                    .networkSynchronized(ByteBufCodecs.fromCodec(Codec.INT.listOf())));

    public static void register(IEventBus modEventBus) {
        DATA_COMPONENTS.register(modEventBus);
    }
}
