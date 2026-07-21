package net.tunamods.customglint.module.compat.iceandfire;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Standalone-only Ice & Fire compat. The client-only side class ({@link IceAndFireClientCompat})
 * clears the mount-armor cache on world unload and is gated on {@code FMLEnvironment.dist}; event
 * listeners for the mount-armor sync (hippogryph/hippocampus) are registered on both sides because
 * the server initiates the sync packet when a player begins tracking the mount.
 *
 * Split was required for dedicated-server compatibility: {@link IceAndFireClientCompat} touches
 * {@code CustomGlintRenderer}, which extends {@code RenderStateShard} (client-only) and so cannot
 * load on a dedicated server. The {@code FMLEnvironment.dist == Dist.CLIENT} guard keeps the client
 * side-class off the server's class path.
 */
public final class IceAndFireCompat {
    private IceAndFireCompat() {}

    static final String HIPPOGRYPH_CLASS  = "com.iafenvoy.iceandfire.entity.HippogryphEntity";
    static final String HIPPOCAMPUS_CLASS = "com.iafenvoy.iceandfire.entity.HippocampusEntity";

    public static void register() {
        if (!ModList.get().isLoaded("iceandfire")) return;

        // Client-only mount-armor cache cleanup wiring.
        if (FMLEnvironment.dist == Dist.CLIENT) IceAndFireClientCompat.run();

        // Mount armor sync (hippogryph / hippocampus): needed on the server to push armor stacks
        // to tracking clients via MountArmorSync. onEntityLeave's `isClientSide` guard makes it a
        // no-op on the server, so a single addListener works for both sides.
        NeoForge.EVENT_BUS.addListener(IceAndFireCompat::onStartTracking);
        NeoForge.EVENT_BUS.addListener(IceAndFireCompat::onEntityLeave);
    }

    /** When a player begins tracking a hippogryph/hippocampus, send them the current armor stack. */
    private static void onStartTracking(PlayerEvent.StartTracking event) {
        Entity target = event.getTarget();
        String cls = target.getClass().getName();
        String field;
        if (cls.equals(HIPPOGRYPH_CLASS))      field = "hippogryphInventory";
        else if (cls.equals(HIPPOCAMPUS_CLASS)) field = "inventory";
        else return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        ItemStack stack = MountArmorSync.readArmorStack(target, field);
        MountArmorSync.sendTo(sp, target, stack);
    }

    /** Drop cache entries when the entity unloads or dies: client render cache and the server-side
     *  change-detection map (the latter would otherwise grow for the life of the server). */
    private static void onEntityLeave(EntityLeaveLevelEvent event) {
        Entity e = event.getEntity();
        String cls = e.getClass().getName();
        if (!cls.equals(HIPPOGRYPH_CLASS) && !cls.equals(HIPPOCAMPUS_CLASS)) return;
        if (event.getLevel().isClientSide) {
            MountArmorCache.remove(e.getId());
        } else {
            MountArmorSync.forget(e.getId());
        }
    }
}
