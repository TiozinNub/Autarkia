package dev.luizloyola.autarkia.core.brain.instinct;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.task.Task;

/**
 * Layer 1 of the brain — a reactive drive bidding, every tick, to be what she does right now (see
 * the brain design doc): each instinct reports a scalar {@code pressure} the
 * {@link dev.luizloyola.autarkia.core.brain.Arbiter} compares against every other's, and the winner
 * supplies a {@code root} task tree. An instinct never touches actuators and never runs a task — it
 * names a want and its strength; the task layer does the doing.
 *
 * <p>The set is fixed and small (Eat + Wander today; Flee, Fight, Grab-loot, Stock-up later): the
 * arbiter needs nothing from an instinct but these three methods.
 */
public interface Instinct {

    /**
     * How strongly this drive wants to run right now, {@code 0..1} on a shared scale — must be
     * cheap: the arbiter reads it for every instinct every tick. A constant is fine for an ambient
     * default like wander.
     */
    double pressure(BrainContext ctx);

    /**
     * A FRESH task tree to pursue this drive, built anew on every grant — never a cached instance.
     * That freshness is the continuous-behavior loop: the arbiter re-grants the incumbent after a
     * SUCCESS by calling this again.
     */
    Task root(BrainContext ctx);

    /** One-word drive name for the debug readout — {@code "eat"}, {@code "wander"}. */
    String describe();
}
