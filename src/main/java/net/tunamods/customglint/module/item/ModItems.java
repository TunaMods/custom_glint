package net.tunamods.customglint.module.item;

import net.minecraft.world.item.BlockItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.module.block.ModBlocks;

public final class ModItems {
    private ModItems() {}

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(CustomGlintMod.MOD_ID);

    public static final DeferredItem<GlintWandItem> GLINT_WAND = ITEMS.registerItem("glint_wand",
            props -> new GlintWandItem(props.stacksTo(1)));

    public static final DeferredItem<GlintTrimItem> GLINT_TRIM = ITEMS.registerItem("glint_trim",
            props -> new GlintTrimItem(props.stacksTo(16)));

    public static final DeferredItem<GlowTrimItem> GLOW_TRIM = ITEMS.registerItem("glow_trim",
            props -> new GlowTrimItem(props.stacksTo(16)));

    public static final DeferredItem<GlintTearItem> GLINT_TEAR_SIMULTANEOUS = ITEMS.registerItem("glint_tear_simultaneous",
            props -> new GlintTearItem(props.stacksTo(64), true));

    public static final DeferredItem<GlintTearItem> GLINT_TEAR_SEQUENTIAL = ITEMS.registerItem("glint_tear_sequential",
            props -> new GlintTearItem(props.stacksTo(64), false));

    public static final DeferredItem<GlintLayerTearItem> GLINT_LAYER_TEAR = ITEMS.registerItem("glint_layer_tear",
            props -> new GlintLayerTearItem(props.stacksTo(64)));

    public static final DeferredItem<GlintBlackTearItem> GLINT_BLACK_TEAR = ITEMS.registerItem("glint_black_tear",
            props -> new GlintBlackTearItem(props.stacksTo(64)));

    public static final DeferredItem<RainbowDyeItem> RAINBOW_DYE = ITEMS.registerItem("rainbow_dye",
            props -> new RainbowDyeItem(props.stacksTo(64)));

    /** BlockItem for the Glint Table, registered last so its block holder already exists. */
    public static final DeferredItem<BlockItem> GLINT_TABLE_ITEM =
            ITEMS.registerSimpleBlockItem(ModBlocks.GLINT_TABLE_BLOCK);

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
