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
 * Fires when a player finishes a Glint Trim with all 8 layers, each carrying all 8 colors. Neither the layer
 * count nor the per-layer color count can be checked by a data-only item predicate, so the places an 8x8 trim
 * is produced (the Glint Table print and a color/layer-adding craft) call {@link #trigger(ServerPlayer)}
 * directly, mirroring {@link EightLayerTrimTrigger}.
 */
public class EightByEightTrimTrigger extends SimpleCriterionTrigger<EightByEightTrimTrigger.TriggerInstance> {

    static final ResourceLocation ID = CustomGlint.res("eight_by_eight_trim");

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

    /** True when a trim carries all 8 layers and every layer holds all 8 colors. */
    public static boolean matches(CustomGlint.Data data) {
        if (data == null || data.layers().length < 8) return false;
        for (CustomGlint.Layer l : data.layers())
            if (l.colors().length < 8) return false;
        return true;
    }

    public static class TriggerInstance extends AbstractCriterionTriggerInstance {
        public TriggerInstance(ContextAwarePredicate player) {
            super(ID, player);
        }
    }
}
