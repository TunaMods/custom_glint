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
 * API-jar registries for glint state, both carrying a {@link GlintState}:
 * <ul>
 *   <li>{@link #GLINT} — a typed item data component ({@code customglint:glint}), the modern
 *       replacement for stuffing a CompoundTag into vanilla's {@code CUSTOM_DATA}.</li>
 *   <li>{@link #ENTITY_GLINT} — a synced entity {@link AttachmentType}: NeoForge auto-syncs it to
 *       tracking clients on every write, persists it server-side, and copies it across player respawn.</li>
 * </ul>
 * Lives in the api jar so embedders that bundle only the api get both with no wiring; {@link CustomGlintApiMod}
 * calls {@link #register}.
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
                    .serialize(GlintState.MAP_CODEC)
                    .sync(GlintState.STREAM_CODEC)
                    .copyOnDeath()
                    .build());

    public static void register(IEventBus modEventBus) {
        DATA_COMPONENTS.register(modEventBus);
        ATTACHMENT_TYPES.register(modEventBus);
    }
}
