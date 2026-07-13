package net.tunamods.customglint.module.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Base for the trim-milestone triggers that carry no data beyond the player predicate. Their condition
 * (color count, layer count) can't be expressed in a data-only item predicate, so the trim-producing paths
 * call {@link #trigger(ServerPlayer)} directly when the milestone is hit. Each subclass is a distinct
 * registered trigger; they share this instance record because the fired predicate is identical.
 */
public abstract class PlayerOnlyTrigger extends SimpleCriterionTrigger<PlayerOnlyTrigger.TriggerInstance> {

    public record TriggerInstance(Optional<ContextAwarePredicate> player)
            implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(i -> i.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player)
        ).apply(i, TriggerInstance::new));
    }

    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    public void trigger(ServerPlayer player) {
        this.trigger(player, instance -> true);
    }
}
