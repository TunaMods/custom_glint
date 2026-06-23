package net.tunamods.customglint.common.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.SpecialModelWrapper;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks special-renderer items (shield, trident) with a custom glint as animated, so their GUI/inventory
 * icon re-renders each frame and the glint scrolls.
 *
 * <p>{@code SpecialModelWrapper.update} only calls {@code ItemStackRenderState.setAnimated()} when the item
 * is enchanted ({@code ItemStack.hasFoil()}); a glinted-but-unenchanted item is therefore cached as a single
 * static frame in the GUI item atlas, so its glint never moves. Unlike quad items, which route through
 * {@code CuboidItemModelWrapperMixin} forcing the foil on, we must not force the foil here: the trident's
 * foil draws through a 4-arg {@code getFoilBuffer} and the shield's through a separate {@code entityGlint}
 * node, so forcing it would draw vanilla's glint on top of ours (see {@code SubmitNodeStorageMixin}, which
 * already draws our glint geometry directly). Calling {@code setAnimated()} alone gives the animation without
 * the doubled foil. In the world the held/dropped item re-renders every frame anyway, so this only affects
 * the cached GUI icon.
 */
@Mixin(SpecialModelWrapper.class)
public class SpecialModelWrapperMixin {

    @Inject(method = "update", at = @At("RETURN"), require = 0)
    private void cg_animateGlint(ItemStackRenderState output, ItemStack item, ItemModelResolver resolver,
            ItemDisplayContext displayContext, ClientLevel level, ItemOwner owner, int seed, CallbackInfo ci) {
        if (!item.isEmpty() && CustomGlint.read(item) != null) {
            output.setAnimated();
        }
    }
}
