package net.tunamods.customglint.module.recipe;

import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.ModItems;

/**
 * Recycle recipe: 4 Trim Powder + 2 Glowstone Dust → one fresh, randomly designed Glint Trim. The result is
 * rolled in {@link #assemble} (a random design, always blank so identical designs stack), so the output
 * preview changes each time the grid updates — that's intentional, it signals the trim is random.
 */
public class TrimPowderRecipe extends CustomRecipe {

    private static final TrimPowderRecipe INSTANCE = new TrimPowderRecipe();
    public static final MapCodec<TrimPowderRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final StreamCodec<RegistryFriendlyByteBuf, TrimPowderRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);
    public static final RecipeSerializer<TrimPowderRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    private static final int POWDER_COUNT = 4;
    private static final int GLOWSTONE_COUNT = 2;

    public TrimPowderRecipe() {}

    @Override
    public boolean matches(CraftingInput inv, Level level) {
        int powder = 0, glowstone = 0, filled = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            filled++;
            if (s.getItem() == ModItems.TRIM_POWDER.get()) powder++;
            else if (s.is(Items.GLOWSTONE_DUST)) glowstone++;
            else return false;
        }
        return filled == POWDER_COUNT + GLOWSTONE_COUNT && powder == POWDER_COUNT && glowstone == GLOWSTONE_COUNT;
    }

    @Override
    public ItemStack assemble(CraftingInput inv) {
        RandomSource random = RandomSource.create();
        ItemStack trim = new ItemStack(ModItems.GLINT_TRIM.get());
        // Mirror the loot roll: a random design, always blank (no colors) so identical designs stack.
        // designFromName handles the vanilla/chromatic sentinels the same way the loot modifier does.
        String name = GlintTrimItem.PATTERNS.get(random.nextInt(GlintTrimItem.PATTERNS.size()));
        GlintTrimItem.setPattern(trim, CustomGlint.designFromName(name));
        return trim;
    }

    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public RecipeSerializer<? extends CustomRecipe> getSerializer() {
        return SERIALIZER;
    }
}
