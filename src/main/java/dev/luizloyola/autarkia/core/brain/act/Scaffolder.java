package dev.luizloyola.autarkia.core.brain.act;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.List;

/**
 * The climb actuator port — one nerd-pole step per {@link #up} call (jump, place a carried block
 * into the cell just vacated, land on it); the body owns the jump/place timing. Coming down needs
 * no port: the pillar is ordinary blocks, broken underfoot with the {@link BlockBreaker}, drops
 * reclaimed by the walk-over pickup.
 *
 * <p><b>The ledger is the body's, not the task's</b>: a mid-climb suspension cancels the task, and
 * a private deque died with it — tower standing, body stranded on top. {@link #placed()} remembers
 * every cell placed and not reclaimed, so any later task can un-build it, and {@link #up} refuses
 * past {@link #PILLAR_MAX} so the cap cannot be re-budgeted by task churn.
 *
 * <p>After a successful {@link #up}, {@link #state()} reports RISING until the step lands (RISEN)
 * or dies (FAILED); the next {@link #up} or {@link #abort} clears a terminal state.
 */
public interface Scaffolder {
    /**
     * Standing-ledger cap — taller than any vanilla trunk (a mega spruce or jungle giant runs
     * to ~30), because a chop now climbs a whole trunk in its own column and the ledger is
     * that climb. A hard bodily limit, not a per-task budget: while {@link #placed()} holds
     * this many cells, {@link #up} refuses.
     */
    int PILLAR_MAX = 32;

    /**
     * Begin one pillar step using one {@code itemId} block from the carried inventory. Returns
     * {@code false} when the step cannot start — nothing carried, no headroom, a step already in
     * flight, the ledger at {@link #PILLAR_MAX}, or this spot having already killed several steps
     * in a row — and nothing is consumed.
     *
     * <p>That last refusal is the one callers must handle: the body's bounded retries step to the
     * middle of the cell first (a box straddling two columns bonks headroom the cell's own check
     * called clear), so once it refuses, asking from the same spot keeps refusing —
     * <b>treat it as "not from here"</b>. A caller that re-asks every tick spins forever.
     */
    boolean up(String itemId);

    /**
     * The standing ledger, newest cell first: every cell placed by {@link #up} and not yet
     * {@link #reclaim reclaimed}. Cells enter when the block actually lands (a FAILED step
     * ledgers nothing). Survives task instances by design — see the class doc.
     */
    List<Pos> placed();

    /**
     * Strike a cell from the ledger — its block was confirmed gone (broken underfoot on the way
     * down, or found already missing). A cell the ledger doesn't hold is ignored.
     */
    void reclaim(Pos cell);

    /** Progress of the most recent step; IDLE when none. */
    ScaffoldState state();

    /** Abandon the step in flight (mid-air placement doesn't happen). Idempotent. */
    void abort();
}
