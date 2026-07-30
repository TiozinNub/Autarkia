package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
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
     * Who holds which item. Identity-keyed on purpose: an item is one specific offer, not a value
     * — two errands that describe themselves identically are still two errands.
     */
    private final Map<WorkItem, AgentId> claimedBy = new IdentityHashMap<>();

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
    public Optional<WorkItem> bestFor(AgentId asker, BrainContext ctx) {
        if (asker == null) {
            return Optional.empty(); // an agent that does not yet know who it is cannot owe anything
        }
        WorkItem best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Entry entry : entries) {
            for (WorkItem item : entry.project().open()) {
                if (claimedBy.containsKey(item)) {
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

    /** Records that {@code who} owes this item, and tells the project it is no longer free. */
    public void claim(WorkItem item, AgentId who) {
        boolean fresh = claimedBy.put(item, who) == null;
        Project owner = fresh ? ownerOf(item) : null;
        if (owner != null) {
            owner.claimed(item);
        }
    }

    /** Whether {@code who} currently holds this item — step 3's {@code stillMine} reads this. */
    public boolean holds(WorkItem item, AgentId who) {
        return who != null && who.equals(claimedBy.get(item));
    }

    /** Gives the item back to the pool without an outcome — a release, not a failure. */
    public void release(WorkItem item, AgentId who) {
        if (holds(item, who)) {
            claimedBy.remove(item);
        }
    }

    /** The item's root SUCCEEDED: the claim clears and the project is told. */
    public void completed(WorkItem item, AgentId who, BrainContext ctx) {
        claimedBy.remove(item);
        Project owner = ownerOf(item);
        if (owner != null) {
            owner.completed(item, ctx);
        }
    }

    /** The item's root FAILED: the claim clears and the project paces its own retry. */
    public void failed(WorkItem item, AgentId who, BrainContext ctx) {
        claimedBy.remove(item);
        Project owner = ownerOf(item);
        if (owner != null) {
            owner.failed(item, ctx);
        }
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
    public List<String> describeLines() {
        String label = label();
        List<String> lines = new ArrayList<>();
        if (entries.isEmpty()) {
            lines.add(label + ": nothing posted");
            return lines;
        }
        lines.add(label + ": " + entries.size() + (entries.size() == 1 ? " project" : " projects"));
        for (Entry entry : entries) {
            lines.add("  #" + entry.handle() + " " + entry.project().describe()
                    + itemSummary(entry.project()));
        }
        return lines;
    }

    /** {@code — 1 item (claimed)} / {@code — 3 items, 1 claimed} / nothing when none are open. */
    private String itemSummary(Project project) {
        List<WorkItem> open = project.open();
        if (open.isEmpty()) {
            return "";
        }
        int claimed = 0;
        for (WorkItem item : open) {
            if (claimedBy.containsKey(item)) {
                claimed++;
            }
        }
        if (open.size() == 1) {
            return " — 1 item (" + (claimed == 1 ? "claimed" : "open") + ")";
        }
        return " — " + open.size() + " items, " + claimed + " claimed";
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

    /** Drops every claim on a departing project's items, so the record cannot outlive the work. */
    private void forget(Project project) {
        for (WorkItem item : project.open()) {
            claimedBy.remove(item);
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
            return bestFor(member.get(), ctx);
        }

        @Override
        public void claimed(WorkItem item, BrainContext ctx) {
            AgentId who = member.get();
            if (who != null) {
                claim(item, who);
            }
        }

        @Override
        public void completed(WorkItem item, BrainContext ctx) {
            ctx.journal().record(Category.PROJECT, item.describe(),
                    "closed (" + item.progress(ctx) + ")");
            Board.this.completed(item, member.get(), ctx);
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
        public String describe(BrainContext ctx) {
            return String.join(" | ", Board.this.describeLines());
        }

        @Override
        public List<String> describeLines(BrainContext ctx) {
            return Board.this.describeLines();
        }
    }
}
