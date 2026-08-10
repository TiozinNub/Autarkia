package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.sense.Pos;

/**
 * A durable name for one {@link dev.luizloyola.anima.core.brain.board.WorkItem} — what a shared
 * board needs and a personal board never did.
 *
 * <p>Items are exhaust, regenerated from project state, so {@link Board} leases them from an
 * {@code IdentityHashMap}: two errands describing themselves identically are still two errands. That
 * identity means nothing across a restart, and re-offering an item costs a settler the job they were
 * walking to. A personal board dodged it with one slot and one member ({@code KeepStocked.State}).
 *
 * <p><b>Every key is a place</b>: a survey errand is named by its slice's corner, a clearing errand
 * by the anchor of the thing to remove — which is also the site claim the task takes.
 *
 * @param flavour which sort of errand this is within its project, so two items about the same
 *                place (survey this corner / fell the tree standing on it) never collide
 * @param at      the place that names it
 */
public record WorkKey(String flavour, Pos at) {

    /** Walking a slice of a box and reporting what is in it. */
    public static final String SURVEY = "survey";

    /** Removing one reported thing. */
    public static final String CLEAR = "clear";
}
