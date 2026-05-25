package net.tunamods.customglint.common.client;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Client-side init for entity glints. Installs an instance-resolver hook on
 * {@link EntityGlintRender} that reads from the per-instance sync cache, and clears the cache on
 * logout. Invoked from {@code CustomGlintApiMod} on the client dist.
 */
public final class EntityGlintClientInit {
    private EntityGlintClientInit() {}

    public static void run() {
        EntityGlintRender.instanceResolver = entity -> {
            EntityGlintCache.Entry e = EntityGlintCache.get(entity.getUUID());
            if (e == null) return null;
            return new EntityGlintRender.Resolution(e.data, e.glowing, e.glowColors);
        };
        MinecraftForge.EVENT_BUS.register(EntityGlintClientInit.class);
    }

    @SubscribeEvent
    public static void onLeaveLevel(ClientPlayerNetworkEvent.LoggingOut event) {
        EntityGlintCache.clear();
    }
}
