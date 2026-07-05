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
 * Fires when a player finishes a Glint Trim with more than one layer (any layered trim). Layer count isn't
 * data-expressible in an item predicate, so the trim-producing paths call {@link #trigger(ServerPlayer)}
 * directly. Sibling of {@link EightLayerTrimTrigger} (which wants the full 8).
 */
public class LayeredTrimTrigger extends SimpleCriterionTrigger<LayeredTrimTrigger.TriggerInstance> {

    static final ResourceLocation ID = CustomGlint.res("layered_trim");

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
