package net.tunamods.customglint.module.advancement;

import com.google.gson.JsonObject;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.GsonHelper;
import net.tunamods.customglint.common.CustomGlint;

/**
 * Fires with how many BASE designs the player has collected in their Glint Table library. Each collection
 * advancement sets either a {@code count} threshold (collect 5 / 10 / 20 / 50) or {@code all} (collect every
 * base design, whose total is resolved server-side so it can't drift out of sync with a hardcoded number).
 */
public class DesignsCollectedTrigger extends SimpleCriterionTrigger<DesignsCollectedTrigger.TriggerInstance> {

    static final ResourceLocation ID = CustomGlint.res("designs_collected");

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected TriggerInstance createInstance(JsonObject json, ContextAwarePredicate player, DeserializationContext ctx) {
        Integer count = json.has("count") ? GsonHelper.getAsInt(json, "count") : null;
        boolean all = GsonHelper.getAsBoolean(json, "all", false);
        return new TriggerInstance(player, count, all);
    }

    public void trigger(ServerPlayer player, int collected, int total) {
        this.trigger(player, inst -> inst.matches(collected, total));
    }

    public static class TriggerInstance extends AbstractCriterionTriggerInstance {
        private final Integer count;
        private final boolean all;

        public TriggerInstance(ContextAwarePredicate player, Integer count, boolean all) {
            super(ID, player);
            this.count = count;
            this.all = all;
        }

        public boolean matches(int collected, int total) {
            if (all) return collected >= total;
            return count == null || collected >= count;
        }
    }
}
