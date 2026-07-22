package dev.luizloyola.autarkia.core.brain.act;

/**
 * The consume actuator port — the gullet: core asks ("eat what is in that slot") and the mod layer
 * implements it over vanilla item-use, ~32 ticks with chew animation and sound, nutrition applied,
 * stack decremented. The only way a task feeds the body; nothing in {@code core} sees the vanilla
 * item-use machinery.
 *
 * <p>Same lifecycle as {@link Mover}: from the tick after a successful {@link #begin},
 * {@link #state()} reports that bite's progress, so an observed {@link ConsumeState#IDLE} after a
 * successful begin can only mean the consumption was stopped out from under the task.
 */
public interface ItemConsumer {
    /**
     * Start consuming the stack in this inventory slot (the core 41-slot indexing), replacing any
     * consumption already in progress. Returns {@code false} when there is nothing to eat there —
     * the slot is empty or its stack is not consumable — in which case nothing was started.
     */
    boolean begin(int slot);

    /**
     * Progress of the most recent {@link #begin}; {@link ConsumeState#IDLE} when there is none.
     * {@link ConsumeState#FINISHED} means consumption completed and the body applied the
     * nutrition — the port owns the whole transaction, the task only observes it.
     */
    ConsumeState state();

    /**
     * Stop mid-chew, dropping the bite (no nutrition, stack kept); {@link #state()} returns to
     * {@link ConsumeState#IDLE}. Safe when idle — tasks cancel unconditionally and their cancel
     * must be idempotent, so the slack is absorbed here, exactly like {@link Mover#stop}.
     */
    void abort();
}
