package net.tunamods.customglint;

import net.tunamods.customglint.module.command.GlintCommand;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.ModCreativeTabs;
import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.module.loot.ModLootModifiers;
import net.tunamods.customglint.module.recipe.ModRecipes;
import net.tunamods.customglint.module.compat.firstperson.FirstPersonCompat;
import net.tunamods.customglint.module.compat.iceandfire.IceAndFireCompat;
import net.tunamods.customglint.module.compat.epicknights.EpicKnightsCompat;
import net.tunamods.customglint.module.network.GlintDesignSyncPacket;
import net.tunamods.customglint.module.network.ModNetworking;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Full standalone mod entry. Registry content (items, creative tab, recipes, loot modifiers, blocks, block
 * entities, menus) lives in the {@code Mod*} holder classes under the matching module packages — see
 * {@link ModItems}, {@link ModCreativeTabs}, {@link ModRecipes}, {@link ModLootModifiers}, etc. This class
 * only wires their {@code register(bus)} hooks and owns the data-pack design reload/sync.
 */
@Mod(CustomGlintMod.MOD_ID)
public class CustomGlintMod {
    public static final String MOD_ID = "customglint";

    private final List<String> dataPackDesigns = new ArrayList<>();

    public CustomGlintMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        modEventBus.addListener(this::commonSetup);

        ModItems.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        ModLootModifiers.register(modEventBus);
        ModRecipes.register(modEventBus);
        net.tunamods.customglint.module.block.ModBlocks.register(modEventBus);
        net.tunamods.customglint.module.block.ModBlockEntities.register(modEventBus);
        net.tunamods.customglint.module.menu.ModMenuTypes.register(modEventBus);

        // Client-only: bind the Glint Table menu to its screen. Class-loaded on the client only.
        net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> net.tunamods.customglint.module.client.GlintTableClientInit.register(modEventBus));

        ModNetworking.register();
        IceAndFireCompat.register();
        FirstPersonCompat.register();
        EpicKnightsCompat.register();

        // Entity-glint sync (EntityGlintEvents, ApiNetworking, EntityGlintClientInit) is now
        // registered by CustomGlintApiMod — the api jar ships with the full jar via jarJar, so
        // those registrations always happen exactly once regardless of which jar a player has.

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.addListener(this::registerCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerJoin);
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
                    ModNetworking.CHANNEL.send(PacketDistributor.ALL.noArg(), packet);
                }
            }
        });
    }

    private void registerCommands(RegisterCommandsEvent event) {
        GlintCommand.register(event.getDispatcher());
    }

    private void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!dataPackDesigns.isEmpty() && event.getEntity() instanceof ServerPlayer player) {
            ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new GlintDesignSyncPacket(new ArrayList<>(dataPackDesigns)));
        }
    }

}
