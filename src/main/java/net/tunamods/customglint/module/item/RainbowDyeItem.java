package net.tunamods.customglint.module.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.tunamods.customglint.common.CustomGlint;

import java.util.function.Consumer;

/**
 * A "rainbow" dye: a white-dye sprite carrying a built-in rainbow glint. Used at the Glint Table to set a
 * color shard to any custom hex (the shard's dye bar slot opens a hex entry); one rainbow dye is consumed
 * per printed custom-hex color.
 */
public class RainbowDyeItem extends Item {

    /** The built-in rainbow glint colours (ROYGBIV) shown on the item icon. */
    private static final int[] RAINBOW = {
        0xFFFF0000, 0xFFFF8000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFF8800FF
    };

    public RainbowDyeItem(Properties properties) {
        super(properties);
    }

    @Override
    public ItemStack getDefaultInstance() {
        ItemStack stack = new ItemStack(this);
        CustomGlint.write(stack, CustomGlint.SOLID, RAINBOW, 1.0f, true, 1.0f, false);
        return stack;
    }

    @Override
    public void appendHoverText(ItemStack pStack, Item.TooltipContext pContext, TooltipDisplay pDisplay, Consumer<Component> pTooltipComponents, TooltipFlag pIsAdvanced) {
        pTooltipComponents.accept(Component.literal("At the Glint Table, sets a color shard to a custom hex color").withStyle(ChatFormatting.GRAY));
    }
}
