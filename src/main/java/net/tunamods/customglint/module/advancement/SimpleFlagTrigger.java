package net.tunamods.customglint.module.advancement;

import com.google.gson.JsonObject;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Base for the parameterless "flag" criterion triggers. Each fires when a finished Glint Trim hits a
 * layer/color milestone that no data-only item predicate can express, so the producing path (Glint Table
 * print, color/layer-adding craft) calls {@link #trigger(ServerPlayer)} directly. A subclass is just an ID;
 * the trigger + instance plumbing is identical, so it lives here instead of being copied per milestone.
 */
public abstract class SimpleFlagTrigger extends SimpleCriterionTrigger<SimpleFlagTrigger.Instance> {

    private final ResourceLocation id;

    protected SimpleFlagTrigger(ResourceLocation id) {
        this.id = id;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    protected Instance createInstance(JsonObject json, ContextAwarePredicate player, DeserializationContext ctx) {
        return new Instance(id, player);
    }

    public void trigger(ServerPlayer player) {
        this.trigger(player, instance -> true);
    }

    public static class Instance extends AbstractCriterionTriggerInstance {
        public Instance(ResourceLocation id, ContextAwarePredicate player) {
            super(id, player);
        }
    }
}
