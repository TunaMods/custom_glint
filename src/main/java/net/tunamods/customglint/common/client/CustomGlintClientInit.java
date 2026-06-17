package net.tunamods.customglint.common.client;

import com.google.common.reflect.TypeToken;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ConfigureMainRenderTargetEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.tunamods.customglint.common.CustomGlintApiMod;

/**
 * Client-only initialization for {@code customglint_api}. Reached from
 * {@link CustomGlintApiMod} behind a {@code FMLEnvironment.getDist() == Dist.CLIENT} guard, so the JVM
 * never resolves {@link CustomGlintRenderer} or any client-only event class on a dedicated server.
 */
public final class CustomGlintClientInit {
    private CustomGlintClientInit() {}

    /** Invoked from the API mod constructor on client only, with the mod event bus + container. */
    public static void run(IEventBus modEventBus, ModContainer modContainer) {
        // Client-only rendering settings (outline resolution, per-type outline toggles). CLIENT type:
        // personal, never synced. Registering the generated config screen makes it editable in-game from
        // the mod list; both the screen and on-disk edits apply live (the render code re-reads each frame).
        modContainer.registerConfig(ModConfig.Type.CLIENT, GlintClientConfig.SPEC);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (IConfigScreenFactory) (container, parent) -> new ConfigurationScreen(container, parent));

        // Vanilla trident BEWLR outline texture: use the real trident texture so the outline
        // shader alpha-discards transparent texels instead of filling model cubes opaquely.
        CustomGlintRenderer.BEWLR_OUTLINE_TEXTURES.put(
                "net.minecraft.world.item.TridentItem",
                Identifier.fromNamespaceAndPath("minecraft", "textures/entity/trident.png"));

        modEventBus.addListener(CustomGlintClientInit::onRegisterClientReloadListeners);

        // The glow-outline composite pipelines are used via RenderPass.setPipeline directly (not through a
        // RenderType), so their shaders only get compiled if they're registered here. The silhouette pipeline
        // goes through RenderType.create and is compiled lazily like the other glint RTs.
        modEventBus.addListener((RegisterRenderPipelinesEvent event) -> {
            event.registerPipeline(GlintPipelines.GLOW_COMPOSITE_ID_PIPE);
            event.registerPipeline(GlintPipelines.GLOW_UPSCALE_PIPE);
        });

        // Stencil is the basis of the whole colored-outline system. The old
        // enableStencilBufferForFramebuffer call is gone in 26.1; the main render target only gets
        // a stencil attachment if a mod requests one via this mod-bus event. Without it every
        // StencilTest pipeline silently no-ops.
        modEventBus.addListener((ConfigureMainRenderTargetEvent event) -> event.enableStencil());

        // Entity glint attachment. The 26.1 entity render is decoupled from the entity by draw time
        // (LivingEntityRenderer.submit only sees a LivingEntityRenderState), so the glint must ride
        // the render state. This modifier runs after vanilla extraction for EVERY living renderer
        // (subclasses included) and stashes the resolved glint under EntityGlintRender.RENDER_DATA;
        // LivingEntityRendererMixin reads it back to submit the glint nodes.
        modEventBus.addListener((RegisterRenderStateModifiersEvent event) ->
                event.registerEntityModifier(
                        new TypeToken<LivingEntityRenderer<LivingEntity, LivingEntityRenderState, ?>>() {},
                        (entity, state) -> state.setRenderData(EntityGlintRender.RENDER_DATA,
                                EntityGlintRender.resolveResolution(entity))));

        // Once-per-frame stencil-clear gate reset. See pendingFrameStencilClear's javadoc
        // in CustomGlintRenderer for the multi-outline / FullyBuffered drain interaction
        // this prevents. RenderFrameEvent.Pre fires once per rendered frame regardless of
        // shader pack or batched-render plumbing, which is what we need.
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> {
            // Clear the main-target stencil HERE (outside any render pass) — clearStencilTexture
            // throws inside the framegraph main pass, where our outline replay runs, so the in-pass
            // clear was silently no-op'ing and stale stencil made the outline appear only from certain
            // angles. See CustomGlintRenderer.clearMainStencil.
            CustomGlintRenderer.clearMainStencil();
            CustomGlintRenderer.pendingFrameStencilClear = false;
            CustomGlintRenderer.shaderOutlinedThisFrame.clear();
            CustomGlintRenderer.resetStencilSlots();
            // Drop any body outlines queued last frame but never drained (level not rendered, stage
            // cancelled, etc.) so they can't replay against the wrong frame.
            EntityGlintRender.clearBodyOutlineQueue();
        });

        // Entity-body glow rings are queued during the entity submit(...) extraction (where the
        // entity-local pose is in hand) and drained HERE — at AfterWeather, the last level stage. The
        // composite writes straight to the main target's colour texture (raw GL, frame-graph pass aside),
        // and under Fancy graphics the clouds and weather passes ALSO write to main, executing AFTER the
        // "main" pass where the earlier stages fire. Draining at AfterTranslucentFeatures (inside the main
        // pass) let clouds and rain paint over the ring; AfterWeather runs after both, so the ring stays on
        // top of them. It's still later than the translucent glint (the reason the drain moved off
        // AfterOpaqueFeatures: there the glint drew after the ring → the ring showed through it as a faded
        // line). Occlusion is unaffected: the mask samples the OPAQUE scene depth, committed in the opaque
        // phase and never overwritten by translucent draws, so the ring still hides behind solid world
        // geometry. Under Fabulous graphics clouds/weather render to their own targets (combined later by
        // the transparency post-chain), so our direct-to-main composite lands before that chain at either
        // stage — no change there.
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterWeather event) ->
                EntityGlintRender.drainBodyOutlines());
    }

    private static void onRegisterClientReloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(Identifier.fromNamespaceAndPath("customglint", "glint_textures"),
                (ResourceManagerReloadListener) (ResourceManager manager) ->
                        CustomGlintRenderer.clearTextures());
    }
}
