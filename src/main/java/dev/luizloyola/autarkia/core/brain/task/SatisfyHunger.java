package dev.luizloyola.autarkia.core.brain.task;

import java.util.List;

/**
 * "They are hungry" as something to ACHIEVE, with no opinion about how. Two ways today,
 * {@link EatReadyFood} (free) and {@link EatLastResort} (desperation-priced): cheapest-wins prefers
 * ready food when any is in hand, and the arbiter's cost tolerance decides whether hunger can
 * afford the last resort. The methods list is the extension point ({@link CompoundTask}); all
 * methods failing or priced out bubbles a root FAILED.
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
