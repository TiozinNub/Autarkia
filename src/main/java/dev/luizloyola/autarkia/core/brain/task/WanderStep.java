package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.log.Category;
import dev.luizloyola.autarkia.core.nav.Gait;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * One beat of a wander: usually just stand around; sometimes amble somewhere nearby. A
 * {@link CompoundTask} rather than a bare primitive so the roll happens at DECOMPOSE time against
 * fresh percepts — offset from where they ACTUALLY stand when the executor reaches this node.
 *
 * <p>The roll (draw order is part of the test contract: walk-roll, then pause, then target): with
 * probability {@link #WALK_CHANCE} a random {@code (dx, dz)} each uniform in
 * {@code [-radius, radius]}, re-rolled while both are zero, {@code y} unchanged, decomposing to
 * {@code [GoTo(target, STROLL), Idle(pause)]}; otherwise just {@code [Idle(pause)]}. Pauses run
 * {@code IDLE_MIN + [0, IDLE_RANGE)} ticks either way.
 *
 * <p>Tuned down deliberately (Luiz): idling is the default, walking the exception, the walk a
 * {@link Gait#STROLL}.
 *
 * <p><b>Failure self-heals.</b> An unreachable roll fails the {@link GoTo} and so the step; the
 * arbiter re-grants wander and a fresh {@link WanderStep} rolls a new target. The grant loop is the
 * retry — re-plan on surprise, never patch mid-plan.
 */
public final class WanderStep implements CompoundTask {
    /** Fraction of wander beats that actually go anywhere; the rest just stand. */
    public static final double WALK_CHANCE = 0.3;
    /** Minimum pause per beat, ticks (5 s) — unhurried by construction. */
    public static final int IDLE_MIN = 100;
    /** Random extra pause, ticks ({@code [0, 200)} on top of the minimum — up to 15 s total). */
    public static final int IDLE_RANGE = 200;

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

    /** The one way to wander: mostly stand about; occasionally stroll somewhere nearby. */
    private final class Roam implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true; // there is always somewhere to drift to — or nowhere, which is also fine
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0; // idling is free — it is the floor the whole cost economy sits on
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            boolean walks = random.nextDouble() < WALK_CHANCE;
            int pause = IDLE_MIN + random.nextInt(IDLE_RANGE);
            if (!walks) {
                return List.of(new Idle(pause));
            }
            Pos here = ctx.percepts().position();
            int dx;
            int dz;
            do {
                dx = random.nextInt(2 * radius + 1) - radius;
                dz = random.nextInt(2 * radius + 1) - radius;
            } while (dx == 0 && dz == 0);
            int tx = here.x() + dx;
            int ty = here.y();
            int tz = here.z() + dz;
            // BRAIN log: the "wander (10, 10, 10) - start" line — the drive plus the spot picked,
            // written on commitment to walking there (the pathfind line for that cell follows).
            ctx.journal().record(Category.BRAIN, "wander (" + tx + ", " + ty + ", " + tz + ")", "start");
            return List.of(new GoTo(tx, ty, tz, Gait.STROLL), new Idle(pause));
        }

        @Override
        public String describe() {
            return "roam";
        }
    }
}
