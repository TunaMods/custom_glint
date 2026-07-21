package net.tunamods.customglint.module.item;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.common.CustomGlint;

public final class ModCreativeTabs {
    private ModCreativeTabs() {}

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CustomGlintMod.MOD_ID);

    public static final RegistryObject<CreativeModeTab> GLINT_TAB = CREATIVE_MODE_TABS.register("glint_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.customglint.glint_tab"))
            .icon(() -> {
                ItemStack icon = new ItemStack(Items.WOODEN_AXE);
                CustomGlint.write(icon,
                        CustomGlint.WAVE,
                        new int[]{0xFF8844EE, 0xFF00BBBB, 0xFFFFAA00},
                        0.5f, true, 1.0f, true);
                return icon;
            })
            .displayItems((parameters, output) -> {
                output.accept(ModItems.GLINT_WAND.get());
                output.accept(ModItems.GLINT_BAG.get().getDefaultInstance());
                output.accept(ModItems.GLINT_TEAR_SIMULTANEOUS.get().getDefaultInstance());
                output.accept(ModItems.GLINT_TEAR_SEQUENTIAL.get().getDefaultInstance());
                output.accept(ModItems.GLINT_LAYER_TEAR.get().getDefaultInstance());
                output.accept(ModItems.GLINT_BLACK_TEAR.get().getDefaultInstance());
                output.accept(ModItems.RAINBOW_DYE.get().getDefaultInstance());
                output.accept(ModItems.TRIM_POWDER.get());
                output.accept(new ItemStack(ModItems.GLINT_TABLE_ITEM.get()));
                output.accept(new ItemStack(ModItems.GLOW_TRIM.get()));
                for (String pattern : GlintTrimItem.PATTERNS) {
                    ItemStack trim = new ItemStack(ModItems.GLINT_TRIM.get());
                    GlintTrimItem.setPattern(trim, CustomGlint.designFromName(pattern));
                    output.accept(trim);
                }
            })
            .build());

    public static void register(IEventBus bus) {
        CREATIVE_MODE_TABS.register(bus);
    }
}
