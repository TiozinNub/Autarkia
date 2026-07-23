package dev.luizloyola.autarkia.core.brain.instinct;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.task.Task;
import dev.luizloyola.autarkia.core.brain.task.WanderStep;
import java.util.random.RandomGenerator;

/**
 * The idle-default drive. Its pressure is a constant {@link #IDLE_PRESSURE} floor: low enough that any real
 * need beats it, high enough to beat doing nothing. Its root is a fresh {@link WanderStep} (roll a
 * nearby spot, walk there, pause), re-granted each time the last one finishes.
 *
 * <p>The RNG is core-pure ({@link RandomGenerator}: the mod hands in a seeded {@code
 * java.util.Random}, not an entity {@code RandomSource}) and is threaded through each
 * {@link WanderStep}, so the wander sequence is one reproducible stream per Person. Placeholder
 * gait; day/night routine and purposeful idling arrive with the {@code rest} need.
 */
public final class WanderInstinct implements Instinct {

    /**
     * The ambient pressure every real drive must beat to take over — low enough that once peckish
     * (0.30) sticks past the arbiter's stickiness it loses to Eat, high enough to beat standing
     * still.
     */
    public static final double IDLE_PRESSURE = 0.15;

    /** Default roam radius (whole blocks) when the caller doesn't specify — a modest saunter. */
    public static final int DEFAULT_RADIUS = 8;

    private final RandomGenerator random;
    private final int radius;

    public WanderInstinct(RandomGenerator random, int radius) {
        this.random = random;
        this.radius = radius;
    }

    /** Wander with the {@link #DEFAULT_RADIUS default radius}. */
    public WanderInstinct(RandomGenerator random) {
        this(random, DEFAULT_RADIUS);
    }

    @Override
    public double pressure(BrainContext ctx) {
        return IDLE_PRESSURE;
    }

    @Override
    public Task root(BrainContext ctx) {
        return new WanderStep(random, radius);
    }

    @Override
    public String describe() {
        return "wander";
    }
}
