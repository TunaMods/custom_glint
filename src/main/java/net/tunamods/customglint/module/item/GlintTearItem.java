package net.tunamods.customglint.module.item;

import net.tunamods.customglint.common.CustomGlint;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

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
}
