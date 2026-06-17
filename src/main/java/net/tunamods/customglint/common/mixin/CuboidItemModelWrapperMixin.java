package net.tunamods.customglint.common.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Forces the foil path on for items that carry a custom glint but are not enchanted.
 *
 * <p>In 26.1 {@code ItemFeatureRenderer.renderItem} only calls {@code getFoilBuffer} (where the custom
 * glint is injected — see {@code ItemRendererMixin}) when the layer's {@code FoilType != NONE}.
 * {@code CuboidItemModelWrapper.update} derives the foil type from {@code ItemStack.hasFoil()} during
 * render-state extraction, so a glinted-but-unenchanted item would never reach the foil draw. Making
 * {@code hasFoil()} read true when our glint is present routes the item through {@code FoilType.STANDARD}
 * and, as a bonus, calls {@code output.setAnimated()} so the item re-renders each frame for the scroll
 * animation. The glint buffer replaces vanilla's sheet, so no vanilla shimmer leaks through.
 */
@Mixin(CuboidItemModelWrapper.class)
public class CuboidItemModelWrapperMixin {

    @ModifyExpressionValue(
        method = "update",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;hasFoil()Z"),
        require = 0
    )
    private boolean cg_forceFoilForGlint(boolean original, @Local(argsOnly = true) ItemStack item) {
        return original || CustomGlint.read(item) != null;
    }
}
