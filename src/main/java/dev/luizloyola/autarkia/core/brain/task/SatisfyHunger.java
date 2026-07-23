package dev.luizloyola.autarkia.core.brain.task;

import java.util.List;

/**
 * The goal "she is hungry" as something to ACHIEVE, with no opinion about how. The ways are the
 * two carried-food tiers, {@link EatReadyFood} (free) and {@link EatLastResort}
 * (desperation-priced): cheapest-wins prefers ready food when any is in hand, and the arbiter's
 * cost tolerance decides whether hunger can afford the last resort. The methods LIST below is
 * the extension point (see {@link CompoundTask}). All methods failing, or all priced out,
 * bubbles a root FAILED.
 */
public final class SatisfyHunger implements CompoundTask {
    private final List<Method> methods = List.of(new EatReadyFood(), new EatLastResort());

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "satisfy hunger";
    }
}
