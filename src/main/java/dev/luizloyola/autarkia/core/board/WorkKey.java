package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
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
 * <p>A key names whatever survives a reload well enough to be found again: {@link AtPlace} for an
 * errand about somewhere — a survey slice's corner, a clearing's anchor, which is also the site
 * claim the task takes — and {@link ForMember} for one about nobody in particular, like a gathering
 * trip, which only the member who took it can be handed back.
 *
 * @param flavour which sort of errand this is within its project, so two items about the same
 *                subject (survey this corner / fell the tree standing on it) never collide
 */
public sealed interface WorkKey permits WorkKey.AtPlace, WorkKey.ForMember {

    String flavour();

    /** Walking a slice of a box and reporting what is in it. */
    String SURVEY = "survey";

    /** Removing one reported thing. */
    String CLEAR = "clear";

    /** Fetching items toward a quota nobody else is credited for. */
    String GATHER = "gather";

    /** @param at the place that names it */
    record AtPlace(String flavour, Pos at) implements WorkKey {
    }

    /** @param who the member whose trip this is */
    record ForMember(String flavour, AgentId who) implements WorkKey {
    }
}
