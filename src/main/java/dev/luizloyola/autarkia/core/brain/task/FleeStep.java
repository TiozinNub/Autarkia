package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.nav.Gait;
import dev.luizloyola.autarkia.core.brain.sense.Threat;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * One leg of a flight: aim away from whatever is pressing in, then run there. A
 * {@link CompoundTask} with a single always-applicable, cost-{@code 0} method, so the roll
 * happens at DECOMPOSE time against fresh percepts, not from the tick the instinct was granted.
 *
 * <p><b>The escape vector.</b> Weight every current threat by {@code 1/distance²} (closest
 * dominates) into a proximity-weighted centroid; the direction is the unit vector from that
 * centroid THROUGH their position, the target that direction times {@link #FLEE_LEG} with
 * independent {@code +/-}{@link #JITTER} on each horizontal axis from the shared
 * {@link RandomGenerator}, so consecutive legs are not ruler-straight; {@code y} is unchanged —
 * the pathfinder finds the ground. No threats at decompose time, or a centroid landing exactly
 * on them (surrounded), fall back to a uniformly random heading of the same length.
 *
 * <p>Decomposes to a single {@code [GoTo(target, Gait.SPRINT)]}, no {@link Idle}: flight does
 * not pause between legs the way a wander does.
 *
 * <p><b>SUCCESS just ends the leg.</b> The arbiter re-grants {@link
 * dev.luizloyola.autarkia.core.brain.instinct.FleeInstinct} while its pressure stays on top, and
 * a fresh {@code FleeStep} re-aims from the CURRENT threat positions, chaining legs as the
 * danger moves. Escape ends by pressure decay (out of {@link
 * dev.luizloyola.autarkia.core.brain.instinct.FleeInstinct#RANGE}), never by a scripted finish;
 * a FAILED leg (cornered against terrain) retries almost immediately ({@link
 * dev.luizloyola.autarkia.core.brain.instinct.FleeInstinct#failCooldown()}) with a fresh roll
 * rather than patching the failed one.
 */
public final class FleeStep implements CompoundTask {

    /** Horizontal length of one escape leg, in blocks, before jitter. */
    public static final double FLEE_LEG = 12.0;

    /** Half-width of the independent per-axis jitter applied to the leg target, in blocks. */
    public static final int JITTER = 2;

    /** Below this squared magnitude the raw escape vector counts as degenerate (surrounded). */
    private static final double DEGENERATE_EPSILON = 1e-6;

    /** Distance floor for the {@code 1/distance²} weighting — guards a threat standing on them. */
    private static final double MIN_WEIGHT_DISTANCE = 0.1;

    private final RandomGenerator random;
    private final List<Method> methods;

    /**
     * @param random the shared per-Person RNG (core-pure {@link RandomGenerator}), threaded
     *               through exactly like {@link WanderStep}'s so the roll stream stays continuous
     */
    public FleeStep(RandomGenerator random) {
        this.random = random;
        this.methods = List.of(new Escape());
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "flee step";
    }

    /** The one way to flee: aim away from the current threats and run there, urgently. */
    private final class Escape implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true; // there is always a direction to run, even with nothing to run from
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0; // survival is free — never priced out by the cost tolerance
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            Pos here = ctx.percepts().position();
            double[] direction = escapeDirection(here, ctx.percepts().threats());
            int jitterX = random.nextInt(2 * JITTER + 1) - JITTER;
            int jitterZ = random.nextInt(2 * JITTER + 1) - JITTER;
            int targetX = here.x() + (int) Math.round(direction[0] * FLEE_LEG) + jitterX;
            int targetZ = here.z() + (int) Math.round(direction[1] * FLEE_LEG) + jitterZ;
            return List.of(new GoTo(targetX, here.y(), targetZ, Gait.SPRINT));
        }

        @Override
        public String describe() {
            return "escape";
        }
    }

    /**
     * The unit direction to run: away from the proximity-weighted threat centroid, or a uniformly
     * random heading when there is nothing to run from (no threats) or nowhere is better than
     * anywhere else (surrounded — the centroid lands on them).
     */
    private double[] escapeDirection(Pos here, List<Threat> threats) {
        if (threats.isEmpty()) {
            return randomDirection();
        }
        double weightSum = 0.0;
        double centroidX = 0.0;
        double centroidZ = 0.0;
        for (Threat threat : threats) {
            double distance = Math.max(threat.distance(), MIN_WEIGHT_DISTANCE);
            double weight = 1.0 / (distance * distance);
            weightSum += weight;
            centroidX += weight * threat.pos().x();
            centroidZ += weight * threat.pos().z();
        }
        centroidX /= weightSum;
        centroidZ /= weightSum;
        double awayX = here.x() - centroidX;
        double awayZ = here.z() - centroidZ;
        double lengthSq = awayX * awayX + awayZ * awayZ;
        if (lengthSq < DEGENERATE_EPSILON) {
            return randomDirection();
        }
        double length = Math.sqrt(lengthSq);
        return new double[] {awayX / length, awayZ / length};
    }

    private double[] randomDirection() {
        double angle = random.nextDouble() * 2.0 * Math.PI;
        return new double[] {Math.cos(angle), Math.sin(angle)};
    }
}
