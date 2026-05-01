// MIT License — Copyright (c) 2026 Likely Tuna | TunaMods — see LICENSE.txt
package net.tunamods.customglint;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.command.GlintCommand;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlintWandItem;
import net.tunamods.customglint.module.loot.GlintLootModifier;
import net.tunamods.customglint.module.loot.GlintTrimLootModifier;
import net.tunamods.customglint.module.network.ModNetworking;
import net.tunamods.customglint.module.item.GlintTearItem;
import net.tunamods.customglint.module.item.GlintLayerTearItem;
import net.tunamods.customglint.module.recipe.GlintTearApplyRecipe;
import net.tunamods.customglint.module.recipe.GlintLayerTearRecipe;
import net.tunamods.customglint.module.recipe.GlintTrimDuplicateRecipe;
import net.tunamods.customglint.module.recipe.GlintTrimDyeRecipe;
import net.tunamods.customglint.module.recipe.GlintTrimMergeRecipe;
import net.tunamods.customglint.module.recipe.GlintTrimSmithingRecipe;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.ItemFishedEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import com.mojang.serialization.Codec;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

@Mod(CustomGlintMod.MOD_ID)
public class CustomGlintMod {
    public static final String MOD_ID = "customglint";

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);
    public static final DeferredRegister<Codec<? extends IGlobalLootModifier>> LOOT_MODIFIER_SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, MOD_ID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, MOD_ID);

    public static final RegistryObject<Codec<GlintLootModifier>> GLINT_LOOT_MODIFIER =
            LOOT_MODIFIER_SERIALIZERS.register("glint_loot_modifier", GlintLootModifier.CODEC);
    public static final RegistryObject<Codec<GlintTrimLootModifier>> GLINT_TRIM_LOOT_MODIFIER =
            LOOT_MODIFIER_SERIALIZERS.register("glint_trim_loot_modifier", GlintTrimLootModifier.CODEC);

    public static final RegistryObject<GlintWandItem> GLINT_WAND = ITEMS.register("glint_wand",
            () -> new GlintWandItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<GlintTrimItem> GLINT_TRIM = ITEMS.register("glint_trim",
            () -> new GlintTrimItem(new Item.Properties().stacksTo(16)));

    public static final RegistryObject<GlintTearItem> GLINT_TEAR_SIMULTANEOUS = ITEMS.register("glint_tear_simultaneous",
            () -> new GlintTearItem(new Item.Properties().stacksTo(16), true));

    public static final RegistryObject<GlintTearItem> GLINT_TEAR_SEQUENTIAL = ITEMS.register("glint_tear_sequential",
            () -> new GlintTearItem(new Item.Properties().stacksTo(16), false));

    public static final RegistryObject<GlintLayerTearItem> GLINT_LAYER_TEAR = ITEMS.register("glint_layer_tear",
            () -> new GlintLayerTearItem(new Item.Properties().stacksTo(16)));

    public static final RegistryObject<RecipeSerializer<GlintTearApplyRecipe>> GLINT_TEAR_APPLY_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_tear_apply", () -> GlintTearApplyRecipe.SERIALIZER);
    public static final RegistryObject<RecipeSerializer<GlintTrimDyeRecipe>> GLINT_TRIM_DYE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_dye", () -> GlintTrimDyeRecipe.SERIALIZER);
    public static final RegistryObject<RecipeSerializer<GlintTrimDuplicateRecipe>> GLINT_TRIM_DUPLICATE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_duplicate", () -> GlintTrimDuplicateRecipe.SERIALIZER);
    public static final RegistryObject<RecipeSerializer<GlintTrimMergeRecipe>> GLINT_TRIM_MERGE_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_merge", () -> GlintTrimMergeRecipe.SERIALIZER);
    public static final RegistryObject<RecipeSerializer<GlintTrimSmithingRecipe>> GLINT_TRIM_SMITHING_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_trim_smithing", () -> GlintTrimSmithingRecipe.SERIALIZER);

    public static final RegistryObject<RecipeSerializer<GlintLayerTearRecipe>> GLINT_LAYER_TEAR_SERIALIZER =
            RECIPE_SERIALIZERS.register("glint_layer_tear", () -> GlintLayerTearRecipe.SERIALIZER);

    public static final RegistryObject<CreativeModeTab> GLINT_TAB = CREATIVE_MODE_TABS.register("glint_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.customglint.glint_tab"))
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> {
                ItemStack icon = new ItemStack(Items.ENCHANTED_BOOK);
                CustomGlint.write(icon,
                        new ResourceLocation("customglint", "textures/glint/wave.png"),
                        new int[]{0xFF8844EE, 0xFF00BBBB, 0xFFFFAA00},
                        0.5f, true, 1.0f, true);
                return icon;
            })
            .displayItems((parameters, output) -> {
                output.accept(GLINT_WAND.get());
                output.accept(GLINT_TEAR_SIMULTANEOUS.get().getDefaultInstance());
                output.accept(GLINT_TEAR_SEQUENTIAL.get().getDefaultInstance());
                output.accept(GLINT_LAYER_TEAR.get().getDefaultInstance());
                for (String pattern : GlintTrimItem.PATTERNS) {
                    ItemStack trim = new ItemStack(GLINT_TRIM.get());
                    ResourceLocation loc = pattern.equals("vanilla")
                        ? CustomGlint.VANILLA
                        : new ResourceLocation("customglint", "textures/glint/" + pattern + ".png");
                    GlintTrimItem.setPattern(trim, loc);
                    output.accept(trim);
                }
            })
            .build());

    public CustomGlintMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        modEventBus.addListener(this::commonSetup);

        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        LOOT_MODIFIER_SERIALIZERS.register(modEventBus);
        RECIPE_SERIALIZERS.register(modEventBus);

        ModNetworking.register();

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.addListener(this::registerCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onCraft);
        MinecraftForge.EVENT_BUS.addListener(this::onFish);
        MinecraftForge.EVENT_BUS.addListener(this::onMobDrop);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
    }

    private void registerCommands(RegisterCommandsEvent event) {
        GlintCommand.register(event.getDispatcher());
    }

    private void onCraft(PlayerEvent.ItemCraftedEvent event) {
        CustomGlint.applyCraftGlint(event.getCrafting());
        if (event.getInventory().getContainerSize() == 3) {
            ItemStack template = event.getInventory().getItem(0);
            if (template.getItem() == GLINT_TRIM.get()) {
                event.getEntity().addItem(template.copy());
            }
        }
    }

    private void onFish(ItemFishedEvent event) {
        event.getDrops().forEach(CustomGlint::applyFishingGlint);
    }

    private void onMobDrop(LivingDropsEvent event) {
        event.getDrops().forEach(entity -> CustomGlint.applyMobDropGlint(entity.getItem()));
    }
}
