package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;

/**
 * The do-nothing primitive: hold for a fixed number of ticks, then SUCCESS. Touches no actuator —
 * pure elapsed time, the pause between wander legs (see {@link WanderStep}). Owning nothing,
 * {@link #cancel} is a genuine no-op: a preempting task can drop an Idle with no cleanup.
 *
 * <p>The first {@code ticks} ticks report RUNNING and the next reports SUCCESS, so
 * {@code new Idle(40)} completes on the 41st tick; {@code new Idle(0)} succeeds on its first.
 */
public final class Idle implements PrimitiveTask {
    private final int ticks;
    private int remaining;

    /** @param ticks how many ticks to report RUNNING before SUCCESS (must be &ge; 0) */
    public Idle(int ticks) {
        this.ticks = ticks;
        this.remaining = ticks;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        if (remaining > 0) {
            remaining--;
            return TaskStatus.RUNNING;
        }
        return TaskStatus.SUCCESS;
    }

    @Override
    public void cancel(BrainContext ctx) {
        // Nothing to release — Idle holds no actuator. Safe in any state, any number of times.
    }

    @Override
    public String describe() {
        return "idle " + ticks + "t";
    }
}
