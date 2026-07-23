package dev.luizloyola.autarkia.core.brain.instinct;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.task.SatisfyHunger;
import dev.luizloyola.autarkia.core.brain.task.Task;

/**
 * The Eat drive — the brain design doc's canonical worked example, wired as a layer-1 instinct.
 * Its {@code pressure} is the body's hunger ({@code 1 - food/20}, from {@link
 * dev.luizloyola.autarkia.core.person.Needs#hunger()}), on the {@code 0.30 / 0.60 / 0.85} bands
 * {@link dev.luizloyola.autarkia.core.brain.ToleranceCurve} reads: peckish bids low and waits for
 * a task boundary, hungry bids high enough to preempt, starving lifts the cost cap. Its root is a
 * fresh {@link SatisfyHunger}, re-granted after each meal.
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
