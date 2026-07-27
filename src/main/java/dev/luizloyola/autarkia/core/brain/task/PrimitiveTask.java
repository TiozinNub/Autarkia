package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;

/**
 * The executable leaf of the task machinery: a small state machine making one decision per tick
 * against the body's actuator ports, reporting {@link TaskStatus}, cancellable at any moment.
 * Everything an NPC physically does bottoms out in one.
 *
 * <p>Ticked by the {@link TaskExecutor}, which {@code BrainDriver} runs from {@code serverAiStep}
 * Before the Navigator — so actuator orders are acted on the same tick.
 *
 * <p>Calls take a {@link BrainContext} rather than bare actuators: a primitive ACTS through
 * {@code ctx.actuators()} while {@link Method}s PERCEIVE through {@code ctx.percepts()}, one
 * contract serving both. The context is passed per call, never held: a task owns intent, never the
 * body.
 */
public non-sealed interface PrimitiveTask extends Task {
    /**
     * The one-liner for a FAILED ending, surfaced into the journal by the executor. The default names
     * the task; a primitive with distinguishable endings overrides with the actual one.
     */
    default String failureDetail() {
        return describe() + " failed";
    }

    /**
     * One decision per tick. Called every tick until a terminal status is returned, and never
     * after — the executor never ticks a finished task, so implementations need not defend
     * against post-terminal ticks.
     */
    TaskStatus tick(BrainContext ctx);

    /**
     * Release every actuator this task owns (stop movement, abort the bite, ...) so the next task
     * starts from a quiet body. Must be idempotent and safe in any state — before the first tick,
     * mid-run, after cancel — because the executor cancels unconditionally when preempting or
     * clearing.
     */
    void cancel(BrainContext ctx);

    /**
     * One-line summary for the debug readout, surfaced by {@link TaskExecutor#describe()}, e.g.
     * {@code "goto (12, -60, 8)"}.
     */
    String describe();
}
