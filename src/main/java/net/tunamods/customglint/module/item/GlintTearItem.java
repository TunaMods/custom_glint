package net.tunamods.customglint.module.item;

import net.tunamods.customglint.common.CustomGlint;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

public class GlintTearItem extends Item {
    private final boolean simultaneous;

    public GlintTearItem(Properties properties, boolean simultaneous) {
        super(properties);
        this.simultaneous = simultaneous;
    }

    @Override
    public ItemStack getDefaultInstance() {
        ItemStack stack = new ItemStack(this);
        CustomGlint.write(stack,
            CustomGlint.res("textures/glint/wave.png"),
            new int[]{ 0xFFFF0000, 0xFF00FF00, 0xFF0000FF },
            1.0f, true, 1.0f, simultaneous);
        return stack;
    }

    @Override
    public void appendHoverText(ItemStack pStack, Item.TooltipContext pContext, TooltipDisplay pDisplay, Consumer<Component> pTooltipComponents, TooltipFlag pIsAdvanced) {
        String mode = simultaneous ? "Simultaneous" : "Sequential";
        String effect = simultaneous ? " mode, all colors shown at once" : " mode, colors cycle one at a time";
        pTooltipComponents.accept(Component.literal("Craft with any glinted item to set all layers to").withStyle(ChatFormatting.GRAY));
        pTooltipComponents.accept(Component.literal(mode).withStyle(ChatFormatting.AQUA)
                .append(Component.literal(effect).withStyle(ChatFormatting.GRAY)));
    }
}
