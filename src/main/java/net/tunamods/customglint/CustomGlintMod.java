package net.tunamods.customglint;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.advancement.EightByEightTrimTrigger;
import net.tunamods.customglint.module.advancement.ModTriggers;
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
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

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
    public static final Logger LOGGER = LogUtils.getLogger();

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
        net.tunamods.customglint.module.compat.geckolib.GeckoLibArmorCompat.register();
        net.tunamods.customglint.module.compat.immersivearmors.ImmersiveArmorsCompat.register();

        // Entity-glint sync (EntityGlintEvents, ApiNetworking, EntityGlintClientInit) is now
        // registered by CustomGlintApiMod — the api jar ships with the full jar via jarJar, so
        // those registrations always happen exactly once regardless of which jar a player has.

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.addListener(this::registerCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerJoin);
        MinecraftForge.EVENT_BUS.addListener(this::onItemCrafted);
        MinecraftForge.EVENT_BUS.addListener(this::onItemPickup);
    }

    /** Route Glint loot items straight into a Glint Bag the player is carrying, so a looting run doesn't fill
     *  the main inventory. Anything the bags can't hold (full, or a bag is absent) falls through to vanilla
     *  pickup. */
    private void onItemPickup(net.minecraftforge.event.entity.player.EntityItemPickupEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) return;
        net.minecraft.world.entity.item.ItemEntity itemEntity = event.getItem();
        ItemStack picked = itemEntity.getItem();
        if (picked.isEmpty() || !net.tunamods.customglint.module.item.GlintBagItem.isAutoCollectable(picked)) return;

        int before = picked.getCount();
        ItemStack remaining = picked;
        net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack bag = inv.getItem(i);
            if (!(bag.getItem() instanceof net.tunamods.customglint.module.item.GlintBagItem)) continue;
            if (!net.tunamods.customglint.module.item.GlintBagItem.isAutoCollect(bag)) continue;
            net.minecraftforge.items.IItemHandler handler =
                    bag.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER).orElse(null);
            if (handler == null) continue;
            remaining = net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(handler, remaining, false);
        }

        int inserted = before - remaining.getCount();
        if (inserted <= 0) return; // no bag / no room — let vanilla handle it

        player.take(itemEntity, inserted); // pickup animation + sound for the portion the bag took
        if (remaining.isEmpty()) {
            itemEntity.discard();
            event.setCanceled(true);
        } else {
            itemEntity.setItem(remaining); // vanilla picks up whatever the bags couldn't hold
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // Custom advancement triggers register straight into the vanilla registry (no DeferredRegister on
        // 1.20.1). enqueueWork keeps it on the main thread, off the parallel mod-loading threads.
        event.enqueueWork(ModTriggers::register);
    }

    /** Award the color/layer trim advancements when a crafting-table recipe (dye / merge / layer) yields a
     *  qualifying Glint Trim. The Glint Table print fires the same triggers from {@code GlintTableMenu#print}. */
    private void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        // A crafted Glint Bag gets the same Golden glow trim its creative/JEI icon shows.
        if (event.getCrafting().getItem() instanceof net.tunamods.customglint.module.item.GlintBagItem) {
            net.tunamods.customglint.module.item.GlintBagItem.applyGoldenGlint(event.getCrafting());
            return;
        }
        if (!(event.getCrafting().getItem() instanceof GlintTrimItem)) return;
        if (GlintTrimItem.getColors(event.getCrafting()).length >= 8) {
            ModTriggers.EIGHT_COLOR_TRIM.trigger(sp);
        }
        CustomGlint.Data data = CustomGlint.read(event.getCrafting());
        int layers = data != null ? data.layers().length : 0;
        if (layers >= 2) ModTriggers.LAYERED_TRIM.trigger(sp);
        if (layers >= 8) ModTriggers.EIGHT_LAYER_TRIM.trigger(sp);
        if (EightByEightTrimTrigger.matches(data)) ModTriggers.EIGHT_BY_EIGHT_TRIM.trigger(sp);
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
