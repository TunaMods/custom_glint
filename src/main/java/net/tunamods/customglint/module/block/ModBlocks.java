package net.tunamods.customglint.module.block;

import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.tunamods.customglint.CustomGlintMod;

public final class ModBlocks {
    private ModBlocks() {}

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(CustomGlintMod.MOD_ID);

    public static final DeferredBlock<GlintTableBlock> GLINT_TABLE_BLOCK = BLOCKS.registerBlock("glint_table",
            GlintTableBlock::new,
            props -> props.mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD));

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
    }
}
