package net.tunamods.customglint.module.compat.iceandfire.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.module.compat.iceandfire.MountArmorSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server-side sync trigger. IaF stores hippogryph armor in a SimpleContainer that doesn't sync
 * to clients - refreshInventory() is the convergence point called from the entity's
 * containerChanged listener (and inventory load), so it's where we broadcast the current armor
 * stack via our own packet. refreshInventory itself early-returns on client, so we filter by
 * side here too.
 */
@Pseudo
@Mixin(targets = "com.github.alexthe666.iceandfire.entity.EntityHippogryph", remap = false)
public class EntityHippogryphArmorSyncMixin {

    @Inject(method = "refreshInventory", at = @At("RETURN"), require = 0)
    private void cg_sync(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.level().isClientSide) return;
        ItemStack stack = MountArmorSync.readArmorStack(self, "hippogryphInventory");
        MountArmorSync.broadcast(self, stack);
    }
}
