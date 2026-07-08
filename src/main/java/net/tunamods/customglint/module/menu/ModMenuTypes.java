package net.tunamods.customglint.module.menu;

import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.tunamods.customglint.CustomGlintMod;

public final class ModMenuTypes {
    private ModMenuTypes() {}

    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, CustomGlintMod.MOD_ID);

    public static final RegistryObject<MenuType<GlintTableMenu>> GLINT_TABLE_MENU =
            MENU_TYPES.register("glint_table", () -> IForgeMenuType.create(GlintTableMenu::new));

    public static final RegistryObject<MenuType<GlintBagMenu>> GLINT_BAG_MENU =
            MENU_TYPES.register("glint_bag", () -> IForgeMenuType.create(GlintBagMenu::new));

    public static void register(IEventBus bus) {
        MENU_TYPES.register(bus);
    }
}
