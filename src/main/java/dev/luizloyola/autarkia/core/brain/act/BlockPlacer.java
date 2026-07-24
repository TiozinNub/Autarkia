package dev.luizloyola.autarkia.core.brain.act;

import dev.luizloyola.autarkia.core.brain.sense.Pos;

/**
 * The place actuator port — the working arm's other verb. Placing is instantaneous in vanilla, so
 * this port is a one-shot rather than a begin/state/abort lifecycle: one call either places and
 * returns {@code true} (block set for real, place sound, arm swing, one item of {@code itemId}
 * consumed from the CARRIED INVENTORY — the source of truth, the equipment mirror follows) or
 * refuses and returns {@code false} — nothing carried, target occupied, the block can't survive
 * there, out of reach — with nothing changed.
 */
public interface BlockPlacer {
    /** Place one {@code itemId} block at the cell; true exactly when the world changed. */
    boolean place(String itemId, Pos target);
}
