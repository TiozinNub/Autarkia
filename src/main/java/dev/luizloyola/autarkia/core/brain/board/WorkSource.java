package dev.luizloyola.autarkia.core.brain.board;

import java.util.Optional;

/**
 * The board's face toward one person's arbiter: what work is on offer, and the three lifecycle
 * reports. For a loner this is her {@link PersonalBoard}; when groups arrive the same four calls
 * front the shared claim board, with claim scoring, contention and timed release BEHIND this seam.
 */
public interface WorkSource {
    /** The best unclaimed, off-cooldown item on offer right now, if any. */
    Optional<WorkItem> bestAvailable();

    /** The arbiter took the item — it is no longer on offer to anyone else. */
    void claim(WorkItem item);

    /** The item's goal was achieved; the board retires it (and re-posts later if the
     *  underlying want re-opens). */
    void complete(WorkItem item);

    /** The item's goal is currently out of reach (the root FAILED); the board unclaims it and
     *  cools it down so the retry is paced, not a spin. */
    void fail(WorkItem item);
}
