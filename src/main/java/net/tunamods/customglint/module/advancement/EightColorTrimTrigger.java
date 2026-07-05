package net.tunamods.customglint.module.advancement;

import com.google.gson.JsonObject;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.tunamods.customglint.common.CustomGlint;

/**
 * Fires when a player finishes a Glint Trim carrying all 8 colors. There's no data-only way to count the
 * colors inside our {@code customglint} NBT, so the two places an 8-color trim is produced (the Glint Table
 * print and a color-adding craft) call {@link #trigger(ServerPlayer)} directly.
 */
public class EightColorTrimTrigger extends SimpleCriterionTrigger<EightColorTrimTrigger.TriggerInstance> {

    static final ResourceLocation ID = CustomGlint.res("eight_color_trim");

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected TriggerInstance createInstance(JsonObject json, ContextAwarePredicate player, DeserializationContext ctx) {
        return new TriggerInstance(player);
    }

    public void trigger(ServerPlayer player) {
        this.trigger(player, instance -> true);
    }

    public static class TriggerInstance extends AbstractCriterionTriggerInstance {
        public TriggerInstance(ContextAwarePredicate player) {
            super(ID, player);
        }
    }
}
