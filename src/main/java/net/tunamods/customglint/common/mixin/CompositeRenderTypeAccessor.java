package net.tunamods.customglint.common.mixin;

import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the private {@code state} field off {@code RenderType$CompositeRenderType}. Targeted by
 * string because the class is package-private and can't be referenced by literal. Used by
 * {@code GlowOutlineRenderer.reflectRenderTypeTexture} to recover the texture an item binds, so a
 * custom-renderer outline can trace the item's own silhouette instead of filling its whole model
 * geometry with white.png.
 */
@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
public interface CompositeRenderTypeAccessor {
    @Accessor("state")
    RenderType.CompositeState customglint$state();
}
