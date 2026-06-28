package net.tunamods.customglint.module.menu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.tunamods.customglint.CustomGlintMod;

public final class ModMenuTypes {
    private ModMenuTypes() {}

    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, CustomGlintMod.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<GlintTableMenu>> GLINT_TABLE_MENU =
            MENU_TYPES.register("glint_table", () -> IMenuTypeExtension.create(GlintTableMenu::new));

    public static void register(IEventBus bus) {
        MENU_TYPES.register(bus);
    }
}
