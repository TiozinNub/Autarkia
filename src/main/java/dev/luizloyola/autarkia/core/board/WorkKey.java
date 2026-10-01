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

    /** Putting a station down at a base. */
    String SET_UP = "set_up";

    /** A scout's step in the search for a HOME. */
    String EXPLORE = "explore";

    /** Keeping with the scout of a search for a HOME. */
    String ACCOMPANY = "accompany";

    /** Taking a job's load home once the job has nothing left for that member. */
    String BRING_IN = "bring_in";

    /** Coming back to a place where a process fell due. */
    String TEND = "tend";

    /** Loading a furnace. */
    String FIRE = "fire";

    /** Cutting one patch of a flatten at one layer. */
    String CUT = "cut";

    /** Filling one patch of a flatten at one layer. */
    String FILL = "fill";

    /** Pulling up the plants on one strip of a box. */
    String CLEAR_PLANTS = "clear_plants";

    /** Placing one run of a building's proved order. */
    String BUILD = "build";

    /** Taking one block down, a container emptied first. */
    String DECONSTRUCT = "deconstruct";

    /** @param at the place that names it */
    record AtPlace(String flavour, Pos at) implements WorkKey {
    }

    /** @param who the member whose trip this is */
    record ForMember(String flavour, AgentId who) implements WorkKey {
    }
}
