package net.tunamods.customglint.module.compat.iceandfire;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * Standalone-only Ice & Fire compat. Renderer-touching configuration (BEWLR outline offsets /
 * textures) is registered from a client-only side class via {@code DistExecutor}; event listeners
 * for the mount-armor sync (hippogryph/hippocampus) are registered unconditionally because the
 * server side initiates the sync packet when a player begins tracking the mount.
 *
 * Split was required for dedicated-server compatibility: the renderer maps live on
 * {@code CustomGlintRenderer}, which extends {@code RenderStateShard} (client-only) and so
 * cannot load on a dedicated server. {@code DistExecutor.safeRunWhenOn} only invokes the
 * supplier on the matching dist, so the client side-class is never resolved on the server.
 */
public final class IceAndFireCompat {
    private IceAndFireCompat() {}

    static final String HIPPOGRYPH_CLASS  = "com.github.alexthe666.iceandfire.entity.EntityHippogryph";
    static final String HIPPOCAMPUS_CLASS = "com.github.alexthe666.iceandfire.entity.EntityHippocampus";

    public static void register() {
        // Renderer overrides — client-only.
        if (FMLEnvironment.dist == Dist.CLIENT) IceAndFireClientCompat.run();

        // Mount armor sync (hippogryph / hippocampus) — needed on the server to push armor stacks
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

    /** Drop client-side cache entries when the entity unloads or dies. No-op on server. */
    private static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide) return;
        Entity e = event.getEntity();
        String cls = e.getClass().getName();
        if (cls.equals(HIPPOGRYPH_CLASS) || cls.equals(HIPPOCAMPUS_CLASS)) {
            MountArmorCache.remove(e.getId());
        }
    }
}
