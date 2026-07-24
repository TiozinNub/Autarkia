package dev.luizloyola.autarkia.core.brain.instinct;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.task.Task;
import dev.luizloyola.autarkia.core.brain.task.UnbuildPillar;

/**
 * The strand-recovery drive: whenever the standing ledger holds pillar cells and nothing is
 * climbing, she wants down — the safety net under every path the chop's own descend-first gates do
 * not cover (a task cancelled mid-climb, a flee off the pole, a dev command).
 *
 * <p>Its pressure sits between mild hunger (0.30) and the preempt bar (0.60): it outranks drifting
 * and a peckish stomach, a real emergency still eats or flees first, and it can never preempt a
 * RUNNING task (0.45 < PREEMPT) — a chop mid-climb is never interrupted, so the instinct only
 * wins at task boundaries.
 */
public final class DescendInstinct implements Instinct {

    /** See the class doc for where this sits on the shared pressure scale. */
    public static final double PRESSURE = 0.45;

    @Override
    public double pressure(BrainContext ctx) {
        return ctx.actuators().scaffolder().placed().isEmpty() ? 0.0 : PRESSURE;
    }

    @Override
    public Task root(BrainContext ctx) {
        return new UnbuildPillar();
    }

    @Override
    public String describe() {
        return "descend";
    }
}
