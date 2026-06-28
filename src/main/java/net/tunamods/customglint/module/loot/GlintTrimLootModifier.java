package net.tunamods.customglint.module.loot;

import com.google.common.base.Suppliers;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.GlowTrimItem;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class GlintTrimLootModifier extends LootModifier {

    public static final Supplier<MapCodec<GlintTrimLootModifier>> CODEC =
        Suppliers.memoize(() -> RecordCodecBuilder.mapCodec(inst ->
            codecStart(inst).apply(inst, GlintTrimLootModifier::new)));

    protected GlintTrimLootModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    private static final Map<String, Float> PATTERN_WEIGHTS = buildWeights();

    private static Map<String, Float> buildWeights() {
        Map<String, Float> weights = new HashMap<>();
        // Nether
        weights.put("fire", 10.0f);
        weights.put("ember", 10.0f);
        weights.put("plasma", 10.0f);
        weights.put("oil", 8.0f);
        weights.put("smoke", 8.0f);
        // The End
        weights.put("glitch", 10.0f);
        weights.put("matrix", 10.0f);
        weights.put("static", 10.0f);
        weights.put("vanilla", 8.0f);
        weights.put("arcs", 8.0f);
        weights.put("pulse", 8.0f);
        // Ocean
        weights.put("tide", 10.0f);
        weights.put("wave", 10.0f);
        weights.put("ripple", 10.0f);
        weights.put("coral", 9.0f);
        weights.put("scales", 9.0f);
        weights.put("silk", 8.0f);
        weights.put("net", 8.0f);
        // Desert
        weights.put("dunes", 10.0f);
        weights.put("sand", 10.0f);
        weights.put("solid", 8.0f);
        weights.put("swirl", 8.0f);
        // Forest
        weights.put("petal", 10.0f);
        weights.put("feather", 10.0f);
        weights.put("blobs", 9.0f);
        weights.put("cascade", 8.0f);
        weights.put("debris", 8.0f);
        weights.put("mosaic", 8.0f);
        // Mountains
        weights.put("crystal", 10.0f);
        weights.put("diamonds", 10.0f);
        weights.put("vein", 9.0f);
        weights.put("cracks", 8.0f);
        weights.put("plate", 8.0f);
        weights.put("mesh", 8.0f);
        weights.put("grid", 8.0f);
        weights.put("tile", 8.0f);
        // Snow
        weights.put("frost", 10.0f);
        weights.put("aurora", 10.0f);
        weights.put("shimmer", 8.0f);
        // Swamp
        weights.put("weave", 10.0f);
        // Plains
        weights.put("stars", 10.0f);
        weights.put("lightning", 10.0f);
        weights.put("halo", 9.0f);
        weights.put("prism", 8.0f);
        weights.put("glow", 8.0f);
        weights.put("sheen", 8.0f);
        weights.put("sparkle", 8.0f);
        // Mushroom
        // (inherits from plains)
        // Universal fallback
        weights.put("chevron", 1.0f);
        weights.put("checker", 1.0f);
        weights.put("crosshatch", 1.0f);
        weights.put("hexagon", 1.0f);
        weights.put("stripes", 1.0f);
        weights.put("zigzag", 1.0f);
        weights.put("slash", 1.0f);
        return weights;
    }

    private String selectPattern(LootContext context) {
        // Get biome category to weight pattern selection. ORIGIN is absent on some data-pack /
        // modded chest tables, so fall back to a neutral biome rather than throwing.
        var origin = context.getParamOrNull(LootContextParams.ORIGIN);
        String biomeName = "plains";
        if (origin != null) {
            var biome = context.getLevel().getBiome(new BlockPos((int)origin.x, (int)origin.y, (int)origin.z));
            biomeName = biome.unwrapKey()
                .map(key -> key.location().getPath())
                .orElse("plains");
        }

        // Category-based selection bonus
        float netherBonus = isNetherBiome(biomeName) ? 3.0f : 1.0f;
        float endBonus = isEndBiome(biomeName) ? 3.0f : 1.0f;
        float oceanBonus = isOceanBiome(biomeName) ? 3.0f : 1.0f;
        float desertBonus = isDesertBiome(biomeName) ? 3.0f : 1.0f;
        float forestBonus = isForestBiome(biomeName) ? 3.0f : 1.0f;
        float mountainBonus = isMountainBiome(biomeName) ? 3.0f : 1.0f;
        float snowBonus = isSnowBiome(biomeName) ? 3.0f : 1.0f;
        float swampBonus = isSwampBiome(biomeName) ? 3.0f : 1.0f;
        float plainsBonus = isPlainsOrMushroomBiome(biomeName) ? 3.0f : 1.0f;

        float totalWeight = 0.0f;
        for (String pattern : GlintTrimItem.PATTERNS) {
            String cleanName = pattern.equals("vanilla") ? "vanilla" : pattern;
            float weight = PATTERN_WEIGHTS.getOrDefault(cleanName, 1.0f);

            // Apply category bonuses
            if (netherBonus > 1.0f && (cleanName.equals("fire") || cleanName.equals("ember") || cleanName.equals("plasma") || cleanName.equals("oil") || cleanName.equals("smoke")))
                weight *= netherBonus;
            else if (endBonus > 1.0f && (cleanName.equals("glitch") || cleanName.equals("matrix") || cleanName.equals("static") || cleanName.equals("vanilla") || cleanName.equals("arcs") || cleanName.equals("pulse")))
                weight *= endBonus;
            else if (oceanBonus > 1.0f && (cleanName.equals("tide") || cleanName.equals("wave") || cleanName.equals("ripple") || cleanName.equals("coral") || cleanName.equals("scales") || cleanName.equals("silk") || cleanName.equals("net")))
                weight *= oceanBonus;
            else if (desertBonus > 1.0f && (cleanName.equals("dunes") || cleanName.equals("sand") || cleanName.equals("solid") || cleanName.equals("swirl")))
                weight *= desertBonus;
            else if (forestBonus > 1.0f && (cleanName.equals("petal") || cleanName.equals("feather") || cleanName.equals("blobs") || cleanName.equals("cascade") || cleanName.equals("debris") || cleanName.equals("mosaic")))
                weight *= forestBonus;
            else if (mountainBonus > 1.0f && (cleanName.equals("crystal") || cleanName.equals("diamonds") || cleanName.equals("vein") || cleanName.equals("cracks") || cleanName.equals("plate") || cleanName.equals("mesh") || cleanName.equals("grid") || cleanName.equals("tile")))
                weight *= mountainBonus;
            else if (snowBonus > 1.0f && (cleanName.equals("frost") || cleanName.equals("aurora") || cleanName.equals("shimmer")))
                weight *= snowBonus;
            else if (swampBonus > 1.0f && cleanName.equals("weave"))
                weight *= swampBonus;
            else if (plainsBonus > 1.0f && (cleanName.equals("stars") || cleanName.equals("lightning") || cleanName.equals("halo") || cleanName.equals("prism") || cleanName.equals("glow") || cleanName.equals("sheen") || cleanName.equals("sparkle")))
                weight *= plainsBonus;

            totalWeight += weight;
        }

        float pick = context.getRandom().nextFloat() * totalWeight;
        for (String pattern : GlintTrimItem.PATTERNS) {
            String cleanName = pattern.equals("vanilla") ? "vanilla" : pattern;
            float weight = PATTERN_WEIGHTS.getOrDefault(cleanName, 1.0f);
            if (netherBonus > 1.0f && (cleanName.equals("fire") || cleanName.equals("ember") || cleanName.equals("plasma") || cleanName.equals("oil") || cleanName.equals("smoke")))
                weight *= netherBonus;
            else if (endBonus > 1.0f && (cleanName.equals("glitch") || cleanName.equals("matrix") || cleanName.equals("static") || cleanName.equals("vanilla") || cleanName.equals("arcs") || cleanName.equals("pulse")))
                weight *= endBonus;
            else if (oceanBonus > 1.0f && (cleanName.equals("tide") || cleanName.equals("wave") || cleanName.equals("ripple") || cleanName.equals("coral") || cleanName.equals("scales") || cleanName.equals("silk") || cleanName.equals("net")))
                weight *= oceanBonus;
            else if (desertBonus > 1.0f && (cleanName.equals("dunes") || cleanName.equals("sand") || cleanName.equals("solid") || cleanName.equals("swirl")))
                weight *= desertBonus;
            else if (forestBonus > 1.0f && (cleanName.equals("petal") || cleanName.equals("feather") || cleanName.equals("blobs") || cleanName.equals("cascade") || cleanName.equals("debris") || cleanName.equals("mosaic")))
                weight *= forestBonus;
            else if (mountainBonus > 1.0f && (cleanName.equals("crystal") || cleanName.equals("diamonds") || cleanName.equals("vein") || cleanName.equals("cracks") || cleanName.equals("plate") || cleanName.equals("mesh") || cleanName.equals("grid") || cleanName.equals("tile")))
                weight *= mountainBonus;
            else if (snowBonus > 1.0f && (cleanName.equals("frost") || cleanName.equals("aurora") || cleanName.equals("shimmer")))
                weight *= snowBonus;
            else if (swampBonus > 1.0f && cleanName.equals("weave"))
                weight *= swampBonus;
            else if (plainsBonus > 1.0f && (cleanName.equals("stars") || cleanName.equals("lightning") || cleanName.equals("halo") || cleanName.equals("prism") || cleanName.equals("glow") || cleanName.equals("sheen") || cleanName.equals("sparkle")))
                weight *= plainsBonus;

            pick -= weight;
            if (pick <= 0) return pattern;
        }
        return GlintTrimItem.PATTERNS.get(context.getRandom().nextInt(GlintTrimItem.PATTERNS.size()));
    }

    private static boolean isNetherBiome(String biome) {
        return biome.contains("nether");
    }

    private static boolean isEndBiome(String biome) {
        return biome.contains("end");
    }

    private static boolean isOceanBiome(String biome) {
        return biome.contains("ocean") || biome.contains("deep_ocean") || biome.contains("warm_ocean") || biome.contains("frozen_ocean") || biome.contains("cold_ocean") || biome.contains("lukewarm_ocean");
    }

    private static boolean isDesertBiome(String biome) {
        return biome.contains("desert") || biome.contains("badlands");
    }

    private static boolean isForestBiome(String biome) {
        return biome.contains("forest") || biome.contains("jungle") || biome.contains("dark_forest") || biome.contains("birch") || biome.contains("bamboo");
    }

    private static boolean isMountainBiome(String biome) {
        return biome.contains("mountain") || biome.contains("peak") || biome.contains("hill") || biome.contains("stony");
    }

    private static boolean isSnowBiome(String biome) {
        return biome.contains("snow") || biome.contains("frozen") || biome.contains("ice");
    }

    private static boolean isSwampBiome(String biome) {
        return biome.contains("swamp") || biome.contains("mangrove");
    }

    private static boolean isPlainsOrMushroomBiome(String biome) {
        return biome.contains("plains") || biome.contains("savanna") || biome.contains("meadow") || biome.contains("mushroom");
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        // Chest loot tables only. Without this gate the LM also fires on mob spawn-equipment
        // rolls (no DAMAGE_SOURCE, no BLOCK_STATE), spamming "Could not equip item" warnings
        // when Mob.equip can't slot a Trim/Tear anywhere.
        ResourceLocation tableId = context.getQueriedLootTableId();
        if (tableId == null || !tableId.getPath().startsWith("chests/")) return generatedLoot;

        // Trims: 22% for 1st (semi-rare), 12% conditional for 2nd (~2.6% overall, rare), 8% conditional for 3rd (~0.2% overall, very rare)
        int trimCount = 0;
        if (context.getRandom().nextFloat() < 0.22f) trimCount++;
        if (trimCount > 0 && context.getRandom().nextFloat() < 0.12f) trimCount++;
        if (trimCount > 1 && context.getRandom().nextFloat() < 0.08f) trimCount++;

        for (int t = 0; t < trimCount; t++) {
            String pattern = selectPattern(context);
            ItemStack trim = new ItemStack(ModItems.GLINT_TRIM.get());
            // designFromName resolves the special sentinels (vanilla, chromatic) as well as the textures/glint
            // designs — the old manual ".png" build turned "chromatic" into a bogus path so rolled chromatic
            // trims dropped blank.
            GlintTrimItem.setPattern(trim, CustomGlint.designFromName(pattern));
            if (context.getRandom().nextFloat() < 0.25f) {
                int colorCount = 1 + context.getRandom().nextInt(3);
                for (int i = 0; i < colorCount; i++)
                    GlintTrimItem.addColor(trim, GlintTrimItem.DYE_COLORS[context.getRandom().nextInt(GlintTrimItem.DYE_COLORS.length)]);
            }
            generatedLoot.add(trim);
        }

        // Glow Trims: 18% for 1st, 10% for 2nd, 5% for 3rd
        if (context.getRandom().nextFloat() < 0.18f) {
            ItemStack glowTrim = new ItemStack(ModItems.GLOW_TRIM.get());
            int colorCount = 1 + context.getRandom().nextInt(3);
            for (int i = 0; i < colorCount; i++)
                GlowTrimItem.addColor(glowTrim, GlintTrimItem.DYE_COLORS[context.getRandom().nextInt(GlintTrimItem.DYE_COLORS.length)]);
            generatedLoot.add(glowTrim);
            if (context.getRandom().nextFloat() < 0.10f) {
                ItemStack glowTrim2 = new ItemStack(ModItems.GLOW_TRIM.get());
                colorCount = 1 + context.getRandom().nextInt(3);
                for (int i = 0; i < colorCount; i++)
                    GlowTrimItem.addColor(glowTrim2, GlintTrimItem.DYE_COLORS[context.getRandom().nextInt(GlintTrimItem.DYE_COLORS.length)]);
                generatedLoot.add(glowTrim2);
                if (context.getRandom().nextFloat() < 0.05f) {
                    ItemStack glowTrim3 = new ItemStack(ModItems.GLOW_TRIM.get());
                    colorCount = 1 + context.getRandom().nextInt(3);
                    for (int i = 0; i < colorCount; i++)
                        GlowTrimItem.addColor(glowTrim3, GlintTrimItem.DYE_COLORS[context.getRandom().nextInt(GlintTrimItem.DYE_COLORS.length)]);
                    generatedLoot.add(glowTrim3);
                }
            }
        }

        // Each tear type independently: 20% for 1st, 10% for 2nd, 5% for 3rd
        if (context.getRandom().nextFloat() < 0.20f) {
            generatedLoot.add(ModItems.GLINT_TEAR_SIMULTANEOUS.get().getDefaultInstance());
            if (context.getRandom().nextFloat() < 0.10f) {
                generatedLoot.add(ModItems.GLINT_TEAR_SIMULTANEOUS.get().getDefaultInstance());
                if (context.getRandom().nextFloat() < 0.05f)
                    generatedLoot.add(ModItems.GLINT_TEAR_SIMULTANEOUS.get().getDefaultInstance());
            }
        }
        if (context.getRandom().nextFloat() < 0.20f) {
            generatedLoot.add(ModItems.GLINT_TEAR_SEQUENTIAL.get().getDefaultInstance());
            if (context.getRandom().nextFloat() < 0.10f) {
                generatedLoot.add(ModItems.GLINT_TEAR_SEQUENTIAL.get().getDefaultInstance());
                if (context.getRandom().nextFloat() < 0.05f)
                    generatedLoot.add(ModItems.GLINT_TEAR_SEQUENTIAL.get().getDefaultInstance());
            }
        }
        if (context.getRandom().nextFloat() < 0.20f) {
            generatedLoot.add(ModItems.GLINT_LAYER_TEAR.get().getDefaultInstance());
            if (context.getRandom().nextFloat() < 0.10f) {
                generatedLoot.add(ModItems.GLINT_LAYER_TEAR.get().getDefaultInstance());
                if (context.getRandom().nextFloat() < 0.05f)
                    generatedLoot.add(ModItems.GLINT_LAYER_TEAR.get().getDefaultInstance());
            }
        }
        if (context.getRandom().nextFloat() < 0.20f) {
            generatedLoot.add(ModItems.GLINT_BLACK_TEAR.get().getDefaultInstance());
            if (context.getRandom().nextFloat() < 0.10f) {
                generatedLoot.add(ModItems.GLINT_BLACK_TEAR.get().getDefaultInstance());
                if (context.getRandom().nextFloat() < 0.05f)
                    generatedLoot.add(ModItems.GLINT_BLACK_TEAR.get().getDefaultInstance());
            }
        }

        // Rainbow Dye: same 20% / 10% / 5% cascade as the tears
        if (context.getRandom().nextFloat() < 0.20f) {
            generatedLoot.add(ModItems.RAINBOW_DYE.get().getDefaultInstance());
            if (context.getRandom().nextFloat() < 0.10f) {
                generatedLoot.add(ModItems.RAINBOW_DYE.get().getDefaultInstance());
                if (context.getRandom().nextFloat() < 0.05f)
                    generatedLoot.add(ModItems.RAINBOW_DYE.get().getDefaultInstance());
            }
        }

        return generatedLoot;
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC.get();
    }
}
