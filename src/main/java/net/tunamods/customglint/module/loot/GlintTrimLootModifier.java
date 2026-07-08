package net.tunamods.customglint.module.loot;

import com.google.common.base.Suppliers;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class GlintTrimLootModifier extends LootModifier {

    public static final Supplier<MapCodec<GlintTrimLootModifier>> CODEC =
        Suppliers.memoize(() -> RecordCodecBuilder.mapCodec(inst ->
            codecStart(inst).apply(inst, GlintTrimLootModifier::new)));

    protected GlintTrimLootModifier(LootItemCondition[] conditions, int priority) {
        super(conditions, priority);
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
        // Weight pattern selection by the biome the chest sits in. ORIGIN is optional in the loot context;
        // getParameter throws if it's absent, so guard and fall back to a neutral biome weighting. (Chest
        // tables supply ORIGIN today, but a datapack/mod routing another table through the chests/ path
        // namespace might not.)
        String biomeName = "plains";
        if (context.hasParameter(LootContextParams.ORIGIN)) {
            var origin = context.getParameter(LootContextParams.ORIGIN);
            var biome = context.getLevel().getBiome(new BlockPos((int)origin.x, (int)origin.y, (int)origin.z));
            biomeName = biome.unwrapKey()
                .map(key -> key.identifier().getPath())
                .orElse("plains");
        }

        float netherBonus = isNetherBiome(biomeName) ? 3.0f : 1.0f;
        float endBonus = isEndBiome(biomeName) ? 3.0f : 1.0f;
        float oceanBonus = isOceanBiome(biomeName) ? 3.0f : 1.0f;
        float desertBonus = isDesertBiome(biomeName) ? 3.0f : 1.0f;
        float forestBonus = isForestBiome(biomeName) ? 3.0f : 1.0f;
        float mountainBonus = isMountainBiome(biomeName) ? 3.0f : 1.0f;
        float snowBonus = isSnowBiome(biomeName) ? 3.0f : 1.0f;
        float swampBonus = isSwampBiome(biomeName) ? 3.0f : 1.0f;
        float plainsBonus = isPlainsOrMushroomBiome(biomeName) ? 3.0f : 1.0f;

        // Pre-compute each pattern's biome-adjusted weight, then do a single weighted pick over them.
        List<String> patterns = GlintTrimItem.PATTERNS;
        float[] weights = new float[patterns.size()];
        float totalWeight = 0.0f;
        for (int i = 0; i < patterns.size(); i++) {
            String name = patterns.get(i);
            float weight = PATTERN_WEIGHTS.getOrDefault(name, 1.0f);
            if (netherBonus > 1.0f && (name.equals("fire") || name.equals("ember") || name.equals("plasma") || name.equals("oil") || name.equals("smoke")))
                weight *= netherBonus;
            else if (endBonus > 1.0f && (name.equals("glitch") || name.equals("matrix") || name.equals("static") || name.equals("vanilla") || name.equals("arcs") || name.equals("pulse")))
                weight *= endBonus;
            else if (oceanBonus > 1.0f && (name.equals("tide") || name.equals("wave") || name.equals("ripple") || name.equals("coral") || name.equals("scales") || name.equals("silk") || name.equals("net")))
                weight *= oceanBonus;
            else if (desertBonus > 1.0f && (name.equals("dunes") || name.equals("sand") || name.equals("solid") || name.equals("swirl")))
                weight *= desertBonus;
            else if (forestBonus > 1.0f && (name.equals("petal") || name.equals("feather") || name.equals("blobs") || name.equals("cascade") || name.equals("debris") || name.equals("mosaic")))
                weight *= forestBonus;
            else if (mountainBonus > 1.0f && (name.equals("crystal") || name.equals("diamonds") || name.equals("vein") || name.equals("cracks") || name.equals("plate") || name.equals("mesh") || name.equals("grid") || name.equals("tile")))
                weight *= mountainBonus;
            else if (snowBonus > 1.0f && (name.equals("frost") || name.equals("aurora") || name.equals("shimmer")))
                weight *= snowBonus;
            else if (swampBonus > 1.0f && name.equals("weave"))
                weight *= swampBonus;
            else if (plainsBonus > 1.0f && (name.equals("stars") || name.equals("lightning") || name.equals("halo") || name.equals("prism") || name.equals("glow") || name.equals("sheen") || name.equals("sparkle")))
                weight *= plainsBonus;
            weights[i] = weight;
            totalWeight += weight;
        }

        if (patterns.isEmpty()) return "vanilla"; // guard nextInt(0) / empty weighting
        float pick = context.getRandom().nextFloat() * totalWeight;
        for (int i = 0; i < patterns.size(); i++) {
            pick -= weights[i];
            if (pick <= 0) return patterns.get(i);
        }
        return patterns.get(context.getRandom().nextInt(patterns.size()));
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
        Identifier tableId = context.getQueriedLootTableId();
        if (tableId == null || !tableId.getPath().startsWith("chests/")) return generatedLoot;

        // Trims: cascading rolls (22% for 1st, 12% for 2nd, 8% for 3rd). Safe to cascade now that loot trims
        // are always blank — same design/type stacks, so extra rolls don't clutter the inventory.
        int trimCount = 0;
        if (context.getRandom().nextFloat() < 0.22f) trimCount++;
        if (trimCount > 0 && context.getRandom().nextFloat() < 0.12f) trimCount++;
        if (trimCount > 1 && context.getRandom().nextFloat() < 0.08f) trimCount++;

        for (int t = 0; t < trimCount; t++) {
            // Glow trims share this pool as the rarer variant (~1 in 4 trim drops). Both drop blank.
            if (context.getRandom().nextFloat() < 0.25f) {
                generatedLoot.add(new ItemStack(ModItems.GLOW_TRIM.get()));
                continue;
            }
            String pattern = selectPattern(context);
            ItemStack trim = new ItemStack(ModItems.GLINT_TRIM.get());
            Identifier patternLoc = CustomGlint.designFromName(pattern); // handles vanilla + chromatic sentinels
            GlintTrimItem.setPattern(trim, patternLoc);
            generatedLoot.add(trim);
        }

        // Tears and rainbow dye stack to 64 and get consumed in bulk, so they keep the cascading rolls: 20% for
        // 1st, 10% for 2nd, 5% for 3rd. Each tear type rolls independently.
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
