package net.tunamods.customglint.module.compat.iceandfire.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.module.compat.iceandfire.MountArmorSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side sync trigger. IaF stores hippogryph armor in a {@code SimpleContainer}
 * ({@code hippogryphInventory}) that doesn't sync to clients. The 1.20.1 build hooked
 * {@code refreshInventory()} (called from the container-changed listener) to broadcast on change,
 * but Community Edition dropped that method and exposes no {@code containerChanged} hook on
 * HippogryphEntity, so we sync from {@code tick()} RETURN with change detection instead: read the
 * armor at slot 2 and broadcast only when it differs from the last value sent for this entity id.
 * New trackers are covered separately by {@link net.tunamods.customglint.module.compat.iceandfire.IceAndFireCompat}
 * onStartTracking, so a no-change tick is just a cheap early-return.
 */
@Pseudo
@Mixin(targets = "com.iafenvoy.iceandfire.entity.HippogryphEntity", remap = false)
public class EntityHippogryphArmorSyncMixin {

    private static final Map<Integer, ItemStack> CG_LAST = new ConcurrentHashMap<>();

    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private void cg_sync(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.level().isClientSide) return;
        ItemStack stack = MountArmorSync.readArmorStack(self, "hippogryphInventory");
        ItemStack last = CG_LAST.get(self.getId());
        if (last != null && ItemStack.matches(last, stack)) return;
        CG_LAST.put(self.getId(), stack.copy());
        MountArmorSync.broadcast(self, stack);
    }
}
