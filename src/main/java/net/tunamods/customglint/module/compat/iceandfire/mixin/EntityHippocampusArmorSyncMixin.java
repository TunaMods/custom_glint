package net.tunamods.customglint.module.compat.iceandfire.mixin;

import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.module.compat.iceandfire.MountArmorSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * See {@link EntityHippogryphArmorSyncMixin}. Hippocampus inventory field is `inventory`, and
 * IaF reacts to inventory changes by overriding ContainerListener.containerChanged (vanilla
 * m_5757_) directly rather than exposing a custom refreshInventory. Dual SRG/named injection
 * because containerChanged IS a vanilla method (remapped between obf production and deobf dev),
 * even though the class itself is remap=false.
 */
@Pseudo
@Mixin(targets = "com.iafenvoy.iceandfire.entity.HippocampusEntity", remap = false)
public class EntityHippocampusArmorSyncMixin {

    @Inject(method = "m_5757_(Lnet/minecraft/world/Container;)V", at = @At("RETURN"), require = 0)
    private void cg_sync_srg(Container container, CallbackInfo ci) {
        cg_doSync();
    }

    @Inject(method = "containerChanged(Lnet/minecraft/world/Container;)V", at = @At("RETURN"), require = 0)
    private void cg_sync_named(Container container, CallbackInfo ci) {
        cg_doSync();
    }

    private void cg_doSync() {
        Entity self = (Entity) (Object) this;
        if (self.level().isClientSide) return;
        ItemStack stack = MountArmorSync.readArmorStack(self, "inventory");
        MountArmorSync.broadcast(self, stack);
    }
}
