package net.tunamods.customglint.module.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.tunamods.customglint.CustomGlintMod;

public final class ModBlocks {
    private ModBlocks() {}

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, CustomGlintMod.MOD_ID);

    public static final RegistryObject<GlintTableBlock> GLINT_TABLE_BLOCK = BLOCKS.register("glint_table",
            () -> new GlintTableBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD)));

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
    }
}
