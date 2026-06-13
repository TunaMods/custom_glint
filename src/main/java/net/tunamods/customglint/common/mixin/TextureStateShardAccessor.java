package net.tunamods.customglint.common.mixin;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;

/**
 * Invokes the protected {@code cutoutTexture()} on {@code RenderStateShard$TextureStateShard} to get
 * the bound texture. See {@link CompositeRenderTypeAccessor}.
 */
@Mixin(RenderStateShard.TextureStateShard.class)
public interface TextureStateShardAccessor {
    @Invoker("cutoutTexture")
    Optional<ResourceLocation> customglint$cutoutTexture();
}
