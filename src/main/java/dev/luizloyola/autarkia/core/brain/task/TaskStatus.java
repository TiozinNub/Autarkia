package dev.luizloyola.autarkia.core.brain.task;

/**
 * What one tick of a {@link PrimitiveTask} concluded. The {@link TaskExecutor} clears and remembers
 * on a terminal status; {@link #FAILED} is what bubbles, failing its parent method and forcing a
 * re-resolve.
 */
public enum TaskStatus {
    /** Still working — tick again next tick. */
    RUNNING,
    /** Terminal: the task achieved what it set out to do. */
    SUCCESS,
    /** Terminal: the task cannot achieve it — the caller must decide what happens next. */
    FAILED
}
