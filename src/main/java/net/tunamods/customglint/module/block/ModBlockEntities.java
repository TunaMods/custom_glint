package net.tunamods.customglint.module.block;

import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.tunamods.customglint.CustomGlintMod;

public final class ModBlockEntities {
    private ModBlockEntities() {}

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, CustomGlintMod.MOD_ID);

    public static final RegistryObject<BlockEntityType<GlintTableBlockEntity>> GLINT_TABLE_BE =
            BLOCK_ENTITY_TYPES.register("glint_table", () ->
                    BlockEntityType.Builder.of(GlintTableBlockEntity::new, ModBlocks.GLINT_TABLE_BLOCK.get()).build(null));

    public static void register(IEventBus bus) {
        BLOCK_ENTITY_TYPES.register(bus);
    }
}
