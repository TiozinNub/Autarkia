package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.act.ConsumeState;

/**
 * The second primitive: eat what is in an inventory slot — the thinnest wrapper over the
 * {@link dev.luizloyola.autarkia.core.brain.act.ItemConsumer} port, which owns the whole
 * transaction (slot shuffle, vanilla item-use, nutrition applied, stack decremented); this task
 * only starts it and translates its lifecycle into {@link TaskStatus}.
 *
 * <p><b>First-tick semantic — the {@link GoTo} rule, with one refinement.</b> The first tick issues
 * {@code begin(slot)} and does not read {@code state()} (see GoTo's class doc: reading on the
 * issuing tick would bind the task to whether the port transitions synchronously). The refinement:
 * {@code begin} answers synchronously whether there was anything to eat, so {@code false} is an
 * immediate first-tick FAILED.
 *
 * <p>An unexpected IDLE maps to FAILED: the task cannot claim a meal it did not finish.
 */
public final class ConsumeItem implements PrimitiveTask {
    private final int slot;
    private boolean issued;

    /** @param slot the core inventory slot (41-slot indexing) whose stack to consume */
    public ConsumeItem(int slot) {
        this.slot = slot;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        if (!issued) {
            issued = true;
            // See class doc: issue, don't read — but begin's own answer is this tick's truth.
            return ctx.actuators().consumer().begin(slot) ? TaskStatus.RUNNING : TaskStatus.FAILED;
        }
        ConsumeState state = ctx.actuators().consumer().state();
        switch (state) {
            case CONSUMING:
                return TaskStatus.RUNNING;
            case FINISHED:
                return TaskStatus.SUCCESS;
            case FAILED:
            case IDLE:
            default:
                // FAILED: the bite died. IDLE: someone else stopped the gullet — the task
                // cannot claim a meal it did not finish.
                return TaskStatus.FAILED;
        }
    }

    @Override
    public void cancel(BrainContext ctx) {
        // Idempotent because ItemConsumer.abort() is safe when idle (see its doc) — before the
        // first tick, after FINISHED, or twice in a row.
        ctx.actuators().consumer().abort();
    }

    @Override
    public String describe() {
        return "consume slot " + slot;
    }
}
