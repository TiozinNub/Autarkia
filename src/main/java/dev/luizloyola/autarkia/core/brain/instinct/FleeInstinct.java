package dev.luizloyola.autarkia.core.brain.instinct;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.sense.Threat;
import dev.luizloyola.autarkia.core.brain.task.FleeStep;
import dev.luizloyola.autarkia.core.brain.task.Task;
import dev.luizloyola.autarkia.core.config.Config;
import dev.luizloyola.autarkia.core.config.Knob;
import java.util.random.RandomGenerator;

/**
 * The emergency drive — the deferred item at the nav/brain boundary (pathfinder design doc). Its
 * pressure is the strongest currently-felt danger, read straight from {@link dev.luizloyola.autarkia.core.brain.sense.Percepts#threats()};
 * its root is a fresh {@link FleeStep}, re-granted while the pressure stays on top so the legs are
 * re-aimed as threats move (see {@link FleeStep}'s doc).
 *
 * <p><b>Pressure.</b> Per threat, a linear ramp from {@link #range()} blocks (none) to contact (full)
 * over the last {@link #ramp()} blocks, times {@link #TARGETING_BONUS} when the threat is actively
 * hunting her ({@link Threat#targetingMe()}), capped at {@code 1.0}; the overall pressure is the MAX
 * across sensed threats — danger does not stack, no threats → {@code 0.0}. At the default range
 * and ramp a passive mob crosses the arbiter's {@code PREEMPT} line (0.6) at about 8.8 blocks, one
 * hunting her at about 10.5. A threat outside {@link #range()} can still lose the bid to a starving
 * {@code Eat} — she wolfs the bread, then runs; that is intended.
 *
 * <p><b>The emergency {@link #failCooldown()}.</b> Ten ticks, not the {@link
 * Instinct#DEFAULT_FAIL_COOLDOWN 100} every other drive sits out: a cornered Person must retry at
 * once with a freshly-rolled direction rather than stand still being eaten.
 */
public final class FleeInstinct implements Instinct {

    /** Beyond this straight-line distance a threat exerts no pressure at all. */
    public static double range() {
        return Config.get().d(Knob.FLEE_RANGE);
    }

    /** Pressure ramps linearly to full over this many blocks, ending at {@link #range()}. */
    public static double ramp() {
        return Config.get().d(Knob.FLEE_RAMP);
    }

    /** Multiplier applied when a threat is actively hunting her, not merely nearby; capped at {@code 1.0}. */
    public static final double TARGETING_BONUS = 1.3;

    /** The emergency override of {@link Instinct#failCooldown()} — retry almost immediately. */
    public static final int FAIL_COOLDOWN = 10;

    private final RandomGenerator random;

    public FleeInstinct(RandomGenerator random) {
        this.random = random;
    }

    @Override
    public double pressure(BrainContext ctx) {
        double max = 0.0;
        double range = range();
        double ramp = ramp();
        for (Threat threat : ctx.percepts().threats()) {
            double ramped = clamp01((range - threat.distance()) / ramp);
            double pressure = threat.targetingMe() ? Math.min(1.0, ramped * TARGETING_BONUS) : ramped;
            if (pressure > max) {
                max = pressure;
            }
        }
        return max;
    }

    @Override
    public Task root(BrainContext ctx) {
        return new FleeStep(random);
    }

    @Override
    public int failCooldown() {
        return FAIL_COOLDOWN;
    }

    @Override
    public String describe() {
        return "flee";
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
