package net.tunamods.customglint.common;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.tunamods.customglint.common.CustomGlint.GlintState;

import java.util.function.Supplier;

import static net.tunamods.customglint.CustomGlintMod.MOD_ID;

/**
 * API-jar item data component for glint state. {@link #GLINT} ({@code customglint:glint}) carries a
 * {@link GlintState} (the glint {@link CustomGlint.Data} plus the glow fields): the modern,
 * codec-typed replacement for stuffing a {@code CompoundTag} into vanilla's {@code CUSTOM_DATA}.
 *
 * <p>{@link #ENTITY_GLINT} is the entity counterpart: a synced {@link AttachmentType}. NeoForge persists it
 * server-side, auto-syncs it to tracking clients on every write (so there is no manual sync packet), and
 * copies it across player respawn.
 *
 * <p>{@link CustomGlintApiMod} calls {@link #register}. The static-imported {@code MOD_ID} names the full
 * mod's class, which the api jar does not ship: it is a compile-time String constant, so javac folds the
 * literal in and no runtime reference to {@code CustomGlintMod} survives.
 */
public final class CustomGlintComponents {
    private CustomGlintComponents() {}

    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MOD_ID);

    public static final Supplier<DataComponentType<GlintState>> GLINT =
            DATA_COMPONENTS.registerComponentType("glint", b -> b
                    .persistent(GlintState.CODEC)
                    .networkSynchronized(GlintState.STREAM_CODEC));

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MOD_ID);

    public static final Supplier<AttachmentType<GlintState>> ENTITY_GLINT = ATTACHMENT_TYPES.register(
            "entity_glint", () -> AttachmentType.builder(() -> GlintState.EMPTY)
                    .serialize(GlintState.CODEC)
                    .sync(GlintState.STREAM_CODEC)
                    .copyOnDeath()
                    .build());

    public static void register(IEventBus modEventBus) {
        DATA_COMPONENTS.register(modEventBus);
        ATTACHMENT_TYPES.register(modEventBus);
    }
}
