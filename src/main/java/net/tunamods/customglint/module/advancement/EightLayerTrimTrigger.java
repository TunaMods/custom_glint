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
 * Fires when a player finishes a Glint Trim with all 8 layers. Layer count can't be checked by a data-only
 * item predicate, so the places an 8-layer trim is produced (the Glint Table print and a layer-adding craft)
 * call {@link #trigger(ServerPlayer)} directly, mirroring {@link EightColorTrimTrigger}.
 */
public class EightLayerTrimTrigger extends SimpleCriterionTrigger<EightLayerTrimTrigger.TriggerInstance> {

    static final ResourceLocation ID = CustomGlint.res("eight_layer_trim");

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
