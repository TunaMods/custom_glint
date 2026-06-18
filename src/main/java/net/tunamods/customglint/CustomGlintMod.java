package net.tunamods.customglint;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.command.GlintCommand;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlintWandItem;
import net.tunamods.customglint.module.item.GlowTrimItem;
import net.tunamods.customglint.module.loot.GlintLootModifier;
import net.tunamods.customglint.module.loot.GlintTrimLootModifier;
import net.tunamods.customglint.module.client.TrimItemColors;
import net.tunamods.customglint.module.network.GlintDesignSyncPacket;
import net.tunamods.customglint.module.network.ModNetworking;
import net.tunamods.customglint.module.item.GlintTearItem;
import net.tunamods.customglint.module.item.GlintLayerTearItem;
import net.tunamods.customglint.module.item.GlintBlackTearItem;
import net.tunamods.customglint.module.recipe.GlintTearApplyRecipe;
import net.tunamods.customglint.module.recipe.GlintLayerTearRecipe;
import net.tunamods.customglint.module.recipe.GlintBlackTearRecipe;
import net.tunamods.customglint.module.recipe.GlintTrimDuplicateRecipe;
import net.tunamods.customglint.module.recipe.GlintTrimBlankDuplicateRecipe;
import net.tunamods.customglint.module.recipe.GlintTrimDyeRecipe;
import net.tunamods.customglint.module.recipe.GlintTrimMergeRecipe;
import net.tunamods.customglint.module.recipe.GlintTrimSmithingRecipe;
import net.tunamods.customglint.module.recipe.GlintTrimSpeedRecipe;
import net.tunamods.customglint.module.recipe.GlintTrimScaleRecipe;
import net.tunamods.customglint.module.recipe.GlintGlowTrimRecipe;
import net.tunamods.customglint.module.recipe.GlowTrimDyeRecipe;
import net.tunamods.customglint.module.recipe.GlowTrimMergeRecipe;
import net.tunamods.customglint.module.recipe.GlowTrimSmithingRecipe;
import com.mojang.serialization.Codec;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.mojang.serialization.MapCodec;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(CustomGlintMod.MOD_ID)
public class CustomGlintMod {
    public static final String MOD_ID = "customglint";

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);
    public static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> LOOT_MODIFIER_SERIALIZERS =
            DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, MOD_ID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, MOD_ID);

    public static final DeferredHolder<MapCodec<? extends IGlobalLootModifier>, MapCodec<GlintLootModifier>> GLINT_LOOT_MODIFIER =
            LOOT_MODIFIER_SERIALIZERS.register("glint_loot_modifier", GlintLootModifier.CODEC);
    public static final DeferredHolder<MapCodec<? extends IGlobalLootModifier>, MapCodec<GlintTrimLootModifier>> GLINT_TRIM_LOOT_MODIFIER =
            LOOT_MODIFIER_SERIALIZERS.register("glint_trim_loot_modifier", GlintTrimLootModifier.CODEC);

    public static final DeferredItem<GlintWandItem> GLINT_WAND = ITEMS.registerItem("glint_wand",
            props -> new GlintWandItem(props.stacksTo(1)));

    public static final DeferredItem<GlintTrimItem> GLINT_TRIM = ITEMS.registerItem("glint_trim",
            props -> new GlintTrimItem(props.stacksTo(16)));

    public static final DeferredItem<GlowTrimItem> GLOW_TRIM = ITEMS.registerItem("glow_trim",
            props -> new GlowTrimItem(props.stacksTo(16)));

    public static final DeferredItem<GlintTearItem> GLINT_TEAR_SIMULTANEOUS = ITEMS.registerItem("glint_tear_simultaneous",
            props -> new GlintTearItem(props.stacksTo(16), true));

    public static final DeferredItem<GlintTearItem> GLINT_TEAR_SEQUENTIAL = ITEMS.registerItem("glint_tear_sequential",
            props -> new GlintTearItem(props.stacksTo(16), false));

    public static final DeferredItem<GlintLayerTearItem> GLINT_LAYER_TEAR = ITEMS.registerItem("glint_layer_tear",
            props -> new GlintLayerTearItem(props.stacksTo(16)));

    public static final DeferredItem<GlintBlackTearItem> GLINT_BLACK_TEAR = ITEMS.registerItem("glint_black_tear",
            props -> new GlintBlackTearItem(props.stacksTo(16)));

    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintTearApplyRecipe>> GLINT_TEAR_APPLY_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_tear_apply", () -> GlintTearApplyRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintTrimDyeRecipe>> GLINT_TRIM_DYE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_dye", () -> GlintTrimDyeRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintTrimDuplicateRecipe>> GLINT_TRIM_DUPLICATE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_duplicate", () -> GlintTrimDuplicateRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintTrimBlankDuplicateRecipe>> GLINT_TRIM_BLANK_DUPLICATE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_blank_duplicate", () -> GlintTrimBlankDuplicateRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintTrimMergeRecipe>> GLINT_TRIM_MERGE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_merge", () -> GlintTrimMergeRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintTrimSmithingRecipe>> GLINT_TRIM_SMITHING_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_smithing", () -> GlintTrimSmithingRecipe.SERIALIZER);

    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintLayerTearRecipe>> GLINT_LAYER_TEAR_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_layer_tear", () -> GlintLayerTearRecipe.SERIALIZER);

    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintBlackTearRecipe>> GLINT_BLACK_TEAR_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_black_tear", () -> GlintBlackTearRecipe.SERIALIZER);

    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintTrimSpeedRecipe>> GLINT_TRIM_SPEED_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_speed", () -> GlintTrimSpeedRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintTrimScaleRecipe>> GLINT_TRIM_SCALE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_scale", () -> GlintTrimScaleRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlintGlowTrimRecipe>> GLINT_GLOW_TRIM_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_glow_trim", () -> GlintGlowTrimRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlowTrimDyeRecipe>> GLOW_TRIM_DYE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glow_trim_dye", () -> GlowTrimDyeRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlowTrimMergeRecipe>> GLOW_TRIM_MERGE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glow_trim_merge", () -> GlowTrimMergeRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<GlowTrimSmithingRecipe>> GLOW_TRIM_SMITHING_SERIALIZER =
            RECIPE_SERIALIZERS.register("glow_trim_smithing", () -> GlowTrimSmithingRecipe.SERIALIZER);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> GLINT_TAB = CREATIVE_MODE_TABS.register("glint_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.customglint.glint_tab"))
            .icon(() -> {
                ItemStack icon = new ItemStack(Items.ENCHANTED_BOOK);
                CustomGlint.write(icon,
                        Identifier.fromNamespaceAndPath("customglint", "textures/glint/wave.png"),
                        new int[]{0xFF8844EE, 0xFF00BBBB, 0xFFFFAA00},
                        0.5f, true, 1.0f, true);
                return icon;
            })
            .displayItems((parameters, output) -> {
                output.accept(GLINT_WAND.get());
                output.accept(GLINT_TEAR_SIMULTANEOUS.get().getDefaultInstance());
                output.accept(GLINT_TEAR_SEQUENTIAL.get().getDefaultInstance());
                output.accept(GLINT_LAYER_TEAR.get().getDefaultInstance());
                output.accept(GLINT_BLACK_TEAR.get().getDefaultInstance());
                output.accept(new ItemStack(GLOW_TRIM.get()));
                for (String pattern : GlintTrimItem.PATTERNS) {
                    ItemStack trim = new ItemStack(GLINT_TRIM.get());
                    Identifier loc;
                    if (pattern.equals("vanilla")) {
                        loc = CustomGlint.VANILLA;
                    } else if (pattern.contains(":")) {
                        int c = pattern.indexOf(':');
                        loc = Identifier.fromNamespaceAndPath(pattern.substring(0, c), "textures/glint/" + pattern.substring(c + 1) + ".png");
                    } else {
                        loc = Identifier.fromNamespaceAndPath("customglint", "textures/glint/" + pattern + ".png");
                    }
                    GlintTrimItem.setPattern(trim, loc);
                    output.accept(trim);
                }
            })
            .build());

    private final List<String> dataPackDesigns = new ArrayList<>();

    public CustomGlintMod(IEventBus modEventBus) {
        modEventBus.addListener(this::commonSetup);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        LOOT_MODIFIER_SERIALIZERS.register(modEventBus);
        RECIPE_SERIALIZERS.register(modEventBus);

        ModNetworking.register(modEventBus);

        // Animated glow tint for the Glint/Glow Trim inventory icons. Client-only (touches ItemTintSource);
        // the client class is referenced solely inside the dist guard so it never loads on a server.
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            TrimItemColors.register(modEventBus);
        }

        // Entity-glint sync (EntityGlintEvents, ApiNetworking, EntityGlintClientInit) is now
        // registered by CustomGlintApiMod — the api jar ships with the full jar via jarJar, so
        // those registrations always happen exactly once regardless of which jar a player has.

        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(this::onPlayerJoin);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
    }

    private void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(Identifier.fromNamespaceAndPath(MOD_ID, "designs"),
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
