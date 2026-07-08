package net.tunamods.customglint;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.CustomGlintRenderer;
import net.tunamods.customglint.module.advancement.EightByEightTrimTrigger;
import net.tunamods.customglint.module.advancement.ModTriggers;
import net.tunamods.customglint.module.command.GlintCommand;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.ModComponents;
import net.tunamods.customglint.module.client.GlintTableClientInit;
import net.tunamods.customglint.module.client.GlintTableModelClient;
import net.tunamods.customglint.module.client.TrimItemColors;
import net.tunamods.customglint.module.network.GlintDesignSyncPacket;
import net.tunamods.customglint.module.network.ModNetworking;
import net.tunamods.customglint.module.block.ModBlockEntities;
import net.tunamods.customglint.module.block.ModBlocks;
import net.tunamods.customglint.module.item.ModCreativeTabs;
import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.module.loot.ModLootModifiers;
import net.tunamods.customglint.module.menu.ModAttachments;
import net.tunamods.customglint.module.menu.ModMenuTypes;
import net.tunamods.customglint.module.recipe.ModRecipes;
import com.mojang.serialization.Codec;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.TriState;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.tunamods.customglint.module.item.GlintBagItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Mod(CustomGlintMod.MOD_ID)
public class CustomGlintMod {
    public static final String MOD_ID = "customglint";

    /** Data-pack design names added since the last reload; broadcast to clients and re-sent on join. */
    private final List<String> dataPackDesigns = new ArrayList<>();

    public CustomGlintMod(IEventBus modEventBus) {
        modEventBus.addListener(this::commonSetup);

        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenuTypes.register(modEventBus);
        ModAttachments.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        ModLootModifiers.register(modEventBus);
        ModRecipes.register(modEventBus);
        ModComponents.register(modEventBus);
        ModNetworking.register(modEventBus);
        ModTriggers.register(modEventBus);

        // Animated glow tint for the Glint/Glow Trim inventory icons. Client-only (touches ItemTintSource);
        // the client classes are referenced solely inside the dist guard so they never load on a server.
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            TrimItemColors.register(modEventBus);
            GlintTableClientInit.register(modEventBus);
            GlintTableModelClient.register(modEventBus);
            // Feed the renderer's shared GUI glint atlas the full, data-pack-inclusive design list (the api
            // jar can't see the module's design registry). A snapshot copy avoids a torn read while the
            // data-pack reload mutates PATTERNS on another thread; the renderer re-stitches on invalidation.
            CustomGlintRenderer.setGuiAtlasDesignSource(() -> {
                List<Identifier> ids = new ArrayList<>();
                for (String name : new ArrayList<>(GlintTrimItem.PATTERNS)) ids.add(CustomGlint.designFromName(name));
                return ids;
            });
        }

        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(this::onPlayerJoin);
        NeoForge.EVENT_BUS.addListener(this::onItemCrafted);
        NeoForge.EVENT_BUS.addListener(this::onItemPickup);
    }

    /** Route Glint loot items straight into a Glint Bag the player is carrying, so a looting run doesn't fill
     *  the main inventory. Anything the bags can't hold (full, or a bag is absent) falls through to vanilla
     *  pickup. */
    private void onItemPickup(ItemEntityPickupEvent.Pre event) {
        Player player = event.getPlayer();
        if (player.level().isClientSide()) return;
        ItemEntity itemEntity = event.getItemEntity();
        ItemStack picked = itemEntity.getItem();
        if (picked.isEmpty() || !GlintBagItem.isAutoCollectable(picked)) return;

        int before = picked.getCount();
        ItemStack remaining = picked;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack bag = inv.getItem(i);
            if (!(bag.getItem() instanceof GlintBagItem)) continue;
            if (!GlintBagItem.isAutoCollect(bag)) continue;
            IItemHandler handler = GlintBagItem.createHandler(bag);
            remaining = ItemHandlerHelper.insertItemStacked(handler, remaining, false);
        }

        int inserted = before - remaining.getCount();
        if (inserted <= 0) return; // no bag / no room — let vanilla handle it

        // Play the pickup pop for the portion the bag absorbed; the vanilla pickup path is denied below.
        player.take(itemEntity, inserted);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2f,
                ((player.getRandom().nextFloat() - player.getRandom().nextFloat()) * 0.7f + 1.0f) * 2.0f);
        if (remaining.isEmpty()) {
            itemEntity.discard();
        } else {
            itemEntity.setItem(remaining); // vanilla picks up whatever the bags couldn't hold, next tick
        }
        event.setCanPickup(TriState.FALSE); // we've handled this touch — don't also add to the main inventory
    }

    /** Award the 8-color trim advancement when a color-adding craft (dye / merge recipe) yields a full
     *  8-color Glint Trim. The Glint Table print fires the same trigger from {@code GlintTableMenu#print}. */
    private void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        // A crafted Glint Bag gets the same golden glint its creative/JEI icon shows.
        if (event.getCrafting().getItem() instanceof GlintBagItem) {
            GlintBagItem.applyGoldenGlint(event.getCrafting());
            return;
        }
        if (!(event.getCrafting().getItem() instanceof GlintTrimItem)) return;
        if (GlintTrimItem.getColors(event.getCrafting()).length >= 8) {
            ModTriggers.EIGHT_COLOR_TRIM.get().trigger(sp);
        }
        CustomGlint.Data data = CustomGlint.read(event.getCrafting());
        int layers = data != null ? data.layers().length : 0;
        if (layers >= 2) ModTriggers.LAYERED_TRIM.get().trigger(sp);
        if (layers >= 8) ModTriggers.EIGHT_LAYER_TRIM.get().trigger(sp);
        if (EightByEightTrimTrigger.matches(data)) ModTriggers.EIGHT_BY_EIGHT_TRIM.get().trigger(sp);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
    }

    private void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(CustomGlint.res("designs"),
                new SimpleJsonResourceReloadListener<List<String>>(Codec.STRING.listOf(), FileToIdConverter.json("customglint/designs")) {
            @Override
            protected void apply(Map<Identifier, List<String>> object, ResourceManager manager, ProfilerFiller profiler) {
                GlintTrimItem.PATTERNS.removeAll(dataPackDesigns);
                dataPackDesigns.clear();
                for (List<String> names : object.values()) {
                    for (String name : names) {
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
