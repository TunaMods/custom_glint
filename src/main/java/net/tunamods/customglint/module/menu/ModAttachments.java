package net.tunamods.customglint.module.menu;

import com.mojang.serialization.Codec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.tunamods.customglint.CustomGlintMod;

import java.util.ArrayList;
import java.util.List;

public final class ModAttachments {
    private ModAttachments() {}

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, CustomGlintMod.MOD_ID);

    /** Per-player set of Glint Table design names the player has "stored" (permanently un-ghosted). */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<List<String>>> STORED_DESIGNS =
            ATTACHMENT_TYPES.register("stored_designs", () ->
                    AttachmentType.<List<String>>builder(() -> new ArrayList<>())
                            .serialize(Codec.STRING.listOf())
                            .copyOnDeath()
                            .build());

    /** Per-player library of finished ("printed") painted trims shown in the Glint Table's right panel. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<List<ItemStack>>> PRINTED_TRIMS =
            ATTACHMENT_TYPES.register("printed_trims", () ->
                    AttachmentType.<List<ItemStack>>builder(() -> new ArrayList<>())
                            .serialize(ItemStack.CODEC.listOf())
                            .copyOnDeath()
                            .build());

    /** Per-player Glint Table slot contents (trims, dyes, modifiers); every table the player opens shares it. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ItemContainerContents>> GLINT_TABLE_CONTENTS =
            ATTACHMENT_TYPES.register("glint_table_contents", () ->
                    AttachmentType.<ItemContainerContents>builder(() -> ItemContainerContents.EMPTY)
                            .serialize(ItemContainerContents.CODEC)
                            .copyOnDeath()
                            .build());

    public static void register(IEventBus bus) {
        ATTACHMENT_TYPES.register(bus);
    }
}
