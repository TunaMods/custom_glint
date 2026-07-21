package net.tunamods.customglint.module.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** Recycled Glint Trim dust. Smelt an unwanted trim to get it; craft 4 + 2 glowstone dust into a fresh random
 *  trim. See {@link net.tunamods.customglint.module.recipe.TrimPowderRecipe}. */
public class TrimPowderItem extends Item {
    public TrimPowderItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("Craft 4 + 2 Glowstone Dust for a random Glint Trim").withStyle(ChatFormatting.GRAY));
    }
}
