package net.tunamods.customglint.common.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.tunamods.customglint.common.CustomGlintApiMod;

/**
 * Client-only initialization for {@code customglint_api}. Reached from
 * {@link CustomGlintApiMod} behind a {@code FMLEnvironment.dist == Dist.CLIENT} guard, so the JVM
 * never resolves {@link CustomGlintRenderer} or any client-only event class on a dedicated server.
 */
public final class CustomGlintClientInit {
    private CustomGlintClientInit() {}

    /** Invoked from the API mod constructor on client only, with the mod event bus. */
    public static void run(IEventBus modEventBus) {
        // Vanilla trident BEWLR outline texture: use the real trident texture so the outline
        // shader alpha-discards transparent texels instead of filling model cubes opaquely.
        CustomGlintRenderer.BEWLR_OUTLINE_TEXTURES.put(
                "net.minecraft.world.item.TridentItem",
                ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/trident.png"));

        modEventBus.addListener(CustomGlintClientInit::onRegisterClientReloadListeners);

        // Once-per-frame stencil-clear gate reset. See pendingFrameStencilClear's javadoc
        // in CustomGlintRenderer for the multi-outline / FullyBuffered drain interaction
        // this prevents. RenderFrameEvent.Pre fires once per rendered frame regardless of
        // shader pack or batched-render plumbing, which is what we need.
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> {
            CustomGlintRenderer.pendingFrameStencilClear = true;
            CustomGlintRenderer.shaderOutlinedThisFrame.clear();
            CustomGlintRenderer.resetStencilSlots();
        });
    }

    private static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) (ResourceManager manager) ->
                CustomGlintRenderer.clearTextures());
    }
}
