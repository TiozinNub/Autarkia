package dev.luizloyola.autarkia.core.brain.instinct;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.task.Task;
import dev.luizloyola.autarkia.core.brain.task.WanderStep;
import dev.luizloyola.autarkia.core.config.Config;
import dev.luizloyola.autarkia.core.config.Knob;
import java.util.random.RandomGenerator;

/**
 * The idle-default drive: pressure is the constant {@link #idlePressure()} floor, the root a fresh
 * {@link WanderStep} re-granted each time the last one finishes. The RNG is core-pure
 * ({@link RandomGenerator}, not an entity {@code RandomSource}) and threaded through each step, so
 * the wander sequence is one continuous, reproducible stream per Person. A placeholder gait:
 * day/night routine and purposeful idling arrive with the {@code rest} need.
 */
public final class WanderInstinct implements Instinct {

    /**
     * The ambient pressure every real drive must beat to take over — low enough that once peckish
     * (0.30) sticks past the arbiter's stickiness it loses to Eat, high enough to beat doing
     * nothing. Set it to zero and an unbothered Person stands still.
     */
    public static double idlePressure() {
        return Config.get().d(Knob.WANDER_IDLE_PRESSURE);
    }

    /** Default roam radius (whole blocks) when the caller doesn't specify — a modest saunter. */
    public static int defaultRadius() {
        return Config.get().i(Knob.WANDER_RADIUS);
    }

    private final RandomGenerator random;
    /** An explicit caller-pinned radius, or {@code null} to follow {@link #defaultRadius()} live. */
    private final Integer radius;

    public WanderInstinct(RandomGenerator random, int radius) {
        this.random = random;
        this.radius = radius;
    }

    /**
     * Wander with the {@link #defaultRadius() configured radius} — and keep following it, so a
     * {@code /autarkia config reload} re-tunes Persons already walking around rather than only
     * the next ones spawned.
     */
    public WanderInstinct(RandomGenerator random) {
        this.random = random;
        this.radius = null;
    }

    @Override
    public double pressure(BrainContext ctx) {
        return idlePressure();
    }

    @Override
    public Task root(BrainContext ctx) {
        return new WanderStep(random, radius == null ? defaultRadius() : radius);
    }

    @Override
    public String describe() {
        return "wander";
    }
}
