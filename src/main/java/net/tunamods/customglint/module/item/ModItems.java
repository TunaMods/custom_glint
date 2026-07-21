package net.tunamods.customglint.module.item;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.module.block.ModBlocks;

/** All item registrations for the full standalone mod. The item holders are referenced across the module
 *  (recipes, loot, GUI, command), so they live here rather than inline in {@link CustomGlintMod}. */
public final class ModItems {
    private ModItems() {}

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(CustomGlintMod.MOD_ID);

    public static final DeferredItem<GlintWandItem> GLINT_WAND = ITEMS.register("glint_wand",
            () -> new GlintWandItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<GlintTrimItem> GLINT_TRIM = ITEMS.register("glint_trim",
            () -> new GlintTrimItem(new Item.Properties().stacksTo(16)));

    public static final DeferredItem<GlowTrimItem> GLOW_TRIM = ITEMS.register("glow_trim",
            () -> new GlowTrimItem(new Item.Properties().stacksTo(16)));

    public static final DeferredItem<GlintTearItem> GLINT_TEAR_SIMULTANEOUS = ITEMS.register("glint_tear_simultaneous",
            () -> new GlintTearItem(new Item.Properties().stacksTo(64), true));

    public static final DeferredItem<GlintTearItem> GLINT_TEAR_SEQUENTIAL = ITEMS.register("glint_tear_sequential",
            () -> new GlintTearItem(new Item.Properties().stacksTo(64), false));

    public static final DeferredItem<GlintLayerTearItem> GLINT_LAYER_TEAR = ITEMS.register("glint_layer_tear",
            () -> new GlintLayerTearItem(new Item.Properties().stacksTo(64)));

    public static final DeferredItem<GlintBlackTearItem> GLINT_BLACK_TEAR = ITEMS.register("glint_black_tear",
            () -> new GlintBlackTearItem(new Item.Properties().stacksTo(64)));

    public static final DeferredItem<RainbowDyeItem> RAINBOW_DYE = ITEMS.register("rainbow_dye",
            () -> new RainbowDyeItem(new Item.Properties()));

    public static final DeferredItem<GlintBagItem> GLINT_BAG = ITEMS.register("glint_bag",
            () -> new GlintBagItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<TrimPowderItem> TRIM_POWDER = ITEMS.register("trim_powder",
            () -> new TrimPowderItem(new Item.Properties()));

    /** BlockItem for the Glint Table. The block/BE/menu/attachment registries live in module.block /
     *  module.menu; the block holder resolves at item-registration time (blocks register first). */
    public static final DeferredItem<BlockItem> GLINT_TABLE_ITEM = ITEMS.register("glint_table",
            () -> new BlockItem(ModBlocks.GLINT_TABLE_BLOCK.get(), new Item.Properties()));

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
