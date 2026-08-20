package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.board.WorkLease;
import dev.luizloyola.anima.core.brain.board.WorkSource;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.brain.instinct.Instinct;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The one {@link WorkSource} an agent's brain actually holds: its personal board and its party's,
 * reached as if they were one.
 *
 * <p><b>Compose, don't merge</b> (decision: Luiz). The two stay separate stores with separate
 * scopes; the composition happens here, at the ask, so the arbiter sees one offer on one scale.
 *
 * <p>Which side an item came from is remembered, not re-derived: the arbiter can hold a claimed item
 * across many ticks before reporting its outcome, and a project can withdraw the last offer while
 * the worker is still walking to it.
 */
public final class ComposedBoards implements WorkSource {

    private final WorkSource personal;
    /**
     * The party side, asked for on every use rather than held: membership can change under an
     * agent (they join, they leave, they are the last one out and their party disbands), and the
     * board that follows from it must change with it. The supplier is the caller's chance to
     * resolve (and cache) that lookup however cheaply it can.
     */
    private final Supplier<WorkSource> party;

    /** Which side offered an item, so its outcome goes back where it came from. */
    private final Map<WorkItem, WorkSource> offeredBy = new IdentityHashMap<>();

    public ComposedBoards(WorkSource personal, Supplier<WorkSource> party) {
        this.personal = personal;
        this.party = party;
    }

    /** A composite with no party side — a body whose owner never gave it one. */
    public ComposedBoards(WorkSource personal) {
        this(personal, () -> WorkSource.NONE);
    }

    /**
     * The better of the two boards' best offers, on the same {@code priority − estimatedCost} scale
     * each board scores with internally. A tie goes to the personal side: what an agent needs for
     * itself outranks an equally-priced errand.
     */
    @Override
    public Optional<WorkItem> bestAvailable(BrainContext ctx) {
        WorkSource other = party.get();
        Optional<WorkItem> mine = personal.bestAvailable(ctx);
        Optional<WorkItem> theirs = other.bestAvailable(ctx);
        WorkItem best;
        WorkSource from;
        if (mine.isPresent() && theirs.isPresent()) {
            boolean personalWins = score(mine.get(), ctx) >= score(theirs.get(), ctx);
            best = personalWins ? mine.get() : theirs.get();
            from = personalWins ? personal : other;
        } else if (mine.isPresent()) {
            best = mine.get();
            from = personal;
        } else if (theirs.isPresent()) {
            best = theirs.get();
            from = other;
        } else {
            return Optional.empty();
        }
        offeredBy.put(best, from);
        return Optional.of(best);
    }

    @Override
    public void claimed(WorkItem item, BrainContext ctx) {
        sourceOf(item).claimed(item, ctx);
    }

    @Override
    public void completed(WorkItem item, BrainContext ctx) {
        WorkSource from = sourceOf(item);
        offeredBy.remove(item); // terminal: the offer is spent either way
        from.completed(item, ctx);
    }

    @Override
    public void failed(WorkItem item, BrainContext ctx) {
        WorkSource from = sourceOf(item);
        offeredBy.remove(item);
        from.failed(item, ctx);
    }

    /**
     * Both boards hear it. Dormant today (see {@link WorkSource#driveFailed}); when it wakes, a
     * body that cannot feed itself is news to its party as much as to itself, and neither side
     * knows yet which of them should answer.
     */
    @Override
    public void driveFailed(Instinct instinct, String detail, BrainContext ctx) {
        personal.driveFailed(instinct, detail, ctx);
        party.get().driveFailed(instinct, detail, ctx);
    }

    /** Routed, not broadcast: a heartbeat is an answer about one errand on one board. */
    @Override
    public void heartbeat(WorkItem item, BrainContext ctx) {
        sourceOf(item).heartbeat(item, ctx);
    }

    /** Likewise the question — only the board that lent the errand out can say whose it is. */
    @Override
    public boolean stillMine(WorkItem item, BrainContext ctx) {
        return sourceOf(item).stillMine(item, ctx);
    }

    @Override
    public List<ItemCall> reserved(BrainContext ctx) {
        // Personal first, which is the ranking: what a body keeps for itself outranks what a
        // party project would like it to hold, for the same reason the personal board exists.
        List<ItemCall> all = new ArrayList<>(personal.reserved(ctx));
        all.addAll(party.get().reserved(ctx));
        return all;
    }

    @Override
    public List<WorkLease> leases(BrainContext ctx) {
        List<WorkLease> all = new ArrayList<>(personal.leases(ctx));
        all.addAll(party.get().leases(ctx));
        return all;
    }

    /**
     * Only the personal side has a cadence of its own here — the party board ticks server-side in
     * its host, once per board rather than once per member. That is the whole reason layer 3 can
     * later run with nobody loaded.
     */
    @Override
    public void tick(BrainContext ctx) {
        personal.tick(ctx);
    }

    @Override
    public String describe(BrainContext ctx) {
        return String.join(" | ", describeLines(ctx));
    }

    @Override
    public List<String> describeLines(BrainContext ctx) {
        List<String> lines = new ArrayList<>(personal.describeLines(ctx));
        lines.addAll(party.get().describeLines(ctx));
        return lines;
    }

    private double score(WorkItem item, BrainContext ctx) {
        return item.priority() - item.estimatedCost(ctx);
    }

    /**
     * The board that offered this item. Falls back to the personal side for an item this
     * composite never handed out — nothing legitimately reaches here that way, and a lost outcome
     * is a worse failure than one delivered to a board that will shrug it off.
     */
    private WorkSource sourceOf(WorkItem item) {
        WorkSource from = offeredBy.get(item);
        return from != null ? from : personal;
    }
}
