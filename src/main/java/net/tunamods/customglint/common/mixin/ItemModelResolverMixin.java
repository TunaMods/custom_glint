package net.tunamods.customglint.common.mixin;

import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CgGlintHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Attaches the top item's glint {@link CustomGlint.Data} to its {@link ItemStackRenderState} when the
 * render state is (re)built. {@code updateForTopItem} clears then repopulates the reused render state,
 * so setting the glint at RETURN (null when the item has none) also resets stale state from the
 * previous item drawn through the same scratch object.
 */
@Mixin(ItemModelResolver.class)
public class ItemModelResolverMixin {

    @Inject(method = "updateForTopItem", at = @At("RETURN"), require = 0)
    private void cg_attachGlint(ItemStackRenderState output, ItemStack item, ItemDisplayContext displayContext,
            Level level, ItemOwner owner, int seed, CallbackInfo ci) {
        CustomGlint.Data glint = item.isEmpty() ? null : CustomGlint.read(item);
        CgGlintHolder holder = (CgGlintHolder) (Object) output;
        holder.customglint$setGlint(glint);
        // Glow rides the carrier independently of the glint: a Glow-Trimmed item with no glint still
        // outlines. The deferred item draw reads these back off the ItemSubmit node to queue the ring.
        holder.customglint$setGlowing(!item.isEmpty() && CustomGlint.isGlowing(item));
        holder.customglint$setGlowColors(item.isEmpty() ? null : CustomGlint.getGlowColors(item));

        // The GUI renders items into an atlas cached by getModelIdentity() (see GuiItemAtlas) — the
        // glint is NOT part of vanilla's identity, so two items of the same base type + foil state
        // share one cached slot. Without this, giving a glinted item freezes the editor preview on
        // that config. Fold the glint config into the identity so each distinct look gets its own slot.
        if (!item.isEmpty() && (glint != null || CustomGlint.isGlowing(item))) {
            output.appendModelIdentityElement(cg_identity(item, glint));
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static String cg_identity(ItemStack item, CustomGlint.Data glint) {
        StringBuilder sb = new StringBuilder("customglint:");
        if (glint != null) {
            for (CustomGlint.Layer l : glint.layers()) {
                sb.append(l.design()).append('@').append(l.speed()).append(',')
                  .append(l.interpolate()).append(',').append(l.patternScale()).append(',')
                  .append(l.simultaneous()).append(Arrays.toString(l.colors())).append(';');
            }
        }
        sb.append("glow=").append(CustomGlint.isGlowing(item))
          .append(Arrays.toString(CustomGlint.getGlowColors(item)));
        return sb.toString();
    }
}
