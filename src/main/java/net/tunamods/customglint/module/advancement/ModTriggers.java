package net.tunamods.customglint.module.advancement;

import net.minecraft.advancements.CriteriaTriggers;

/**
 * Full-jar (module) advancement criterion triggers. Standalone-only, like the rest of {@code module/}.
 *
 * <p>Forge 1.20.1 has no {@code Registries.TRIGGER_TYPE} DeferredRegister (that's 1.20.2+); custom triggers
 * are registered straight into {@link CriteriaTriggers} (publicized via the mod access transformer). Call
 * {@link #register()} once during common setup.
 */
public final class ModTriggers {
    private ModTriggers() {}

    public static EightColorTrimTrigger EIGHT_COLOR_TRIM;
    public static LayeredTrimTrigger LAYERED_TRIM;
    public static EightLayerTrimTrigger EIGHT_LAYER_TRIM;
    public static EightByEightTrimTrigger EIGHT_BY_EIGHT_TRIM;
    public static DesignsCollectedTrigger DESIGNS_COLLECTED;

    public static void register() {
        EIGHT_COLOR_TRIM    = CriteriaTriggers.register(new EightColorTrimTrigger());
        LAYERED_TRIM        = CriteriaTriggers.register(new LayeredTrimTrigger());
        EIGHT_LAYER_TRIM    = CriteriaTriggers.register(new EightLayerTrimTrigger());
        EIGHT_BY_EIGHT_TRIM = CriteriaTriggers.register(new EightByEightTrimTrigger());
        DESIGNS_COLLECTED   = CriteriaTriggers.register(new DesignsCollectedTrigger());
    }
}
