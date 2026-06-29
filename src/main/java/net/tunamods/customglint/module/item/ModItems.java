package net.tunamods.customglint.module.item;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.module.block.ModBlocks;

/** All item registrations for the full standalone mod. The item holders are referenced across the module
 *  (recipes, loot, GUI, command), so they live here rather than inline in {@link CustomGlintMod}. */
public final class ModItems {
    private ModItems() {}

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, CustomGlintMod.MOD_ID);

    public static final RegistryObject<GlintWandItem> GLINT_WAND = ITEMS.register("glint_wand",
            () -> new GlintWandItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<GlintTrimItem> GLINT_TRIM = ITEMS.register("glint_trim",
            () -> new GlintTrimItem(new Item.Properties().stacksTo(16)));

    public static final RegistryObject<GlowTrimItem> GLOW_TRIM = ITEMS.register("glow_trim",
            () -> new GlowTrimItem(new Item.Properties().stacksTo(64)));

    public static final RegistryObject<GlintTearItem> GLINT_TEAR_SIMULTANEOUS = ITEMS.register("glint_tear_simultaneous",
            () -> new GlintTearItem(new Item.Properties().stacksTo(64), true));

    public static final RegistryObject<GlintTearItem> GLINT_TEAR_SEQUENTIAL = ITEMS.register("glint_tear_sequential",
            () -> new GlintTearItem(new Item.Properties().stacksTo(64), false));

    public static final RegistryObject<GlintLayerTearItem> GLINT_LAYER_TEAR = ITEMS.register("glint_layer_tear",
            () -> new GlintLayerTearItem(new Item.Properties().stacksTo(64)));

    public static final RegistryObject<GlintBlackTearItem> GLINT_BLACK_TEAR = ITEMS.register("glint_black_tear",
            () -> new GlintBlackTearItem(new Item.Properties().stacksTo(64)));

    public static final RegistryObject<RainbowDyeItem> RAINBOW_DYE = ITEMS.register("rainbow_dye",
            () -> new RainbowDyeItem(new Item.Properties()));

    /** BlockItem for the Glint Table. The block/BE/menu registries live in module.block / module.menu;
     *  the block holder resolves at item-registration time (blocks register first). */
    public static final RegistryObject<BlockItem> GLINT_TABLE_ITEM = ITEMS.register("glint_table",
            () -> new BlockItem(ModBlocks.GLINT_TABLE_BLOCK.get(), new Item.Properties()));

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
