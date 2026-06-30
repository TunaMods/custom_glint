package net.tunamods.customglint.module.loot;

import com.mojang.serialization.Codec;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.tunamods.customglint.CustomGlintMod;

public final class ModLootModifiers {
    private ModLootModifiers() {}

    public static final DeferredRegister<Codec<? extends IGlobalLootModifier>> LOOT_MODIFIER_SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, CustomGlintMod.MOD_ID);

    public static final RegistryObject<Codec<GlintLootModifier>> GLINT_LOOT_MODIFIER =
            LOOT_MODIFIER_SERIALIZERS.register("glint_loot_modifier", GlintLootModifier.CODEC);
    public static final RegistryObject<Codec<GlintTrimLootModifier>> GLINT_TRIM_LOOT_MODIFIER =
            LOOT_MODIFIER_SERIALIZERS.register("glint_trim_loot_modifier", GlintTrimLootModifier.CODEC);

    public static void register(IEventBus bus) {
        LOOT_MODIFIER_SERIALIZERS.register(bus);
    }
}
