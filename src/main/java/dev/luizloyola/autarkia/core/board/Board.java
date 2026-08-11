package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.SiteClaims;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.board.WorkLease;
import dev.luizloyola.anima.core.brain.board.WorkSource;
import dev.luizloyola.anima.core.log.Category;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A place work is posted: a set of {@link Project}s, the items they offer, and the record of who
 * has claimed what. One class serves both scopes — a personal board is a board whose only member is
 * its owner — so the loner's case is not a different code path.
 *
 * <p><b>The board owns claim state; projects own items.</b> {@link Project#open()} returns every
 * live item, claimed or not, and the board filters, so there is exactly one answer to "is this item
 * available". A project may withdraw an <em>unclaimed</em> item freely; withdrawing a claimed one is
 * not its to do.
 *
 * <p><b>Identity arrives by view, not by seam</b> (the {@code AgentClaims} pattern): the board hands
 * each member a {@link #viewFor} binding their {@code AgentId}, and that view is the
 * {@link WorkSource} the brain sees — so {@code BrainContext} never learns whose it is, and no task
 * or instinct carries identity plumbing.
 */
public class Board {

    /** Posted projects in post order; the readout and the offer scan both follow it. */
    private final List<Entry> entries = new ArrayList<>();

    /**
     * Who holds which item, and until when. Identity-keyed on purpose: an item is one specific
     * offer, not a value — two errands that describe themselves identically are still two
     * errands.
     */
    private final Map<WorkItem, Lease> leases = new IdentityHashMap<>();

    /**
     * One hold: a holder and the tick it dies on without another heartbeat.
     *
     * <p>Same semantics as {@code SiteClaims}, sharing its TTL knob in v1. An agent preempted,
     * unloaded or killed mid-errand stops heartbeating and the hold lapses — no cleanup path, no
     * death hook, no way for a crash to wedge a board shut.
     */
    private record Lease(AgentId who, long untilTick) {
        boolean liveAt(long now) {
            return untilTick > now;
        }
    }

    /** Hands out the small stable handles the board command cancels by. Never reused. */
    private int nextHandle = 1;

    private record Entry(int handle, Project project) {
    }

    /** Posts a project and returns its handle — the {@code #n} the readout shows. */
    public int post(Project project) {
        entries.add(new Entry(nextHandle, project));
        return nextHandle++;
    }

    /**
     * Drops the project with this handle, releasing every claim on its items. Empty when no such
     * handle is posted.
     *
     * <p>Cancelling under a worker is allowed and quiet, or a stuck project would be uncancellable;
     * the arbiter still reports its outcome to a board that no longer carries it, which the routing
     * below absorbs.
     */
    public Optional<Project> cancel(int handle) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).handle() == handle) {
                Project project = entries.remove(i).project();
                forget(project);
                return Optional.of(project);
            }
        }
        return Optional.empty();
    }

    /** Every posted project, in post order. */
    public List<Project> projects() {
        List<Project> all = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            all.add(entry.project());
        }
        return List.copyOf(all);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Closes and drops every project that reports itself {@link Project#finished()}. Called on
     * whatever cadence the owning scope ticks at, and separate from posting so a board nobody has
     * ticked yet still cannot serve items from a done project.
     */
    public void closeFinished() {
        entries.removeIf(entry -> {
            if (!entry.project().finished()) {
                return false;
            }
            forget(entry.project());
            return true;
        });
    }

    /**
     * The best item on offer to this asker: highest {@code priority − estimatedCost(ctx)} among the
     * unclaimed, or empty when there is nothing for them. Greedy and per-asker (v1, decision: Luiz)
     * — no auction, no global matching. Ties go to the earlier project and, within one, the earlier
     * item, so the same board asked twice answers the same way.
     */
    public Optional<WorkItem> bestFor(AgentId asker, BrainContext ctx, long now) {
        if (asker == null) {
            return Optional.empty(); // an agent that does not yet know who it is cannot owe anything
        }
        if (isBenched(asker, now)) {
            return Optional.empty(); // failing everything: let them do something else for a while
        }
        WorkItem best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Entry entry : entries) {
            for (WorkItem item : entry.project().open()) {
                Lease lease = leases.get(item);
                if (lease != null && lease.liveAt(now)) {
                    continue; 
                }
                double score = item.priority() - item.estimatedCost(ctx);
                if (score > bestScore) {
                    best = item;
                    bestScore = score;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Takes the hold for {@code who} until {@code now + }{@link #ttlTicks()}, and tells the project.
     * Refuses only against somebody else's LIVE hold (the {@code SiteClaims} rule), so a lapsed hold
     * is an opening, not a conflict.
     */
    public boolean claim(WorkItem item, AgentId who, long now) {
        Lease held = leases.get(item);
        if (held != null && held.liveAt(now) && !held.who().equals(who)) {
            return false;
        }
        boolean fresh = held == null;
        leases.put(item, new Lease(who, now + ttlTicks()));
        if (fresh) {
            Project owner = ownerOf(item);
            if (owner != null) {
                owner.claimed(item);
            }
        }
        return true;
    }

    /** Renews {@code who}'s hold. A heartbeat from anyone else is not theirs to give. */
    public void heartbeat(WorkItem item, AgentId who, long now) {
        if (holds(item, who, now)) {
            leases.put(item, new Lease(who, now + ttlTicks()));
        }
    }

    /** Whether {@code who}'s hold on this item is live right now — what {@code stillMine} reads. */
    public boolean holds(WorkItem item, AgentId who, long now) {
        Lease held = leases.get(item);
        return who != null && held != null && held.liveAt(now) && held.who().equals(who);
    }

    /**
     * Sweeps every lapsed hold back to the pool and tells the project. Run on whatever cadence the
     * owning scope ticks at. Not a failure (the holder only stopped saying they were on it), so the
     * project pays no retry cooldown for somebody else's interruption.
     */
    public void expire(long now) {
        List<WorkItem> lapsed = new ArrayList<>();
        for (Map.Entry<WorkItem, Lease> held : leases.entrySet()) {
            if (!held.getValue().liveAt(now)) {
                lapsed.add(held.getKey());
            }
        }
        for (WorkItem item : lapsed) {
            leases.remove(item);
            Project owner = ownerOf(item);
            if (owner != null) {
                owner.lapsed(item);
            }
        }
    }

    /** Gives the item back to the pool without an outcome — a release, not a failure. */
    public void release(WorkItem item, AgentId who, long now) {
        if (holds(item, who, now)) {
            leases.remove(item);
        }
    }

    /** The item's root SUCCEEDED: the hold clears and the project is told. */
    public void completed(WorkItem item, AgentId who, BrainContext ctx) {
        flailing.remove(who); // anything at all going right ends the streak
        leases.remove(item);
        Project owner = ownerOf(item);
        if (owner != null) {
            owner.completed(item, ctx);
        }
    }

    /** The item's root FAILED: the hold clears and the project paces its own retry. */
    public void failed(WorkItem item, AgentId who, BrainContext ctx) {
        leases.remove(item);
        Project owner = ownerOf(item);
        if (owner != null) {
            owner.failed(item, who, ctx);
        }
        benchIfFlailing(who, ctx);
    }

    /**
     * Notices a worker who is failing everything and stops offering it work for a while.
     *
     * <p>A body that can succeed at nothing (stuck in geometry, unable to path) otherwise walks
     * the whole ledger in seconds, and every failure is recorded against the WORK rather than
     * against it. Not benching cost a hundred and thirty-four trees; benching costs one worker for
     * {@link #BENCH_TICKS}. The streak resets on any success.
     */
    private void benchIfFlailing(AgentId who, BrainContext ctx) {
        if (who == null) {
            return;
        }
        int streak = flailing.merge(who, 1, Integer::sum);
        if (streak >= BENCH_AFTER) {
            benched.put(who, ctx.percepts().time() + BENCH_TICKS);
            flailing.remove(who);
            ctx.journal().record(Category.PROJECT, "board",
                    "nothing is working — standing down from errands for " + BENCH_TICKS + "t");
        }
    }

    /** Consecutive failures, with no success in between, before a worker is stood down. */
    public static final int BENCH_AFTER = 4;

    /** How long a stood-down worker is offered nothing. */
    public static final int BENCH_TICKS = 600;

    /** Consecutive failures per worker; any success clears the entry. */
    private final Map<AgentId, Integer> flailing = new java.util.HashMap<>();

    /** Workers stood down, and the tick they may be offered work again. */
    private final Map<AgentId, Long> benched = new java.util.HashMap<>();

    /** Whether this worker is currently stood down — the offer scan skips them. */
    public boolean isBenched(AgentId who, long now) {
        Long until = benched.get(who);
        return until != null && until > now;
    }

    /**
     * Every live hold on this board, for the claims dump — flattened into Anima's reporting
     * shape so the command can render a holder's name without knowing what a board is.
     */
    public List<WorkLease> leases(long now) {
        List<WorkLease> live = new ArrayList<>();
        for (Entry entry : entries) {
            for (WorkItem item : entry.project().open()) {
                Lease held = leases.get(item);
                if (held != null && held.liveAt(now)) {
                    live.add(new WorkLease(held.who(), label(), item.describe(),
                            held.untilTick() - now));
                }
            }
        }
        return live;
    }

    /**
     * Every live hold as the pair a store needs — the item and who holds it. Unlike
     * {@link #leases(long)}, which flattens to a rendered line, a store needs the item itself so its
     * project can be asked for its durable name.
     */
    protected List<Map.Entry<WorkItem, AgentId>> held(long now) {
        List<Map.Entry<WorkItem, AgentId>> live = new ArrayList<>();
        for (Map.Entry<WorkItem, Lease> entry : leases.entrySet()) {
            if (entry.getValue().liveAt(now)) {
                live.add(Map.entry(entry.getKey(), entry.getValue().who()));
            }
        }
        return live;
    }

    /**
     * Ticks a hold survives past its last heartbeat. Shared with {@code SiteClaims} in v1
     * (decision: Luiz — one semantics, and for the clear-area project the two holds coincide
     * anyway), so the one knob tunes both.
     */
    public static int ttlTicks() {
        return SiteClaims.ttlTicks();
    }

    /**
     * A member's face on this board — the {@link WorkSource} half of what the brain is handed.
     * The id comes through a supplier because it is not knowable when the view is built (an
     * entity learns its {@code AgentId} on its first tick, after every field is final), and
     * because a member whose identity later changes hands should not be holding a stale one.
     */
    public WorkSource viewFor(Supplier<AgentId> member) {
        return new View(member);
    }

    /**
     * What this board is called in a readout — the one thing that differs between the scopes when
     * an operator is looking at both at once.
     */
    public String label() {
        return "board";
    }

    /**
     * One beat of whatever slow thinking this board does with a member's eyes. No-op here: a party
     * board thinks once in its host, not once per member who ticks it (see
     * {@link PartyBoard#tick(long)}); {@link PersonalBoard} overrides it.
     *
     * <p>On the base because a member reaches its board through a {@link #viewFor view}, which must
     * pass the beat along without knowing which kind of board is behind it.
     */
    public void tick(BrainContext ctx) {
    }

    /** This board's rows for the operator readout: a header line, then one line per project. */
    public List<String> describeLines(long now) {
        String label = label();
        List<String> lines = new ArrayList<>();
        if (entries.isEmpty()) {
            lines.add(label + ": nothing posted");
            return lines;
        }
        lines.add(label + ": " + entries.size() + (entries.size() == 1 ? " project" : " projects"));
        for (Entry entry : entries) {
            lines.add("  #" + entry.handle() + " " + entry.project().describe()
                    + itemSummary(entry.project(), now));
        }
        return lines;
    }

    /**
     * {@code — 1 item (held)} / {@code — 3 items, 1 held} / nothing when none are open. Counts LIVE
     * holds only: an item whose holder went quiet is on offer again.
     */
    private String itemSummary(Project project, long now) {
        List<WorkItem> open = project.open();
        if (open.isEmpty()) {
            return "";
        }
        int held = 0;
        for (WorkItem item : open) {
            Lease lease = leases.get(item);
            if (lease != null && lease.liveAt(now)) {
                held++;
            }
        }
        if (open.size() == 1) {
            return " — 1 item (" + (held == 1 ? "held" : "open") + ")";
        }
        return " — " + open.size() + " items, " + held + " held";
    }

    /** The project currently offering this item, or null if none does (cancelled mid-errand). */
    private Project ownerOf(WorkItem item) {
        for (Entry entry : entries) {
            for (WorkItem offered : entry.project().open()) {
                if (offered == item) {
                    return entry.project();
                }
            }
        }
        return null;
    }

    /** Drops every hold on a departing project's items, so the record cannot outlive the work. */
    private void forget(Project project) {
        for (WorkItem item : project.open()) {
            leases.remove(item);
        }
    }

    /**
     * One member's bound view: everything it does is the board's with the id filled in, so the brain
     * never learns there is a shared board behind it, let alone who else is on it.
     */
    private final class View implements WorkSource {
        private final Supplier<AgentId> member;

        private View(Supplier<AgentId> member) {
            this.member = member;
        }

        @Override
        public Optional<WorkItem> bestAvailable(BrainContext ctx) {
            return bestFor(member.get(), ctx, now(ctx));
        }

        @Override
        public void claimed(WorkItem item, BrainContext ctx) {
            AgentId who = member.get();
            if (who != null) {
                claim(item, who, now(ctx));
            }
        }

        @Override
        public void heartbeat(WorkItem item, BrainContext ctx) {
            Board.this.heartbeat(item, member.get(), now(ctx));
        }

        @Override
        public boolean stillMine(WorkItem item, BrainContext ctx) {
            return holds(item, member.get(), now(ctx));
        }

        @Override
        public void completed(WorkItem item, BrainContext ctx) {
            // Tell the project first, then write the line: the outcome is what advances progress,
            // so a line written before the hand-over reports the state the errand was leaving — it
            // read "closed (0/1 cleared)" on the errand that cleared the one. Only wrong for work
            // whose progress its own project keeps.
            Board.this.completed(item, member.get(), ctx);
            ctx.journal().record(Category.PROJECT, item.describe(),
                    "closed (" + item.progress(ctx) + ")");
        }

        @Override
        public void failed(WorkItem item, BrainContext ctx) {
            Board.this.failed(item, member.get(), ctx);
        }

        @Override
        public void tick(BrainContext ctx) {
            Board.this.tick(ctx);
        }

        @Override
        public List<WorkLease> leases(BrainContext ctx) {
            return Board.this.leases(now(ctx));
        }

        @Override
        public String describe(BrainContext ctx) {
            return String.join(" | ", Board.this.describeLines(now(ctx)));
        }

        @Override
        public List<String> describeLines(BrainContext ctx) {
            return Board.this.describeLines(now(ctx));
        }

        /**
         * The clock every hold is measured against — the same game time the asking body already
         * read this tick, following the {@code AgentClaims} discipline: callers stamp, the store
         * keeps no clock of its own.
         */
        private long now(BrainContext ctx) {
            return ctx.percepts().time();
        }
    }

    /**
     * Hands a lease straight back on a reload, without the bidding a fresh claim goes through: not
     * {@link #claim}, because re-taking an errand across a restart could see it scored, offered and
     * handed to somebody else, losing a settler a job they were walking to. Ticks do not pass while
     * a server is down, so the lease was never near expiring.
     */
    public void reclaim(WorkItem item, AgentId who, long now) {
        leases.put(item, new Lease(who, now + ttlTicks()));
    }
}
