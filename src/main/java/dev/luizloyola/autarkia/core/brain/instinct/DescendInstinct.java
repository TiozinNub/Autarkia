package dev.luizloyola.autarkia.core.brain.instinct;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.task.Task;
import dev.luizloyola.autarkia.core.brain.task.UnbuildPillar;
import dev.luizloyola.autarkia.core.config.Config;
import dev.luizloyola.autarkia.core.config.Knob;

/**
 * The strand-recovery drive: whenever the body's standing ledger holds pillar cells and nothing is
 * actively climbing, they want down — the safety net under every path the chop's own descend-first
 * gates miss.
 *
 * <p>Its pressure sits between mild hunger (0.30) and the preempt bar (0.60), so a real emergency
 * still eats or flees first and it can never preempt a RUNNING task (0.45 &lt; PREEMPT): it only
 * wins at task boundaries.
 *
 * <p>Both ends are configurable ({@code instincts.descend_pressure} and {@code brain.preempt}) and
 * it is the <em>relationship</em> that carries the behavior — push this above the preempt bar and
 * they abandon a legitimate mid-climb chop.
 */
public final class DescendInstinct implements Instinct {

    /** See the class doc for where this sits on the shared pressure scale. */
    public static double strandedPressure() {
        return Config.get().d(Knob.DESCEND_PRESSURE);
    }

    @Override
    public double pressure(BrainContext ctx) {
        return ctx.actuators().scaffolder().placed().isEmpty() ? 0.0 : strandedPressure();
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
