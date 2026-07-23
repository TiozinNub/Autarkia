package dev.luizloyola.autarkia.core.brain;

import dev.luizloyola.autarkia.core.brain.act.ActuatorAccess;
import dev.luizloyola.autarkia.core.brain.sense.Percepts;

/**
 * Everything the task machinery is handed per call: the body's controls ({@link #actuators()}) and
 * the brain's senses ({@link #percepts()}), bundled so one contract serves the whole tree — the
 * executor hands the same context to every call.
 *
 * <p>Assembled per Person by the mod {@code BrainDriver}: fresh views over live state, never a
 * snapshot. Passed into every call rather than held — a task owns intent, never the body.
 */
public interface BrainContext {
    /** The body's controls — one port per actuator domain (legs, gullet, ...). */
    ActuatorAccess actuators();

    /** The brain's senses — what she can currently perceive of herself and the world. */
    Percepts percepts();

    /**
     * The maximum method cost currently acceptable, in the walk-block currency methods price
     * themselves in — the executor treats any applicable method costing more than this as if it
     * were inapplicable. Set by the arbiter from the active instinct's pressure through
     * {@link ToleranceCurve}. {@link Double#POSITIVE_INFINITY} means unbounded: the STARVING
     * plateau, and how manual/debug driving runs.
     */
    double costTolerance();
}
