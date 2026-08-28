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

    /**
     * Which side offered an item, so its outcome goes back where it came from. <b>Only CLAIMED
     * items are in here</b> — an offer waits in {@link #lastOffer} until a claim promotes it.
     *
     * <p>Remembering every offer instead was a leak with no ceiling. A project that mints a fresh
     * item per ask ({@code Gather} realises a new trip each time) left one permanent entry per
     * ask, and {@code Arbiter} asks whenever the body holds no claim — 20 Hz per Person, against a
     * composite that is a final field of the entity.
     */
    private final Map<WorkItem, WorkSource> offeredBy = new IdentityHashMap<>();

    /**
     * The last thing {@link #bestAvailable} handed out, and the side it came from. One slot is
     * enough: the arbiter claims what it was just offered or nothing at all, so an offer that goes
     * unclaimed has no successor worth keeping.
     */
    private Offered lastOffer;

    private record Offered(WorkItem item, WorkSource from) {
    }

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
        lastOffer = new Offered(best, from);
        return Optional.of(best);
    }

    /**
     * The moment an offer becomes a commitment — and so the only moment worth remembering a route
     * for. {@code Arbiter.grantWork} calls this straight after {@link #bestAvailable} with the same
     * item, which is what makes one slot enough.
     */
    @Override
    public void claimed(WorkItem item, BrainContext ctx) {
        if (lastOffer != null && lastOffer.item() == item) {
            offeredBy.put(item, lastOffer.from());
            lastOffer = null;
        }
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
     * The board that offered this item. Falls back to the personal side for an item this composite
     * never handed out, which is not merely defensive: a reload puts the arbiter's errand back
     * through {@code Arbiter.restoreGrant} without a claim, and the item it hands over came from
     * the PERSONAL board — the party side restores its own holds on the party board instead. A
     * lost outcome would be a worse failure than one delivered to a board that will shrug it off.
     */
    private WorkSource sourceOf(WorkItem item) {
        WorkSource from = offeredBy.get(item);
        return from != null ? from : personal;
    }

    /**
     * How many items this composite is still holding a route for. Bounded by the claims in flight
     * plus the one offer waiting — the assertion the retention test is made of, since a leak here
     * is invisible in every behaviour.
     */
    int routesHeld() {
        return offeredBy.size() + (lastOffer == null ? 0 : 1);
    }
}
