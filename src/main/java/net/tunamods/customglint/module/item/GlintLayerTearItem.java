package net.tunamods.customglint.module.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.tunamods.customglint.common.CustomGlint;

public class GlintLayerTearItem extends Item {

    public GlintLayerTearItem(Properties properties) {
        super(properties);
    }

    @Override
    public ItemStack getDefaultInstance() {
        ItemStack stack = new ItemStack(this);
        CustomGlint.write(stack, CustomGlint.SOLID,
                new int[]{ CustomGlint.YELLOW, CustomGlint.BLACK }, 1.0f, true, 1.0f, false);
        return stack;
    }

    @Override
    public void appendHoverText(ItemStack pStack, Item.TooltipContext pContext, List<Component> pTooltipComponents, TooltipFlag pIsAdvanced) {
        pTooltipComponents.add(Component.literal("Craft with two Glint Trims to merge their").withStyle(ChatFormatting.GRAY));
        pTooltipComponents.add(Component.literal("layer arrays into one multi-layer trim").withStyle(ChatFormatting.GRAY));
    }
}
