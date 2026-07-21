package net.tunamods.customglint.module.loot;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.ModItems;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class GlintTrimLootModifier extends LootModifier {

    /** Patterns matching the loot origin's biome category get this weight multiplier. */
    private static final float BIOME_MATCH_BONUS = 3.0f;

    public static final Supplier<Codec<GlintTrimLootModifier>> CODEC =
        Suppliers.memoize(() -> RecordCodecBuilder.create(inst ->
            codecStart(inst).apply(inst, GlintTrimLootModifier::new)));

    private static final Map<String, Float> PATTERN_WEIGHTS = buildWeights();

    /** Biome-category predicate paired with the pattern names that get {@link #BIOME_MATCH_BONUS} in that
     *  biome. Checked in order; a pattern belongs to at most one category, so order does not affect results. */
    private static final List<BiomeCategory> BIOME_CATEGORIES = List.of(
        new BiomeCategory(GlintTrimLootModifier::isNetherBiome, Set.of("fire", "ember", "plasma", "oil", "smoke")),
        new BiomeCategory(GlintTrimLootModifier::isEndBiome, Set.of("glitch", "matrix", "static", "vanilla", "arcs", "pulse")),
        new BiomeCategory(GlintTrimLootModifier::isOceanBiome, Set.of("tide", "wave", "ripple", "coral", "scales", "silk", "net")),
        new BiomeCategory(GlintTrimLootModifier::isDesertBiome, Set.of("dunes", "sand", "solid", "swirl")),
        new BiomeCategory(GlintTrimLootModifier::isForestBiome, Set.of("petal", "feather", "blobs", "cascade", "debris", "mosaic")),
        new BiomeCategory(GlintTrimLootModifier::isMountainBiome, Set.of("crystal", "diamonds", "vein", "cracks", "plate", "mesh", "grid", "tile")),
        new BiomeCategory(GlintTrimLootModifier::isSnowBiome, Set.of("frost", "aurora", "shimmer")),
        new BiomeCategory(GlintTrimLootModifier::isSwampBiome, Set.of("weave")),
        new BiomeCategory(GlintTrimLootModifier::isPlainsOrMushroomBiome, Set.of("stars", "lightning", "halo", "prism", "glow", "sheen", "sparkle"))
    );

    protected GlintTrimLootModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    private record BiomeCategory(Predicate<String> biomeMatch, Set<String> patterns) {}

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

    private static String selectPattern(LootContext context) {
        // ORIGIN isn't guaranteed on every loot context (some modded chest tables omit it); getParam would
        // throw NoSuchElementException, so default to plains.
        var origin = context.getParamOrNull(LootContextParams.ORIGIN);
        String biomeName = "plains";
        if (origin != null) {
            var biome = context.getLevel().getBiome(new BlockPos((int) origin.x, (int) origin.y, (int) origin.z));
            biomeName = biome.unwrapKey()
                .map(key -> key.location().getPath())
                .orElse("plains");
        }

        // Weight each pattern once into the array, then walk it to pick.
        List<String> patterns = GlintTrimItem.PATTERNS;
        float[] weights = new float[patterns.size()];
        float totalWeight = 0.0f;
        for (int i = 0; i < patterns.size(); i++) {
            String cleanName = patterns.get(i);
            float weight = PATTERN_WEIGHTS.getOrDefault(cleanName, 1.0f);

            for (BiomeCategory category : BIOME_CATEGORIES) {
                if (category.patterns().contains(cleanName) && category.biomeMatch().test(biomeName)) {
                    weight *= BIOME_MATCH_BONUS;
                    break;
                }
            }

            weights[i] = weight;
            totalWeight += weight;
        }

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
        return biome.contains("ocean");
    }

    private static boolean isDesertBiome(String biome) {
        return biome.contains("desert") || biome.contains("badlands");
    }

    private static boolean isForestBiome(String biome) {
        return biome.contains("forest") || biome.contains("jungle") || biome.contains("birch") || biome.contains("bamboo");
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
        // are always blank, same design/type stacks, so extra rolls don't clutter the inventory.
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
            // designFromName maps "vanilla"→VANILLA and "chromatic"→the CHROMATIC sentinel (no PNG); setPattern
            // then rolls a chromatic seed. Building the RL inline would mint a dead chromatic.png path.
            GlintTrimItem.setPattern(trim, CustomGlint.designFromName(pattern));
            generatedLoot.add(trim);
        }

        // Tears and rainbow dye stack to 64 and get consumed in bulk, so they keep the cascading rolls (20% /
        // 10% / 5%). Each type rolls independently.
        rollCascade(context, generatedLoot, () -> ModItems.GLINT_TEAR_SIMULTANEOUS.get().getDefaultInstance());
        rollCascade(context, generatedLoot, () -> ModItems.GLINT_TEAR_SEQUENTIAL.get().getDefaultInstance());
        rollCascade(context, generatedLoot, () -> ModItems.GLINT_LAYER_TEAR.get().getDefaultInstance());
        rollCascade(context, generatedLoot, () -> ModItems.GLINT_BLACK_TEAR.get().getDefaultInstance());
        rollCascade(context, generatedLoot, () -> ModItems.RAINBOW_DYE.get().getDefaultInstance());

        return generatedLoot;
    }

    /** Up to three cascading rolls (20% → 10% → 5%) that each add one {@code item} to the loot. */
    private static void rollCascade(LootContext context, ObjectArrayList<ItemStack> loot,
                                    Supplier<ItemStack> item) {
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
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC.get();
    }
}
