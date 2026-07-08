package net.tunamods.customglint.module.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.tunamods.customglint.module.gui.GlintBagScreen;
import net.tunamods.customglint.module.gui.GlintTableScreen;
import net.tunamods.customglint.module.menu.ModMenuTypes;
import net.tunamods.customglint.module.network.GlintPrintedSyncPacket;
import net.tunamods.customglint.module.network.GlintStoredSyncPacket;

/** Client-only wiring for the Glint Table: binds the menu type to its screen + drops the sync mirrors on
 *  disconnect so a later session can't read stale data from the previous server. */
public final class GlintTableClientInit {
    private GlintTableClientInit() {}

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(GlintTableClientInit::onRegisterScreens);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            GlintStoredSyncPacket.clearClient();
            GlintPrintedSyncPacket.clearClient();
            GlintTableScreen.clearSavedBuild();
            GlintTableModelClient.clearTracked();
        });
    }

    private static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.GLINT_TABLE_MENU.get(), GlintTableScreen::new);
        event.register(ModMenuTypes.GLINT_BAG_MENU.get(), GlintBagScreen::new);
    }
}
