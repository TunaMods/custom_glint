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
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Adds the mod's own drops to vanilla chest loot: Glint and Glow Trims picked by biome theme, plus the
 * bulk-consumed tears and rainbow dye. Its instance JSON lives in {@code data/customglint/loot_modifiers/}
 * and is switched on by {@code data/neoforge/loot_modifiers/global_loot_modifiers.json}.
 */
public class GlintTrimLootModifier extends LootModifier {

    public static final Supplier<MapCodec<GlintTrimLootModifier>> CODEC =
        Suppliers.memoize(() -> RecordCodecBuilder.mapCodec(inst ->
            codecStart(inst).apply(inst, GlintTrimLootModifier::new)));

    /** Roll weight for a design no theme claims, including any design a data pack adds. */
    private static final float UNTHEMED_WEIGHT = 1.0f;

    /** Themed designs roll three times as often in their own biome. */
    private static final float THEME_BONUS = 3.0f;

    /**
     * One biome theme: the biome-path fragments it covers, and the designs it owns with their base roll
     * weights. A design belongs to at most one theme, and its weight is multiplied by {@link #THEME_BONUS}
     * when the chest sits in a matching biome. Mushroom biomes deliberately share the plains theme.
     */
    private record Theme(List<String> biomes, Map<String, Float> weights) {
        boolean coversBiome(String biome) {
            for (String fragment : biomes)
                if (biome.contains(fragment)) return true;
            return false;
        }
    }

    private static final List<Theme> THEMES = List.of(
        new Theme(List.of("nether"), Map.of(
            "fire", 10.0f, "ember", 10.0f, "plasma", 10.0f, "oil", 8.0f, "smoke", 8.0f)),
        new Theme(List.of("end"), Map.of(
            "glitch", 10.0f, "matrix", 10.0f, "static", 10.0f, "vanilla", 8.0f, "arcs", 8.0f, "pulse", 8.0f)),
        new Theme(List.of("ocean"), Map.of(
            "tide", 10.0f, "wave", 10.0f, "ripple", 10.0f, "coral", 9.0f, "scales", 9.0f, "silk", 8.0f,
            "net", 8.0f)),
        new Theme(List.of("desert", "badlands"), Map.of(
            "dunes", 10.0f, "sand", 10.0f, "solid", 8.0f, "swirl", 8.0f)),
        new Theme(List.of("forest", "jungle", "birch", "bamboo"), Map.of(
            "petal", 10.0f, "feather", 10.0f, "blobs", 9.0f, "cascade", 8.0f, "debris", 8.0f, "mosaic", 8.0f)),
        new Theme(List.of("mountain", "peak", "hill", "stony"), Map.of(
            "crystal", 10.0f, "diamonds", 10.0f, "vein", 9.0f, "cracks", 8.0f, "plate", 8.0f, "mesh", 8.0f,
            "grid", 8.0f, "tile", 8.0f)),
        new Theme(List.of("snow", "frozen", "ice"), Map.of(
            "frost", 10.0f, "aurora", 10.0f, "shimmer", 8.0f)),
        new Theme(List.of("swamp", "mangrove"), Map.of(
            "weave", 10.0f)),
        new Theme(List.of("plains", "savanna", "meadow", "mushroom"), Map.of(
            "stars", 10.0f, "lightning", 10.0f, "halo", 9.0f, "prism", 8.0f, "glow", 8.0f, "sheen", 8.0f,
            "sparkle", 8.0f))
    );

    protected GlintTrimLootModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    /** Weighted design roll for one dropped trim, biased toward the chest's biome theme. */
    private static String selectPattern(LootContext context) {
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

    /** Roll weight for one design in the given biome: its theme's base weight, tripled in that theme's own
     *  biome so fire drops in the Nether and frost in the snow. Designs no theme claims sit at the floor. */
    private static float weightOf(String pattern, String biomeName) {
        for (Theme theme : THEMES) {
            Float base = theme.weights().get(pattern);
            if (base == null) continue;
            return theme.coversBiome(biomeName) ? base * THEME_BONUS : base;
        }
        return UNTHEMED_WEIGHT;
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
