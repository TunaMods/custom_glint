package net.tunamods.customglint.module.block;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.tunamods.customglint.CustomGlintMod;

public final class ModBlockEntities {
    private ModBlockEntities() {}

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CustomGlintMod.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GlintTableBlockEntity>> GLINT_TABLE_BE =
            BLOCK_ENTITY_TYPES.register("glint_table", () ->
                    BlockEntityType.Builder.of(GlintTableBlockEntity::new, ModBlocks.GLINT_TABLE_BLOCK.get()).build(null));

    public static void register(IEventBus bus) {
        BLOCK_ENTITY_TYPES.register(bus);
    }
}
