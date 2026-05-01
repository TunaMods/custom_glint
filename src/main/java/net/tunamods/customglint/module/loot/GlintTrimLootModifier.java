package net.tunamods.customglint.module.loot;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
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

    private static final float DROP_CHANCE = 0.20f;

    protected GlintTrimLootModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        if (context.getRandom().nextFloat() >= DROP_CHANCE) return generatedLoot;

        String pattern = GlintTrimItem.PATTERNS.get(context.getRandom().nextInt(GlintTrimItem.PATTERNS.size()));

        int colorCount = 1 + context.getRandom().nextInt(3);
        int[] colors = new int[colorCount];
        for (int i = 0; i < colorCount; i++) {
            colors[i] = GlintTrimItem.DYE_COLORS[context.getRandom().nextInt(GlintTrimItem.DYE_COLORS.length)];
        }

        ItemStack trim = new ItemStack(CustomGlintMod.GLINT_TRIM.get());
        ResourceLocation patternLoc = pattern.equals("vanilla")
            ? CustomGlint.VANILLA
            : new ResourceLocation("customglint", "textures/glint/" + pattern + ".png");
        GlintTrimItem.setPattern(trim, patternLoc);
        for (int color : colors) {
            GlintTrimItem.addColor(trim, color);
        }

        generatedLoot.add(trim);

        if (context.getRandom().nextFloat() < DROP_CHANCE)
            generatedLoot.add(CustomGlintMod.GLINT_TEAR_SIMULTANEOUS.get().getDefaultInstance());

        if (context.getRandom().nextFloat() < DROP_CHANCE)
            generatedLoot.add(CustomGlintMod.GLINT_TEAR_SEQUENTIAL.get().getDefaultInstance());

        return generatedLoot;
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC.get();
    }
}
