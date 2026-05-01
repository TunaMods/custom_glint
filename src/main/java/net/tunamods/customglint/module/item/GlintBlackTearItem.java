// MIT License — Copyright (c) 2026 Likely Tuna | TunaMods — see LICENSE.txt
package net.tunamods.customglint.module.item;

import net.tunamods.customglint.common.CustomGlint;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class GlintBlackTearItem extends Item {

    public GlintBlackTearItem(Properties properties) {
        super(properties);
    }

    @Override
    public ItemStack getDefaultInstance() {
        ItemStack stack = new ItemStack(this);
        CustomGlint.write(stack, CustomGlint.SOLID,
                new int[]{ CustomGlint.BLACK, CustomGlint.WHITE }, 1.0f, true, 1.0f, false);
        return stack;
    }
}
