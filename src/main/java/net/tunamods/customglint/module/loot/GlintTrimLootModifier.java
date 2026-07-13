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
        // ORIGIN is absent on some data-pack / modded chest tables, so fall back to a neutral biome.
        var origin = context.getParamOrNull(LootContextParams.ORIGIN);
        String biomeName = "plains";
        if (origin != null) {
            var biome = context.getLevel().getBiome(new BlockPos((int)origin.x, (int)origin.y, (int)origin.z));
            biomeName = biome.unwrapKey()
                .map(key -> key.location().getPath())
                .orElse("plains");
        }

        float totalWeight = 0.0f;
        for (String pattern : GlintTrimItem.PATTERNS)
            totalWeight += weightOf(pattern, biomeName);

        float pick = context.getRandom().nextFloat() * totalWeight;
        for (String pattern : GlintTrimItem.PATTERNS) {
            pick -= weightOf(pattern, biomeName);
            if (pick <= 0) return pattern;
        }
        return GlintTrimItem.PATTERNS.get(context.getRandom().nextInt(GlintTrimItem.PATTERNS.size()));
    }

    /** Roll weight for one design in the given biome: its base {@link #PATTERN_WEIGHTS} entry, tripled when the
     *  design's theme matches the chest's biome category. Only the first matching category applies. The 3x
     *  bonus makes themed designs (fire in the Nether, frost in snow) the common drop there. */
    private static float weightOf(String pattern, String biomeName) {
        float weight = PATTERN_WEIGHTS.getOrDefault(pattern, 1.0f);
        if (isNetherBiome(biomeName) && (pattern.equals("fire") || pattern.equals("ember") || pattern.equals("plasma") || pattern.equals("oil") || pattern.equals("smoke")))
            weight *= 3.0f;
        else if (isEndBiome(biomeName) && (pattern.equals("glitch") || pattern.equals("matrix") || pattern.equals("static") || pattern.equals("vanilla") || pattern.equals("arcs") || pattern.equals("pulse")))
            weight *= 3.0f;
        else if (isOceanBiome(biomeName) && (pattern.equals("tide") || pattern.equals("wave") || pattern.equals("ripple") || pattern.equals("coral") || pattern.equals("scales") || pattern.equals("silk") || pattern.equals("net")))
            weight *= 3.0f;
        else if (isDesertBiome(biomeName) && (pattern.equals("dunes") || pattern.equals("sand") || pattern.equals("solid") || pattern.equals("swirl")))
            weight *= 3.0f;
        else if (isForestBiome(biomeName) && (pattern.equals("petal") || pattern.equals("feather") || pattern.equals("blobs") || pattern.equals("cascade") || pattern.equals("debris") || pattern.equals("mosaic")))
            weight *= 3.0f;
        else if (isMountainBiome(biomeName) && (pattern.equals("crystal") || pattern.equals("diamonds") || pattern.equals("vein") || pattern.equals("cracks") || pattern.equals("plate") || pattern.equals("mesh") || pattern.equals("grid") || pattern.equals("tile")))
            weight *= 3.0f;
        else if (isSnowBiome(biomeName) && (pattern.equals("frost") || pattern.equals("aurora") || pattern.equals("shimmer")))
            weight *= 3.0f;
        else if (isSwampBiome(biomeName) && pattern.equals("weave"))
            weight *= 3.0f;
        else if (isPlainsOrMushroomBiome(biomeName) && (pattern.equals("stars") || pattern.equals("lightning") || pattern.equals("halo") || pattern.equals("prism") || pattern.equals("glow") || pattern.equals("sheen") || pattern.equals("sparkle")))
            weight *= 3.0f;
        return weight;
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

        // Trims: cascading rolls (22% for 1st, 12% for 2nd, 8% for 3rd). Safe to cascade now that loot trims
        // are always blank: same design/type stacks, so extra rolls don't clutter the inventory.
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
            // designFromName resolves the special sentinels (vanilla, chromatic) as well as the textures/glint
            // designs; the old manual ".png" build turned "chromatic" into a bogus path so rolled chromatic
            // trims dropped blank.
            GlintTrimItem.setPattern(trim, CustomGlint.designFromName(pattern));
            generatedLoot.add(trim);
        }

        // Tears and rainbow dye stack to 64 and get consumed in bulk, so they keep the cascading rolls: 20% for
        // 1st, 10% for 2nd, 5% for 3rd. Each type rolls independently.
        rollCascade(context, generatedLoot, () -> ModItems.GLINT_TEAR_SIMULTANEOUS.get().getDefaultInstance());
        rollCascade(context, generatedLoot, () -> ModItems.GLINT_TEAR_SEQUENTIAL.get().getDefaultInstance());
        rollCascade(context, generatedLoot, () -> ModItems.GLINT_LAYER_TEAR.get().getDefaultInstance());
        rollCascade(context, generatedLoot, () -> ModItems.GLINT_BLACK_TEAR.get().getDefaultInstance());
        rollCascade(context, generatedLoot, () -> ModItems.RAINBOW_DYE.get().getDefaultInstance());

        return generatedLoot;
    }

    /** Cascading independent rolls for one bulk-consumed item: 20% for the 1st copy, then 10% / 5% for a 2nd / 3rd. */
    private static void rollCascade(LootContext context, ObjectArrayList<ItemStack> loot, Supplier<ItemStack> item) {
        if (context.getRandom().nextFloat() < 0.20f) {
            loot.add(item.get());
            if (context.getRandom().nextFloat() < 0.10f) {
                loot.add(item.get());
                if (context.getRandom().nextFloat() < 0.05f)
                    loot.add(item.get());
            }
        }
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC.get();
    }
}
