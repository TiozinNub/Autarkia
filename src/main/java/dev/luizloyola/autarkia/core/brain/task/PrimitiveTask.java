package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.act.ActuatorAccess;

/**
 * The executable leaf of the task machinery — a small state machine that makes one decision per
 * tick against the body's actuator ports, reports {@link TaskStatus}, and can be cancelled at
 * any moment. Compound tasks only ever decompose into them.
 *
 * <p>Ticked by the {@link TaskExecutor}, which the mod {@code BrainDriver} runs from
 * {@code serverAiStep} before the Navigator ticks, so actuator orders issued in a tick are acted
 * on that same tick. {@link ActuatorAccess} is passed into every call rather than held (see its
 * doc).
 */
public interface PrimitiveTask {
    /**
     * One decision per tick. Called every tick until a terminal status is returned, and never
     * after — the executor clears a finished task, so implementations need not defend against
     * post-terminal ticks.
     */
    TaskStatus tick(ActuatorAccess actuators);

    /**
     * Release every actuator this task owns (stop movement, ...) so the next task starts from a
     * quiet body. Must be idempotent and safe in any state — before the first tick, mid-run,
     * after cancel — because the executor cancels unconditionally when preempting or clearing.
     */
    void cancel(ActuatorAccess actuators);

    /**
     * One-line summary for the debug readout — the "why is she doing that?" answer, surfaced by
     * {@link TaskExecutor#describe()}. E.g. {@code "goto (12, -60, 8)"}.
     */
    String describe();
}
