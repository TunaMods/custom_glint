package net.tunamods.customglint.module.loot;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.item.GlintTrimItem;

import java.util.function.Supplier;

public class GlintTrimLootModifier extends LootModifier {

    public static final Supplier<Codec<GlintTrimLootModifier>> CODEC =
        Suppliers.memoize(() -> RecordCodecBuilder.create(inst ->
            codecStart(inst).apply(inst, GlintTrimLootModifier::new)));

    protected GlintTrimLootModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        if (context.hasParam(LootContextParams.BLOCK_STATE)) return generatedLoot;
        if (context.hasParam(LootContextParams.DAMAGE_SOURCE)) return generatedLoot;

        // Trims: 22% for 1st (semi-rare), 12% conditional for 2nd (~2.6% overall, rare), 8% conditional for 3rd (~0.2% overall, very rare)
        int trimCount = 0;
        if (context.getRandom().nextFloat() < 0.22f) trimCount++;
        if (trimCount > 0 && context.getRandom().nextFloat() < 0.12f) trimCount++;
        if (trimCount > 1 && context.getRandom().nextFloat() < 0.08f) trimCount++;

        for (int t = 0; t < trimCount; t++) {
            String pattern = GlintTrimItem.PATTERNS.get(context.getRandom().nextInt(GlintTrimItem.PATTERNS.size()));
            ItemStack trim = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
            ResourceLocation patternLoc = pattern.equals("vanilla")
                ? CustomGlint.VANILLA
                : new ResourceLocation("customglint", "textures/glint/" + pattern + ".png");
            GlintTrimItem.setPattern(trim, patternLoc);
            if (context.getRandom().nextFloat() < 0.25f) {
                int colorCount = 1 + context.getRandom().nextInt(3);
                for (int i = 0; i < colorCount; i++)
                    GlintTrimItem.addColor(trim, GlintTrimItem.DYE_COLORS[context.getRandom().nextInt(GlintTrimItem.DYE_COLORS.length)]);
            }
            generatedLoot.add(trim);
        }

        // Each tear type independently: 20% for 1st, 10% for 2nd, 5% for 3rd
        if (context.getRandom().nextFloat() < 0.20f) {
            generatedLoot.add(CustomGlintMod.GLINT_TEAR_SIMULTANEOUS.get().getDefaultInstance());
            if (context.getRandom().nextFloat() < 0.10f) {
                generatedLoot.add(CustomGlintMod.GLINT_TEAR_SIMULTANEOUS.get().getDefaultInstance());
                if (context.getRandom().nextFloat() < 0.05f)
                    generatedLoot.add(CustomGlintMod.GLINT_TEAR_SIMULTANEOUS.get().getDefaultInstance());
            }
        }
        if (context.getRandom().nextFloat() < 0.20f) {
            generatedLoot.add(CustomGlintMod.GLINT_TEAR_SEQUENTIAL.get().getDefaultInstance());
            if (context.getRandom().nextFloat() < 0.10f) {
                generatedLoot.add(CustomGlintMod.GLINT_TEAR_SEQUENTIAL.get().getDefaultInstance());
                if (context.getRandom().nextFloat() < 0.05f)
                    generatedLoot.add(CustomGlintMod.GLINT_TEAR_SEQUENTIAL.get().getDefaultInstance());
            }
        }
        if (context.getRandom().nextFloat() < 0.20f) {
            generatedLoot.add(CustomGlintMod.GLINT_LAYER_TEAR.get().getDefaultInstance());
            if (context.getRandom().nextFloat() < 0.10f) {
                generatedLoot.add(CustomGlintMod.GLINT_LAYER_TEAR.get().getDefaultInstance());
                if (context.getRandom().nextFloat() < 0.05f)
                    generatedLoot.add(CustomGlintMod.GLINT_LAYER_TEAR.get().getDefaultInstance());
            }
        }
        if (context.getRandom().nextFloat() < 0.20f) {
            generatedLoot.add(CustomGlintMod.GLINT_BLACK_TEAR.get().getDefaultInstance());
            if (context.getRandom().nextFloat() < 0.10f) {
                generatedLoot.add(CustomGlintMod.GLINT_BLACK_TEAR.get().getDefaultInstance());
                if (context.getRandom().nextFloat() < 0.05f)
                    generatedLoot.add(CustomGlintMod.GLINT_BLACK_TEAR.get().getDefaultInstance());
            }
        }

        return generatedLoot;
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC.get();
    }
}
