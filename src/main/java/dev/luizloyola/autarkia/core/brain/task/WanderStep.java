package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * One leg of a wander: roll a nearby cell, walk there, pause. A {@link CompoundTask} with a single
 * always-applicable, cost-{@code 0} method, so the roll happens at DECOMPOSE time against fresh
 * percepts — offset from where she actually stands when the executor reaches this node.
 *
 * <p>The roll: {@code (dx, dz)} each uniform in {@code [-radius, radius]}, re-rolled while both are
 * zero (a step always goes somewhere); {@code y} unchanged; a pause of {@code 40 + [0, 80)} ticks.
 * Decomposes to {@code [GoTo(target), Idle(pause)]}.
 *
 * <p>Failure self-heals: an unreachable roll FAILS the {@link GoTo}, the method and the step; the
 * arbiter re-grants and a fresh {@link WanderStep} rolls again. The grant loop is the retry.
 */
public final class WanderStep implements CompoundTask {
    private final RandomGenerator random;
    private final int radius;
    private final List<Method> methods;

    /**
     * @param random the shared per-Person RNG (core-pure {@link RandomGenerator}); advancing it here
     *               is what makes the wander stream continuous across steps
     * @param radius half-width of the roam box in whole blocks (positive)
     */
    public WanderStep(RandomGenerator random, int radius) {
        this.random = random;
        this.radius = radius;
        this.methods = List.of(new Roam());
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "wander step";
    }

    private final class Roam implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true; // there is always somewhere to drift to
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0; // idling is free — it is the floor the whole cost economy sits on
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            Pos here = ctx.percepts().position();
            int dx;
            int dz;
            do {
                dx = random.nextInt(2 * radius + 1) - radius;
                dz = random.nextInt(2 * radius + 1) - radius;
            } while (dx == 0 && dz == 0);
            int pause = 40 + random.nextInt(80);
            return List.of(new GoTo(here.x() + dx, here.y(), here.z() + dz), new Idle(pause));
        }

        @Override
        public String describe() {
            return "roam";
        }
    }
}
