package net.tunamods.customglint.module.item;

import net.tunamods.customglint.common.CustomGlint;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

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
            new ResourceLocation("customglint", "textures/glint/wave.png"),
            new int[]{ 0xFFFF0000, 0xFF00FF00, 0xFF0000FF },
            1.0f, true, 1.0f, simultaneous);
        return stack;
    }

    @Override
    public void appendHoverText(ItemStack pStack, @Nullable Level pLevel, List<Component> pTooltipComponents, TooltipFlag pIsAdvanced) {
        if (simultaneous) {
            pTooltipComponents.add(Component.literal("Craft with any glinted item to set all layers to").withStyle(ChatFormatting.GRAY));
            pTooltipComponents.add(Component.literal("Simultaneous").withStyle(ChatFormatting.AQUA).append(Component.literal(" mode — all colors shown at once").withStyle(ChatFormatting.GRAY)));
        } else {
            pTooltipComponents.add(Component.literal("Craft with any glinted item to set all layers to").withStyle(ChatFormatting.GRAY));
            pTooltipComponents.add(Component.literal("Sequential").withStyle(ChatFormatting.AQUA).append(Component.literal(" mode — colors cycle one at a time").withStyle(ChatFormatting.GRAY)));
        }
    }
}
