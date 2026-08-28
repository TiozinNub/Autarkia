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
     * Whether this project is the one to tell about {@code item}. The default is what the board
     * has always done — an identity scan of what is on offer — and it is not enough for a project
     * that mints an item for one asker at claim time: such an item is not on offer to anybody, so
     * the scan would find no owner and the claim would be lost in silence.
     */
    default boolean owns(WorkItem item) {
        for (WorkItem offered : open()) {
            if (offered == item) {
                return true;
            }
        }
        return false;
    }

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

    /**
     * The same claim, with the body that took it — for a project that mints an item at claim time
     * and has to file it under its claimant. Delegates by default, exactly as {@code failed} does.
     */
    default void claimed(WorkItem item, AgentId who) {
        claimed(item);
    }

    /**
     * Whether this item may go to {@code asker} right now. The board holds the lease and the
     * scoring; only the project knows whether THIS body is the one an item was meant for, is being
     * paced after a failure, or has no room to carry what the item is for. True by default — a
     * project whose items are interchangeable says nothing.
     */
    default boolean offerableTo(WorkItem item, AgentId asker, BrainContext ctx) {
        return true;
    }

    /**
     * The item this asker actually takes, given the offer they won the scan with. The default is
     * the offer itself — a project whose items are already concrete says nothing.
     *
     * <p><b>This MUST be pure.</b> {@code Arbiter} asks on every arbitration tick and frequently
     * does not grant what it is handed: a drive can outbid the item, and the executor can be busy.
     * A {@code realise} that recorded anything would mint state per body per tick and commit almost
     * none of it. Commitment belongs in {@link #claimed(WorkItem, AgentId)}, which the board calls
     * only when a claim actually lands.
     *
     * <p>An item returned that is not in {@link #open()} — this hook's whole reason to exist — must
     * also be recognised by {@link #owns(WorkItem)}: the board finds an item's project only through
     * {@code owns}, so a substitute it does not recognise leaves every later claim, completion,
     * failure and expiry silently unrouted, never told to anybody.
     */
    default WorkItem realise(WorkItem offer, AgentId asker, BrainContext ctx) {
        return offer;
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
    /**
     * What this project wants KEPT in a member's pack, best first — read by the stow machinery so
     * it can tell cargo from kit. Nothing by default: a project that only posts work reserves
     * nothing, and an errand's own {@code Kit} is the arbiter's business rather than a project's.
     */
    default java.util.List<dev.luizloyola.anima.core.inv.ItemCall> reserved() {
        return java.util.List.of();
    }

    String describe();
}
