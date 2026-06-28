package net.tunamods.customglint.module.item;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.tunamods.customglint.common.CustomGlint;

import static net.tunamods.customglint.CustomGlintMod.MOD_ID;

/**
 * Full-jar item data components for the Trim items. The api-side glint component lives in
 * {@link net.tunamods.customglint.common.CustomGlintComponents}; these are standalone-only because the
 * Trim items are. Colors use {@code List<Integer>} so the components are value-equal (items stack and
 * {@code ItemStack.matches} works, matching the old NBT behaviour).
 */
public final class ModComponents {
    private ModComponents() {}

    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MOD_ID);

    /** The Glint Trim's editable config: pattern, color list, speed, scale, scroll direction + static
     *  offset, glowing flag, and the procedural-chromatic seed. {@code scroll}/{@code offset}/{@code seed}
     *  mirror {@link CustomGlint.Layer}. */
    public record TrimConfig(Optional<ResourceLocation> pattern, List<Integer> colors, float speed, float scale,
                             int scroll, float offset, boolean glowing, int seed) {
        public static final TrimConfig EMPTY = new TrimConfig(Optional.empty(), List.of(), 1.0f, 1.0f,
                CustomGlint.SCROLL_E, 0.0f, false, 0);

        public static final Codec<TrimConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceLocation.CODEC.optionalFieldOf("pattern").forGetter(TrimConfig::pattern),
                Codec.INT.listOf().optionalFieldOf("colors", List.of()).forGetter(TrimConfig::colors),
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

    /** The Glow Trim's color list (drives the glow-color animation on smithing). */
    public static final Supplier<DataComponentType<List<Integer>>> GLOW_TRIM =
            DATA_COMPONENTS.registerComponentType("glow_trim", b -> b
                    .persistent(Codec.INT.listOf())
                    .networkSynchronized(ByteBufCodecs.fromCodec(Codec.INT.listOf())));

    public static void register(IEventBus modEventBus) {
        DATA_COMPONENTS.register(modEventBus);
    }
}
