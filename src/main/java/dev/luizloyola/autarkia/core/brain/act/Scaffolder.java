package dev.luizloyola.autarkia.core.brain.act;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.List;

/**
 * The climb actuator port: one nerd-pole step per {@link #up} call — jump, place a carried block
 * into the cell just vacated, land on it — the body owning the jump/place timing as the breaker
 * owns crack timing. Coming down needs no port: the pillar is ordinary blocks, broken underfoot
 * with the {@link BlockBreaker} and reclaimed by walk-over pickup (decision: Luiz — a lumberjack
 * pillars on her own logs).
 *
 * <p><b>The ledger is the body's, not the task's</b>: a mid-climb suspension cancels the task and
 * a private deque dies with it, leaving the tower standing and the body stranded on top.
 * {@link #placed()} remembers every unreclaimed cell so any later task can un-build it, and
 * {@link #up} refuses past {@link #PILLAR_MAX} standing cells, so the cap cannot be re-budgeted by
 * task churn.
 *
 * <p>Lifecycle as the siblings: after a successful {@link #up}, {@link #state()} reports RISING
 * until the step lands (RISEN) or dies (FAILED); the next {@link #up} or {@link #abort} clears it.
 */
public interface Scaffolder {
    /**
     * Standing-ledger cap — taller than any vanilla tree's working need. A hard bodily limit,
     * not a per-task budget: while {@link #placed()} holds this many cells, {@link #up} refuses.
     */
    int PILLAR_MAX = 12;

    /**
     * Begin one pillar step using one {@code itemId} block from the carried inventory.
     * Returns {@code false} when the step cannot start — nothing carried, no headroom, a step
     * already in flight, or the ledger at {@link #PILLAR_MAX} — in which case nothing was
     * consumed.
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
