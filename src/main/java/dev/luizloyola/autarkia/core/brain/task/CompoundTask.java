package dev.luizloyola.autarkia.core.brain.task;

import java.util.List;

/**
 * A goal: something to achieve, not something to do. A compound never touches actuators — it
 * only names its alternatives ({@link #methods()}); the {@link TaskExecutor} picks the cheapest
 * applicable one on reaching this node (lazy expansion) and falls back to the next when a choice
 * fails (the one failure rule). See the brain design doc's task-machinery section.
 *
 * <p>The methods LIST is the extension point: {@code SatisfyHunger} gains containers, harvesting
 * and hunting by ADDING methods here, leaving the compound, the executor and every existing
 * method untouched — behavior grows combinatorially, code linearly.
 */
public non-sealed interface CompoundTask extends Task {
    /**
     * The fixed alternatives for achieving this goal. Fixed means the LIST does not depend on the
     * world — which entries are usable right now ({@link Method#applicable}) and what each would
     * cost ({@link Method#estimateCost}) are decided at expansion time, against fresh percepts.
     */
    List<Method> methods();

    /**
     * One-line goal summary for the debug readout, e.g. {@code "satisfy hunger"} — chained by
     * {@link TaskExecutor#describe()} into the expansion path.
     */
    String describe();
}
