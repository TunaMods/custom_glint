package net.tunamods.customglint.module.recipe;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;

/** Crafting (shapeless): a GlintTrimItem (with pattern + ≥1 color) + 8 Glowstone Dust → same trim with glowing=true. */
public class GlintGlowTrimRecipe extends CustomRecipe {
    public static final SimpleCraftingRecipeSerializer<GlintGlowTrimRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(GlintGlowTrimRecipe::new);

    public GlintGlowTrimRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer pInv, Level pLevel) {
        ItemStack trim = ItemStack.EMPTY;
        int glowstone = 0;
        for (int i = 0; i < pInv.getContainerSize(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof GlintTrimItem && GlintTrimItem.getPattern(s) != null
                    && GlintTrimItem.getColors(s).length > 0) {
                if (!trim.isEmpty()) return false;
                trim = s;
            } else if (s.is(Items.GLOWSTONE_DUST)) {
                glowstone++;
            } else {
                return false;
            }
        }
        return !trim.isEmpty() && glowstone == 8;
    }

    @Override
    public ItemStack assemble(CraftingContainer pInv, RegistryAccess pRegistryAccess) {
        ItemStack trim = ItemStack.EMPTY;
        for (int i = 0; i < pInv.getContainerSize(); i++) {
            ItemStack s = pInv.getItem(i);
            if (s.getItem() instanceof GlintTrimItem) { trim = s; break; }
        }
        if (trim.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = trim.copy();
        result.setCount(1);
        GlintTrimItem.setGlowing(result, true);
        CustomGlint.setGlowing(result, true);
        return result;
    }

    @Override
    public boolean canCraftInDimensions(int pWidth, int pHeight) {
        return pWidth * pHeight >= 9;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }
}
