package dev.luizloyola.autarkia.core.brain.instinct;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.task.SatisfyHunger;
import dev.luizloyola.autarkia.core.brain.task.Task;

/**
 * The Eat drive: its {@code pressure} is the body's hunger ({@code 1 - food/20}, from
 * {@link dev.luizloyola.autarkia.core.person.Needs#hunger()}) on the same {@code 0.30 / 0.60 / 0.85}
 * bands {@link dev.luizloyola.autarkia.core.brain.ToleranceCurve} reads — peckish waits for a task
 * boundary, hungry preempts, starving lifts the cost cap. The root is a fresh
 * {@link SatisfyHunger}, re-granted after each meal.
 */
public final class EatInstinct implements Instinct {

    @Override
    public double pressure(BrainContext ctx) {
        return ctx.percepts().needs().hunger();
    }

    @Override
    public Task root(BrainContext ctx) {
        return new SatisfyHunger();
    }

    @Override
    public String describe() {
        return "eat";
    }
}
