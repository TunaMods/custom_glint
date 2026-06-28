package net.tunamods.customglint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import net.tunamods.customglint.module.block.ModBlockEntities;
import net.tunamods.customglint.module.block.ModBlocks;
import net.tunamods.customglint.module.client.GlintTableClientInit;
import net.tunamods.customglint.module.client.GlintTableModelClient;
import net.tunamods.customglint.module.command.GlintCommand;
import net.tunamods.customglint.module.compat.epicknights.EpicKnightsCompat;
import net.tunamods.customglint.module.compat.firstperson.FirstPersonCompat;
import net.tunamods.customglint.module.compat.iceandfire.IceAndFireCompat;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.ModComponents;
import net.tunamods.customglint.module.item.ModCreativeTabs;
import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.module.loot.ModLootModifiers;
import net.tunamods.customglint.module.menu.ModAttachments;
import net.tunamods.customglint.module.menu.ModMenuTypes;
import net.tunamods.customglint.module.network.GlintDesignSyncPacket;
import net.tunamods.customglint.module.network.ModNetworking;
import net.tunamods.customglint.module.recipe.ModRecipes;

/**
 * Full standalone mod entry. Registry content (items, creative tab, recipes, loot modifiers, blocks, block
 * entities, menus, attachments) lives in the {@code Mod*} holder classes under the matching module packages
 * — see {@link ModItems}, {@link ModCreativeTabs}, {@link ModRecipes}, {@link ModLootModifiers}, etc. This
 * class only wires their {@code register(bus)} hooks and owns the data-pack design reload/sync.
 */
@Mod(CustomGlintMod.MOD_ID)
public class CustomGlintMod {
    public static final String MOD_ID = "customglint";

    private final List<String> dataPackDesigns = new ArrayList<>();

    public CustomGlintMod(IEventBus modEventBus) {
        modEventBus.addListener(this::commonSetup);

        ModItems.register(modEventBus);
        ModComponents.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        ModLootModifiers.register(modEventBus);
        ModRecipes.register(modEventBus);
        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenuTypes.register(modEventBus);
        ModAttachments.register(modEventBus);

        ModNetworking.register(modEventBus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            GlintTableClientInit.register(modEventBus);
            GlintTableModelClient.register(modEventBus);
        }
        IceAndFireCompat.register();
        FirstPersonCompat.register();
        EpicKnightsCompat.register();

        // Entity glint is the synced ENTITY_GLINT attachment (registered by CustomGlintApiMod via
        // CustomGlintComponents); NeoForge auto-syncs it to trackers, so there is no sync packet to wire.

        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(this::onPlayerJoin);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
    }

    private void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new SimpleJsonResourceReloadListener(new Gson(), "customglint/designs") {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> object, ResourceManager manager, ProfilerFiller profiler) {
                GlintTrimItem.PATTERNS.removeAll(dataPackDesigns);
                dataPackDesigns.clear();
                for (JsonElement file : object.values()) {
                    if (!file.isJsonArray()) continue;
                    for (JsonElement entry : file.getAsJsonArray()) {
                        if (!entry.isJsonPrimitive()) continue;
                        String name = entry.getAsString();
                        if (!GlintTrimItem.PATTERNS.contains(name)) {
                            GlintTrimItem.PATTERNS.add(name);
                            dataPackDesigns.add(name);
                        }
                    }
                }
                if (ServerLifecycleHooks.getCurrentServer() != null) {
                    GlintDesignSyncPacket packet = new GlintDesignSyncPacket(new ArrayList<>(dataPackDesigns));
                    PacketDistributor.sendToAllPlayers(packet);
                }
            }
        });
    }

    private void registerCommands(RegisterCommandsEvent event) {
        GlintCommand.register(event.getDispatcher());
    }

    private void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!dataPackDesigns.isEmpty() && event.getEntity() instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(player, new GlintDesignSyncPacket(new ArrayList<>(dataPackDesigns)));
        }
    }
}
