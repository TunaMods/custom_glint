package net.tunamods.customglint.common.mixin;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the {@code textureState} shard off {@code RenderType$CompositeState}. See
 * {@link CompositeRenderTypeAccessor}.
 */
@Mixin(RenderType.CompositeState.class)
public interface CompositeStateAccessor {
    @Accessor("textureState")
    RenderStateShard.EmptyTextureStateShard customglint$textureState();
}
