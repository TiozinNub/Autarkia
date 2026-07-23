package dev.luizloyola.autarkia.core.brain.act;

import dev.luizloyola.autarkia.core.nav.Gait;

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
     * Begin navigating to the given cell at the ordinary pace — shorthand for
     * {@link #moveTo(int, int, int, Gait)} with {@link Gait#WALK}.
     */
    default void moveTo(int x, int y, int z) {
        moveTo(x, y, z, Gait.WALK);
    }

    /**
     * Begin navigating to the given cell, replacing any move already in progress — the newest
     * order always wins, there is no queue. From the next tick on, {@link #state()} reports this
     * order's progress.
     *
     * @param gait the requested pace (see {@link Gait}): a flee leg orders {@link Gait#SPRINT},
     *             a wander leg {@link Gait#STROLL}, everything else {@link Gait#WALK}. The mod's
     *             Navigator decides where each gait actually applies (terrain overrides mood);
     *             the port stays advisory, never a guarantee.
     */
    void moveTo(int x, int y, int z, Gait gait);

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
