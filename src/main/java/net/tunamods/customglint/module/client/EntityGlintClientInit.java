package net.tunamods.customglint.module.client;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.tunamods.customglint.common.client.EntityGlintRender;

/**
 * Client-side init for entity glints in the standalone jar. Installs an instance-resolver hook
 * on {@link EntityGlintRender} that reads from the per-instance sync cache; the API jar's
 * default resolver returns null, so api-only embedders still get type-registry glints but no
 * per-instance ones (which would require their own sync packet).
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
