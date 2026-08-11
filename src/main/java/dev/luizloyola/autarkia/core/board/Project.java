package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import java.util.List;

/**
 * Layer 3 proper: a <b>durable decomposer</b> owned by a {@link Board}. It holds a goal, keeps
 * transient {@link WorkItem work items} on offer, reacts to their outcomes, and knows when it is
 * satisfied.
 *
 * <p>A work item is <em>exhaust</em>, regenerable from project state at any moment, so
 * nothing persists it (the world-is-the-cursor rule, one layer up). The project is what lasts, and
 * it survives every worker who touches it, including all of them dying.
 *
 * <p>The cadence is not here: a personal project reads the owner's pack ({@link PersonalProject})
 * while a party project is entity-free and ticks in the server-side host.
 *
 * <p>Autarkia's, not Anima's, by the Fidelia test: a pack of pets is a party, but it never composes
 * a board or decomposes a goal.
 */
public interface Project {

    /** The demand bid every item this project mints carries — board policy on the 0..1 scale. */
    double priority();

    /**
     * The items currently on offer, in no particular order — the board filters out whatever is
     * already claimed and scores the rest per asker. Called often (every ask); return a view of
     * state the project already holds rather than building one.
     */
    List<WorkItem> open();

    /**
     * Whether the goal has been met and this project should be closed and dropped by its board.
     * Context-free on purpose: a goal that cannot be judged without somebody's eyes was never the
     * group's. A <em>standing</em> project describes a condition and always answers {@code false}.
     */
    boolean finished();

    /**
     * The board keeps the authoritative record of who holds what — this is only so a project that
     * cares can stop treating the item as free, which a standing project must: withdrawing an
     * errand out from under the agent already walking to it is not a project's to do.
     */
    default void claimed(WorkItem item) {
    }

    /** An item's root SUCCEEDED, worked by the agent whose {@code ctx} this is. */
    void completed(WorkItem item, BrainContext ctx);

    /** An item's root FAILED. Pacing the retry is the project's business, not the arbiter's. */
    void failed(WorkItem item, BrainContext ctx);

    /**
     * The same failure, with the worker it came from — for projects that treat a failure as EVIDENCE
     * about the errand rather than as a setback.
     *
     * <p>Who failed separates "this cannot be done" from "this body could not do it": one stuck
     * worker charged 134 perfectly fellable trees to the trees themselves (live, 2026-08-11). Count
     * per WORKER, so giving up means several people agreed.
     */
    default void failed(WorkItem item, AgentId who, BrainContext ctx) {
        failed(item, ctx);
    }

    /**
     * A claim on one of this project's items lapsed — the worker suspended it and never came back,
     * or died. The item is already back in the pool, so saying nothing is the right default: its
     * return to the pool is the correction.
     */
    default void lapsed(WorkItem item) {
    }

    /** One line for the board readout: what this project wants and how far along it is. */
    String describe();
}
