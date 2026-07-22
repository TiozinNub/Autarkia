package dev.luizloyola.autarkia.core.brain.task;

import java.util.List;

/**
 * The goal "she is hungry" as something to ACHIEVE, with no opinion about how. Today the only
 * way is {@link EatFromInventory}; the methods LIST below is the extension point (see
 * {@link CompoundTask}). All methods failing bubbles a root FAILED.
 */
public final class SatisfyHunger implements CompoundTask {
    private final List<Method> methods = List.of(new EatFromInventory());

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "satisfy hunger";
    }
}
