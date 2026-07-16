package net.tunamods.customglint.common.mixin;

import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.tunamods.customglint.common.CustomGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Glints the crafting result in the slot, before it is taken. ItemCraftedEvent, which CustomGlintApiMod
 * uses to apply CRAFT_GLINTS, only fires on take. slotChangedCraftingGrid builds the preview instead, and
 * is static and shared by CraftingMenu (3x3) and InventoryMenu (2x2), so one hook covers both grids.
 * Modded stations that assemble their own result bypass it and still glint on take only.
 *
 * Server-safe: the vanilla method early-returns on isClientSide, so nothing here runs on the client.
 *
 * TRIED: @ModifyArg on ResultContainer.setItem alone looked wrong at first glance -- the method goes on to
 * call setRemoteSlot and send ClientboundContainerSetSlotPacket with its own local, which an arg-only
 * change would not touch. It works because all three read the SAME ItemStack instance (local 6), so
 * mutating the stack in place inside the ModifyArg reaches the container, the remote slot, and the packet.
 * Do not "fix" this by reassigning instead of mutating.
 */
@Mixin(CraftingMenu.class)
public class CraftingMenuMixin {

    /** SRG target: obfuscated environments. */
    @ModifyArg(
        method = "m_150546_",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/ResultContainer;m_6836_(ILnet/minecraft/world/item/ItemStack;)V"),
        index = 1, require = 0
    )
    private static ItemStack cg_glintCraftPreview_srg(ItemStack result) {
        return cg_glintCraftPreview(result);
    }

    /** Named target: dev/deobf environments. */
    @ModifyArg(
        method = "slotChangedCraftingGrid(Lnet/minecraft/world/inventory/AbstractContainerMenu;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/inventory/CraftingContainer;Lnet/minecraft/world/inventory/ResultContainer;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/ResultContainer;setItem(ILnet/minecraft/world/item/ItemStack;)V"),
        index = 1, require = 0, remap = false
    )
    private static ItemStack cg_glintCraftPreview_named(ItemStack result) {
        return cg_glintCraftPreview(result);
    }

    /** Shared logic: stamp the registered craft glint onto the assembled result. The recipe assembles a
     *  fresh stack per grid change, so mutating it cannot poison the recipe's own result template. */
    private static ItemStack cg_glintCraftPreview(ItemStack result) {
        // No recipe match hands us the shared ItemStack.EMPTY singleton -- writing NBT to that would
        // corrupt every empty stack in the game.
        if (result.isEmpty()) return result;
        CustomGlint.applyCraftGlint(result);
        return result;
    }
}
