package dev.luizloyola.autarkia.core.brain.act;

/**
 * The movement actuator port: core defines the interface in terms of what an NPC needs ("walk to
 * that cell") and the mod layer implements it over the Navigator. The only way a task moves the
 * body; nothing in {@code core} ever sees the Navigator itself.
 *
 * <p>Primitive tasks call it from {@code TaskExecutor.tick}, which the mod {@code BrainDriver} runs
 * from {@code serverAiStep} before the Navigator ticks — so a {@link #moveTo} issued this tick is
 * acted on this same tick, with no dead tick in between.
 */
public interface Mover {
    /**
     * Begin navigating to the given cell, replacing any move already in progress — the newest
     * order always wins, there is no queue. From the next tick on, {@link #state()} reports this
     * order's progress.
     */
    void moveTo(int x, int y, int z);

    /** Progress of the most recent order; {@link MoveState#IDLE} when there is none. */
    MoveState state();

    /**
     * Abandon the move in progress; {@link #state()} returns to {@link MoveState#IDLE}. Calling
     * this with no move in progress is a harmless no-op — tasks cancel unconditionally, and their
     * cancel must be idempotent, so the slack is absorbed here rather than guarded at every call
     * site.
     */
    void stop();
}
