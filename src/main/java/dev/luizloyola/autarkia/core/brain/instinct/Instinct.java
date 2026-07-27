package dev.luizloyola.autarkia.core.brain.instinct;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.task.Task;

/**
 * Layer 1 of the brain — a reactive drive that bids, every tick, to be what they do right now: each
 * instinct reports a scalar {@code pressure} the {@link dev.luizloyola.autarkia.core.brain.Arbiter}
 * compares against every other's, and the winner supplies a {@code root} task tree for the executor
 * to run. An instinct never touches actuators and never runs a task itself — it names a want and
 * how strongly it wants it; the task layer does the doing.
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

    /**
     * Ticks this instinct sits out after its root FAILED. Most drives use the default; an
     * emergency drive (Flee) overrides it small — a cornered Person must retry immediately, not
     * stand still being eaten.
     */
    default int failCooldown() {
        return DEFAULT_FAIL_COOLDOWN;
    }

    /**
     * The default {@link #failCooldown()} — anti fail-spin: a drive that cannot currently be
     * satisfied (no food anywhere) must not monopolize the wheel re-failing every tick while lower
     * drives starve. A fixed cooldown gives the runner-up a turn.
     */
    int DEFAULT_FAIL_COOLDOWN = 100;
}
