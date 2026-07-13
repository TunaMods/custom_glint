package net.tunamods.customglint.module.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.criterion.ContextAwarePredicate;
import net.minecraft.advancements.criterion.EntityPredicate;
import net.minecraft.advancements.criterion.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Base for the "player finished a trim shaped like X" criteria. The trim shape can't be expressed by a
 * data-only item predicate, so the trim-producing paths (Glint Table print, color/layer-adding crafts) test
 * the shape in Java and call {@link #trigger(ServerPlayer)} on the matching criterion. The instance carries
 * only the optional player predicate. Each subclass is registered under its own id in {@code ModTriggers}, so
 * they fire independently despite sharing this instance type.
 */
public abstract class SimplePlayerTrigger extends SimpleCriterionTrigger<SimplePlayerTrigger.Instance> {

    public record Instance(Optional<ContextAwarePredicate> player) implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player)
        ).apply(i, Instance::new));
    }

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public void trigger(ServerPlayer player) {
        this.trigger(player, instance -> true);
    }
}
