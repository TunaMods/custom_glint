package net.tunamods.customglint.common.client;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
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
        modEventBus.addListener(CustomGlintClientInit::onRegisterClientReloadListeners);
        // Glow-outline core shaders (silhouette + composite) — mod-bus event, client only.
        modEventBus.addListener(GlowOutlineRenderer::registerShaders);
        // Procedural chromatic glint core shader — mod-bus event, client only.
        modEventBus.addListener(CustomGlintRenderer::registerShaders);

        // Release the glow-outline offscreen targets + silhouette RT caches on resource reload, so
        // they aren't pinned for the whole session.
        CustomGlintRenderer.additionalReloadCleanup.add(GlowOutlineRenderer::release);

        // Once-per-frame stencil-clear gate reset, consumed by the compat stencil RTs
        // (IaF mount armor, EK decorations) that still use pendingFrameStencilClear.
        // RenderFrameEvent.Pre fires once per rendered frame regardless of shader pack or
        // batched-render plumbing, which is what we need. Also resets the glow-outline
        // per-frame capture queue + id counter.
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> {
            CustomGlintRenderer.pendingFrameStencilClear = true;
            CustomGlintRenderer.resetStencilSlots();
            GlowOutlineRenderer.beginFrame();
        });

        // Drain world-space item glow outlines after weather, where the live world projection /
        // modelview still match what the items were drawn with (the camera modelview hasn't been
        // popped yet) and the opaque scene depth is committed for the occlusion test.
        //
        // Under an Iris/Oculus shader pack the pack's own scene composite runs AFTER this stage and would
        // overwrite a ring drawn here, so only ACCUMULATE the mask now and defer the composite to
        // LevelRenderer.renderLevel TAIL (LevelRendererMixin → compositeWorld), after the pack composites
        // to the main target. Off-pack, do the whole drain immediately as before.
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
                if (CustomGlintRenderer.isShaderPackActive()) GlowOutlineRenderer.accumulateWorld();
                else GlowOutlineRenderer.drainWorld();
            }
        });
    }

    private static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) (ResourceManager manager) ->
                CustomGlintRenderer.clearTextures());
    }
}
