package dev.luizloyola.autarkia.core.board;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.SurveyArea;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.Kit;
import dev.luizloyola.anima.core.log.Category;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Clear this area — the first project a party can be given, and the one that makes layer 3's board
 * delegate to several people at once.
 *
 * <p><b>The command marks the EDGES</b> (decision: Luiz): two corners, not a list of things to
 * remove and not a seeded ledger, which makes <em>going and looking</em> the work rather than a
 * precondition of it.
 *
 * <h2>Phases</h2>
 *
 * <pre>
 *   SURVEYING ──(every slice reported)──► CLEARING ──(every target settled)──► VERIFYING
 *                                             ▲                                   │
 *                                             └────(the second pass found new)─────┤
 *                                                                                  ▼
 *                                                              DONE ◄──(it found nothing)
 * </pre>
 *
 * <p>The phase decides the offer: unreported slices while surveying, unsettled targets while
 * clearing. A verify pass re-sweeps the whole box, because what the first pass missed is
 * <em>precisely</em> where nobody walked.
 *
 * <h2>Slices are the claim unit</h2>
 *
 * <p>A surveyor claims one slice, walks it, and reports on completion (decision: Luiz). One errand
 * for the whole box breaks three ways:
 *
 * <ul>
 *   <li><b>A report must be smaller than the memory carrying it.</b> A body remembers
 *       {@code places.max_per_kind} places of a kind (160 for a Person), so a whole-box report
 *       comes back short, its stalest entries evicted and nothing saying so. {@link #SLICE_SIZE} is
 *       sized against that number.</li>
 *   <li><b>The ledger has to outgrow any one body</b>: a slice is banked before the next is
 *       walked.</li>
 *   <li><b>A lapse has to cost something bounded</b>: only that slice is lost, and somebody takes it
 *       again from the start.</li>
 * </ul>
 *
 * <p>A surveyor reports everything they know inside the project's <em>bounds</em>, not just their
 * slice — the near field reaches 8–24 blocks, and discarding what it saw across the border would
 * mean walking that ground twice. Only the claimed slice is marked reported: that flag is what a
 * phase change reads.
 */
public final class ClearArea implements PartyProject {

    /**
     * Edge of one slice, in blocks. Sized against {@code places.max_per_kind}: dense forest runs
     * roughly one tree per 25–50 m², so 48×48 yields ~50–90 anchors against a Person's memory of
     * 160, leaving room for everything else a walk notices. Raising this past what a surveyor can
     * hold does not make surveying faster, it makes reports quietly short.
     */
    public static final int SLICE_SIZE = 48;

    /**
     * Failures before a target is given up on. Nothing else in the board machinery ever gives up —
     * {@link #failed} paces a retry and re-offers — so without it one unreachable remnant would hold
     * the project open forever.
     */
    public static final int REFUSE_AFTER = 3;

    /** Ticks a failed slice or target sits out before being offered again. */
    public static final int FAIL_COOLDOWN = 600;

    /**
     * Distance at which an errand costs the most it can, and how much that is. A bid is
     * {@code priority − estimatedCost} on a 0..1 scale, so distance is mapped: nothing at the
     * asker's feet, {@link #COST_AT_RANGE} at {@link #COST_RANGE} blocks and beyond. Together they
     * decide when party work starts losing to the personal board's standing want.
     */
    public static final int COST_RANGE = 128;
    public static final double COST_AT_RANGE = 0.25;

    /** Which pass the project is on, and what it therefore offers. */
    public enum Phase {
        /** Nobody has walked the box yet; unreported slices are on offer. */
        SURVEYING,
        /** The ledger is known; unsettled targets are on offer. */
        CLEARING,
        /** A full second sweep, to catch what the first pass walked past. */
        VERIFYING,
        /** Nothing more to find and nothing more to remove. */
        DONE
    }

    public enum TargetState {
        /** Still standing, still on offer (or waiting out a retry cooldown). */
        OPEN,
        CLEARED,
        /** Given up on after {@link #REFUSE_AFTER} failures — never offered or re-reported again. */
        REFUSED
    }

    /**
     * One thing to remove. Immutable and replaced wholesale in the ledger, so a row that is written
     * to the store is the row that was read from it.
     *
     * @param retryAfter game time before which this is not offered again, 0 when it never failed
     */
    public record Target(Pos anchor, TargetState state, int failures, long retryAfter,
                        List<AgentId> failedBy) {
        static Target fresh(Pos anchor) {
            return new Target(anchor, TargetState.OPEN, 0, 0L, List.of());
        }

        boolean offerableAt(long now) {
            return state == TargetState.OPEN && retryAfter <= now;
        }

        /**
         * The same target with one more failure recorded against the worker who had it.
         *
         * <p>{@link #failedBy} is a SET of workers, not a tally of attempts: giving up must mean
         * several people tried. One stuck worker refused a hundred and thirty-four fellable trees on
         * its own (live, 2026-08-11), each failure charged to a different tree.
         */
        Target andFailedBy(@Nullable AgentId who, long retryAfter) {
            List<AgentId> tried = new ArrayList<>(failedBy);
            if (who != null && !tried.contains(who)) {
                tried.add(who);
            }
            return new Target(anchor, state, failures + 1, retryAfter, List.copyOf(tried));
        }
    }

    /** A slice waiting out a failure — same pacing a target gets, for the same reason. */
    public record SliceCooldown(int slice, long retryAfter) {
    }

    private final Clearing clearing;
    private final Region bounds;
    private final double priority;

    /** The grid the box divides into, in a fixed order — index is the durable slice name. */
    private final List<Region> slices;

    private Phase phase = Phase.SURVEYING;

    /** Slices reported in the CURRENT pass. Emptied when a verify pass begins. */
    private final Set<Integer> reported = new LinkedHashSet<>();

    /** Slice index → game time it may be offered again. Only failures put anything here. */
    private final Map<Integer, Long> sliceRetryAfter = new LinkedHashMap<>();

    /** Everything anybody has ever reported inside the bounds, by anchor, in report order. */
    private final Map<Pos, Target> ledger = new LinkedHashMap<>();

    /** Anchors reported during the pass now under way — emptied when a new pass begins. */
    private final Set<Pos> foundThisPass = new LinkedHashSet<>();

    /**
     * Game time the current survey pass began — the cut-off for what a reporter may report.
     *
     * <p>A memory whose last sighting predates the pass is not evidence about what is standing there
     * now. Taking everything a surveyor knows reopened cleared anchors, sent people to fell ghosts,
     * and marked their cells dirty so the skip rule could never settle: the box cycled 197 cleared,
     * 186, 197, 186 (live, 2026-08-12).
     */
    private long passStartedAt;

    /**
     * What the last COMPLETED survey pass found, and the only thing the skip rule judges by.
     *
     * <p>Judging by the whole ledger never converges: a cell that once held a tree stays dirty, so a
     * worked box reads as dirty everywhere and every later pass re-walks all of it (Luiz:
     * "blacklisting didn't work, they always re-scan everything"). What was standing last time
     * somebody looked is what matters, so the empty quarters of the box drop out for good.
     */
    private Set<Pos> foundLastPass = new LinkedHashSet<>();

    /**
     * Targets removed since this clearing round began — the licence to reopen refusals.
     *
     * <p>A tree can be unreachable BECAUSE of the trees around it (decision: Luiz, 2026-08-11), but
     * unconditional reopening is the non-termination {@link #REFUSE_AFTER} prevents. So a retry
     * costs at least one felled tree, and there are finitely many.
     */
    private int clearedThisRound;

    /** What is on offer right now. Held rather than rebuilt: the board leases items by IDENTITY. */
    private final Map<WorkKey, WorkItem> open = new LinkedHashMap<>();

    /** Items somebody is holding, so a withdrawal can never pull one out from under a worker. */
    private final Set<WorkKey> claimed = new LinkedHashSet<>();

    /** {@link #open} as the board sees it, rebuilt on change so an ask allocates nothing. */
    private List<WorkItem> offer = List.of();

    public ClearArea(Clearing clearing, Region bounds, double priority) {
        this.clearing = clearing;
        this.bounds = bounds;
        this.priority = priority;
        this.slices = sliceUp(bounds);
    }

    /** What this project clears, for the store and the readout. */
    public Clearing clearing() {
        return clearing;
    }

    /** The box, as the operator typed it. */
    public Region bounds() {
        return bounds;
    }

    public Phase phase() {
        return phase;
    }

    /** The slices the box divides into, in index order. */
    public List<Region> slices() {
        return slices;
    }

    /** Everything reported so far, by anchor, in report order. */
    public Map<Pos, Target> ledger() {
        return Map.copyOf(ledger);
    }

    /** Which slices have been walked and reported in the CURRENT pass — the "explored" answer. */
    public Set<Integer> reported() {
        return Set.copyOf(reported);
    }

    /** Whether this slice is waiting out a failure rather than genuinely on offer. */
    public boolean sliceCoolingAt(int slice, long now) {
        return sliceRetryAfter.getOrDefault(slice, 0L) > now;
    }

    @Override
    public double priority() {
        return priority;
    }

    @Override
    public List<WorkItem> open() {
        return offer;
    }

    @Override
    public boolean finished() {
        return phase == Phase.DONE;
    }

    // ── the beat ─────────────────────────────────────────────────────────────────────────────

    /**
     * One host beat: cooldowns expire into fresh offers, and the phase advances when the current one
     * has run out of work. All bookkeeping over state and the clock. That is what lets a party
     * board keep thinking with every member unloaded.
     */
    @Override
    public void tick(long now) {
        refresh(now);
        advance(null, now);
    }

    /** Rebuilds the offer for the current phase, honouring cooldowns and never touching a hold. */
    private void refresh(long now) {
        switch (phase) {
            case SURVEYING, VERIFYING -> refreshSurvey(now);
            case CLEARING -> refreshClearing(now);
            case DONE -> withdrawAll();
        }
    }

    private void refreshSurvey(long now) {
        if (!clearing.surveys()) {
            // Nothing can survey for this kind yet (ladder step 2). Offer nothing and say so in
            // the readout, rather than failing errands into a cooldown forever.
            withdrawAll();
            return;
        }
        for (int i = 0; i < slices.size(); i++) {
            final int index = i;
            Region area = slices.get(index);
            WorkKey key = new WorkKey(WorkKey.SURVEY, area.min());
            if (!reported.contains(index) && sliceRetryAfter.getOrDefault(index, 0L) <= now) {
                open.computeIfAbsent(key, k -> new SurveyItem(index, area));
            } else {
                withdraw(key);
            }
        }
        rebuildOffer();
    }

    private void refreshClearing(long now) {
        for (Target target : List.copyOf(ledger.values())) {
            WorkKey key = new WorkKey(WorkKey.CLEAR, target.anchor());
            if (target.offerableAt(now)) {
                open.computeIfAbsent(key, ClearItem::new);
            } else {
                withdraw(key);
            }
        }
        rebuildOffer();
    }

    /**
     * Moves to the next phase when the current one has nothing left to do. Takes the reporter's
     * context when a report caused the change, so the line lands in that person's journal; a
     * transition driven by a cooldown gets no line, and the readout carries it.
     */
    private void advance(@Nullable BrainContext ctx, long now) {
        switch (phase) {
            case SURVEYING, VERIFYING -> {
                if (!clearing.surveys() || reported.size() < slices.size()) {
                    return;
                }
                // What this pass saw becomes the slate the next one is judged against.
                foundLastPass = new LinkedHashSet<>(foundThisPass);
                foundThisPass.clear();
                boolean anything = ledger.values().stream().anyMatch(t -> t.state() == TargetState.OPEN);
                enter(anything ? Phase.CLEARING : Phase.DONE, ctx, now);
            }
            case CLEARING -> {
                if (ledger.values().stream().anyMatch(t -> t.state() == TargetState.OPEN)) {
                    return;
                }
                // A verify pass starts knowing nothing about coverage: what the first pass missed
                // is where nobody went.
                reported.clear();
                sliceRetryAfter.clear();
                // Progress is the licence: a round that removed something has earned another look
                // at what it gave up on — its neighbours may have been what made it unreachable.
                int reopened = clearedThisRound > 0 ? reopenRefusals() : 0;
                enter(Phase.VERIFYING, ctx, now, reopened);
            }
            case DONE -> {
            }
        }
    }

    private void enter(Phase next, @Nullable BrainContext ctx, long now) {
        enter(next, ctx, now, 0);
    }

    private void enter(Phase next, @Nullable BrainContext ctx, long now, int reopened) {
        this.phase = next;
        if (next == Phase.CLEARING) {
            this.clearedThisRound = 0;
        }
        if (next == Phase.SURVEYING || next == Phase.VERIFYING) {
            this.passStartedAt = now; // nothing seen before this moment counts as seen this pass
        }
        withdrawAll();
        refresh(now);
        if (ctx != null) {
            ctx.journal().record(Category.PROJECT, name(), switch (next) {
                case CLEARING -> "surveyed — " + count(TargetState.OPEN) + " to clear";
                case VERIFYING -> "cleared — checking the whole box again"
                        + (reopened == 0 ? "" : ", and giving " + reopened
                                + " we gave up on another go now their neighbours are down");
                case DONE -> closingLine();
                case SURVEYING -> "surveying";
            });
        }
    }

    /**
     * Puts every refused target back on offer, its failure count wiped.
     *
     * <p>Called only when the round that just ended actually removed something — see
     * {@link #clearedThisRound}. The count is wiped rather than carried because the question being
     * re-asked is a different one: not "can this be felled" but "can this be felled NOW, with the
     * wood that was around it gone".
     */
    private int reopenRefusals() {
        int reopened = 0;
        for (Target target : List.copyOf(ledger.values())) {
            if (target.state() == TargetState.REFUSED) {
                ledger.put(target.anchor(), Target.fresh(target.anchor()));
                reopened++;
            }
        }
        return reopened;
    }

    private String closingLine() {
        int refused = count(TargetState.REFUSED);
        return "done — " + count(TargetState.CLEARED) + " cleared"
                + (refused == 0 ? "" : ", " + refused + " refused: " + refusedAnchors());
    }

    // ── outcomes ─────────────────────────────────────────────────────────────────────────────

    @Override
    public void claimed(WorkItem item) {
        keyOf(item).ifPresent(claimed::add);
    }

    /**
     * A lapsed hold is not a failure: the worker was pulled away and never came back, which says
     * nothing about whether the errand is doable. The item was already handed back to the pool by
     * the board; all this does is stop calling it held, so a withdrawal is allowed again.
     */
    @Override
    public void lapsed(WorkItem item) {
        keyOf(item).ifPresent(claimed::remove);
    }

    @Override
    public void completed(WorkItem item, BrainContext ctx) {
        long now = ctx.percepts().time();
        Optional<WorkKey> named = keyOf(item);
        if (named.isEmpty()) {
            return;
        }
        WorkKey key = named.get();
        claimed.remove(key);
        if (WorkKey.SURVEY.equals(key.flavour())) {
            int slice = indexOf(key);
            reported.add(slice);
            sliceRetryAfter.remove(slice);
            int found = harvest(ctx);
            ctx.journal().record(Category.PROJECT, name(),
                    "slice " + (slice + 1) + "/" + slices.size() + " walked — "
                            + (found == 0 ? "nothing new" : found + " found"));
        } else {
            settle(key.at(), TargetState.CLEARED, 0, 0L);
            clearedThisRound++;
        }
        withdraw(key);
        refresh(now);
        advance(ctx, now);
    }

    @Override
    public void failed(WorkItem item, BrainContext ctx) {
        failed(item, null, ctx);
    }

    @Override
    public void failed(WorkItem item, @Nullable AgentId who, BrainContext ctx) {
        long now = ctx.percepts().time();
        Optional<WorkKey> named = keyOf(item);
        if (named.isEmpty()) {
            return;
        }
        WorkKey key = named.get();
        claimed.remove(key);
        if (WorkKey.SURVEY.equals(key.flavour())) {
            sliceRetryAfter.put(indexOf(key), now + FAIL_COOLDOWN);
        } else {
            Target was = ledger.getOrDefault(key.at(), Target.fresh(key.at()));
            Target tried = was.andFailedBy(who, now + FAIL_COOLDOWN);
            // Distinct WORKERS, not attempts — see Target.andFailedBy for what counting attempts
            // cost. Falling back to attempts when nobody is named keeps termination: corroboration
            // needs identities, and production always names the worker, so this is the seam's
            // default rather than a path a settlement takes.
            boolean giveUp = tried.failedBy().isEmpty()
                    ? tried.failures() >= REFUSE_AFTER
                    : tried.failedBy().size() >= REFUSE_AFTER;
            ledger.put(key.at(), giveUp
                    ? new Target(key.at(), TargetState.REFUSED, tried.failures(), 0L,
                            tried.failedBy())
                    : tried);
            ctx.journal().record(Category.PROJECT, name(), giveUp
                    ? "gave up on " + at(key.at()) + " — " + tried.failedBy().size()
                            + " different people could not"
                    : "failed at " + at(key.at()) + ", retry in " + FAIL_COOLDOWN + "t");
        }
        withdraw(key);
        refresh(now);
        advance(ctx, now);
    }

    /**
     * Takes everything the reporter knows of this kind inside the bounds into the ledger, and
     * answers how much of it was new.
     *
     * <p><b>Only ever adds.</b> An anchor already in the ledger keeps the state it has unless that
     * state is {@code CLEARED}: a re-reported {@code REFUSED} target would restart the loop
     * {@link #REFUSE_AFTER} exists to end.
     */
    private int harvest(BrainContext ctx) {
        int added = 0;
        for (PoiMemory memory : ctx.knowledge().all(clearing.kind())) {
            Pos anchor = memory.anchor();
            if (!bounds.contains(anchor) || memory.lastSeenTick() < passStartedAt) {
                continue; // remembered from before this pass — not evidence about now
            }
            Target known = ledger.get(anchor);
            if (known != null && known.state() != TargetState.CLEARED) {
                continue; // already on the list, or given up on — the reopen rule owns that one
            }
            // A CLEARED anchor reported again is something standing there again. Sealing those off
            // guarded a stale memory sending somebody to an empty patch, when that cost three
            // failed chops and a permanent refusal; a chop that finds nothing now SUCCEEDS, so it
            // costs one short walk, and the seal hid real regrowth (Luiz replanted at (418, -136)).
            ledger.put(anchor, Target.fresh(anchor));
            added++;
        }
        // Everything in bounds this pass can see counts as "standing here now", whether it is new
        // to the ledger or a row somebody else already filed — the skip rule asks what was there,
        // not who reported it first.
        for (PoiMemory memory : ctx.knowledge().all(clearing.kind())) {
            if (bounds.contains(memory.anchor()) && memory.lastSeenTick() >= passStartedAt) {
                foundThisPass.add(memory.anchor());
            }
        }
        return added;
    }

    private void settle(Pos anchor, TargetState state, int failures, long retryAfter) {
        List<AgentId> tried = ledger.containsKey(anchor) ? ledger.get(anchor).failedBy() : List.of();
        ledger.put(anchor, new Target(anchor, state, failures, retryAfter, tried));
    }

    // ── durable names ────────────────────────────────────────────────────────────────────────

    @Override
    public Optional<WorkKey> keyOf(WorkItem item) {
        for (Map.Entry<WorkKey, WorkItem> entry : open.entrySet()) {
            if (entry.getValue() == item) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<WorkItem> itemFor(WorkKey key) {
        return Optional.ofNullable(open.get(key));
    }

    /**
     * Ground a later pass may skip: cells no target has ever been found in, and none of whose eight
     * neighbours has either.
     *
     * <p>The margin is the point — what a first pass walks past is nearly always beside something it
     * did find, so a cell is written off only when it is clear and surrounded by clear (decision:
     * Luiz). One dirty cell keeps its whole ring in play.
     *
     * <p>Empty on a first pass, and load-bearing: with nothing dirty yet, every cell would look
     * clear-and-surrounded-by-clear and the whole box would be written off unseen.
     *
     * <p>Not persisted — a pure function of the ledger and the bounds, so a reload recomputes it.
     */
    private Set<Pos> settledCells() {
        if (phase != Phase.VERIFYING) {
            return Set.of();
        }
        Set<Long> dirty = new java.util.HashSet<>();
        for (Pos anchor : foundLastPass) {
            dirty.add(cellKey(anchor.x(), anchor.z()));
        }
        if (dirty.isEmpty()) {
            return Set.of();
        }
        Set<Pos> settled = new LinkedHashSet<>();
        for (int x = bounds.min().x(); x <= bounds.max().x(); x += SurveyArea.CELL) {
            for (int z = bounds.min().z(); z <= bounds.max().z(); z += SurveyArea.CELL) {
                if (nearDirty(dirty, x, z)) {
                    continue;
                }
                settled.add(new Pos(x, bounds.min().y(), z));
            }
        }
        return settled;
    }

    /** Whether this cell or any of its eight neighbours has ever held a target. */
    private boolean nearDirty(Set<Long> dirty, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dirty.contains(cellKey(x + dx * SurveyArea.CELL, z + dz * SurveyArea.CELL))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A cell of the coverage grid, named by the box-relative square a world position falls in. */
    private long cellKey(int x, int z) {
        long cx = Math.floorDiv(x - bounds.min().x(), SurveyArea.CELL);
        long cz = Math.floorDiv(z - bounds.min().z(), SurveyArea.CELL);
        return cx << 32 ^ (cz & 0xFFFFFFFFL);
    }

    /** How many coverage cells the whole box divides into — the denominator for what is skipped. */
    private int cellsInBox() {
        int wide = (bounds.max().x() - bounds.min().x()) / SurveyArea.CELL + 1;
        int deep = (bounds.max().z() - bounds.min().z()) / SurveyArea.CELL + 1;
        return wide * deep;
    }

    /** How much ground a verify pass may skip — for the readout and the debug view. */
    public Set<Pos> skippable() {
        return settledCells();
    }

    // ── the readout ──────────────────────────────────────────────────────────────────────────

    @Override
    public String describe() {
        return name() + " — " + progress();
    }

    /**
     * What this project is, with no word about how far along it is — the subject every journal line
     * is filed under. Separate from {@link #describe()}, which wants both in one string: the closing
     * line came out {@code done (3 cleared - done) 3 cleared}.
     */
    private String name() {
        return "clear " + clearing.label() + " in " + size() + " at " + at(bounds.min());
    }

    private String progress() {
        int refused = count(TargetState.REFUSED);
        String tail = refused == 0 ? "" : " (" + refused + " refused)";
        return switch (phase) {
            case SURVEYING, VERIFYING -> {
                String pass = phase == Phase.SURVEYING ? "surveying " : "verifying ";
                if (!clearing.surveys()) {
                    yield pass + "— nobody can survey " + clearing.label() + " yet";
                }
                int skipped = skippable().size();
                yield pass + reported.size() + "/" + slices.size() + " slices"
                        + (skipped == 0 ? "" : ", skipping " + skipped + "/" + cellsInBox()
                                + " cells") + tail;
            }
            case CLEARING -> "clearing " + count(TargetState.CLEARED) + "/"
                    + (ledger.size() - refused) + tail;
            case DONE -> "done — " + count(TargetState.CLEARED) + " cleared" + tail;
        };
    }

    private String size() {
        return (bounds.max().x() - bounds.min().x() + 1) + "×"
                + (bounds.max().y() - bounds.min().y() + 1) + "×"
                + (bounds.max().z() - bounds.min().z() + 1);
    }

    private static String at(Pos p) {
        return "(" + p.x() + ", " + p.y() + ", " + p.z() + ")";
    }

    private String refusedAnchors() {
        List<String> named = new ArrayList<>();
        for (Target target : ledger.values()) {
            if (target.state() == TargetState.REFUSED) {
                named.add(at(target.anchor()));
            }
        }
        return String.join(", ", named);
    }

    private int count(TargetState state) {
        int n = 0;
        for (Target target : ledger.values()) {
            if (target.state() == state) {
                n++;
            }
        }
        return n;
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────

    /**
     * Divides the box's footprint into a fixed grid, keeping the box's full height in every slice —
     * a slice is ground to walk, not a cube to fill. Row-major and deterministic, so slice 3 is the
     * same ground before and after a restart. That is what makes the index a durable name.
     */
    private static List<Region> sliceUp(Region bounds) {
        List<Region> out = new ArrayList<>();
        for (int x = bounds.min().x(); x <= bounds.max().x(); x += SLICE_SIZE) {
            for (int z = bounds.min().z(); z <= bounds.max().z(); z += SLICE_SIZE) {
                out.add(new Region(
                        new Pos(x, bounds.min().y(), z),
                        new Pos(Math.min(x + SLICE_SIZE - 1, bounds.max().x()), bounds.max().y(),
                                Math.min(z + SLICE_SIZE - 1, bounds.max().z()))));
            }
        }
        return List.copyOf(out);
    }

    /** Which slice a survey key names — its corner is the key, so this is a lookup, not a guess. */
    private int indexOf(WorkKey key) {
        for (int i = 0; i < slices.size(); i++) {
            if (slices.get(i).min().equals(key.at())) {
                return i;
            }
        }
        return -1;
    }

    /** Drops an offer, unless somebody is standing in a forest acting on it. */
    private void withdraw(WorkKey key) {
        if (!claimed.contains(key)) {
            open.remove(key);
        }
    }

    private void withdrawAll() {
        open.keySet().removeIf(key -> !claimed.contains(key));
        rebuildOffer();
    }

    private void rebuildOffer() {
        this.offer = List.copyOf(open.values());
    }

    /** Walk a slice and come back knowing what is in it. Named by the slice's corner. */
    private final class SurveyItem implements WorkItem {
        private final int index;
        private final Region area;

        private SurveyItem(int index, Region area) {
            this.index = index;
            this.area = area;
        }

        @Override
        public double priority() {
            return priority;
        }

        @Override
        public double estimatedCost(BrainContext ctx) {
            return costOfWalkingTo(centreOf(area), ctx);
        }

        @Override
        public Task root() {
            return clearing.survey(area, settledCells());
        }

        @Override
        public String describe() {
            return "survey slice " + (index + 1) + "/" + slices.size() + " of " + clearing.label();
        }

        @Override
        public String progress(BrainContext ctx) {
            return reported.size() + "/" + slices.size() + " slices walked";
        }
    }

    /** Remove the one thing standing here. Named by its anchor, which is also its site claim. */
    private final class ClearItem implements WorkItem {
        private final WorkKey key;

        private ClearItem(WorkKey key) {
            this.key = key;
        }

        @Override
        public double priority() {
            return priority;
        }

        @Override
        public double estimatedCost(BrainContext ctx) {
            return costOfWalkingTo(key.at(), ctx);
        }

        @Override
        public Task root() {
            return clearing.clear(key.at());
        }

        /** The clearing kind's answer, not this project's — see {@link Clearing#kit()}. */
        @Override
        public Kit kit() {
            return clearing.kit();
        }

        @Override
        public String describe() {
            return "clear the " + clearing.label() + " at " + at(key.at());
        }

        @Override
        public String progress(BrainContext ctx) {
            return count(TargetState.CLEARED) + "/" + (ledger.size() - count(TargetState.REFUSED))
                    + " cleared";
        }
    }

    private static Pos centreOf(Region area) {
        return new Pos((area.min().x() + area.max().x()) / 2, area.min().y(),
                (area.min().z() + area.max().z()) / 2);
    }

    /**
     * Distance, mapped onto the bid scale — see {@link #COST_RANGE}. Horizontal only: a body walks
     * around a hill rather than through it, and a height difference the pathfinder handles should
     * not price an errand out.
     */
    private static double costOfWalkingTo(Pos there, BrainContext ctx) {
        Pos here = ctx.percepts().position();
        double dx = there.x() - here.x();
        double dz = there.z() - here.z();
        double distance = Math.sqrt(dx * dx + dz * dz);
        return COST_AT_RANGE * Math.min(1.0, distance / COST_RANGE);
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /**
     * Everything this project is, minus what its {@link Clearing} rebuilds — the party store's row.
     *
     * <p>The slice grid is not here: it is a pure function of the bounds and {@link #SLICE_SIZE},
     * so it comes back identical, and slice indices stay the names they were. If the constant ever
     * becomes a per-project knob it joins this record on the same day, or every saved index silently
     * moves to different ground.
     */
    public record State(String clearing, Region bounds, double priority, Phase phase,
                        List<Integer> reported, List<SliceCooldown> sliceCooldowns,
                        List<Target> targets, int clearedThisRound, long passStartedAt) {
    }

    /** What this project would need to carry on exactly where it left off. */
    public State snapshot() {
        List<SliceCooldown> cooldowns = new ArrayList<>();
        sliceRetryAfter.forEach((slice, until) -> cooldowns.add(new SliceCooldown(slice, until)));
        return new State(clearing.id(), bounds, priority, phase,
                List.copyOf(reported), List.copyOf(cooldowns), List.copyOf(ledger.values()),
                clearedThisRound, passStartedAt);
    }

    /**
     * Rebuilds a saved project and mints its current offers, so a lease can be handed straight back
     * to the member who held it. Empty when this build has no {@link Clearing} by that id — a real
     * failure for the store to report, never a row to drop quietly.
     */
    public static Optional<ClearArea> restore(State state, long now) {
        return Clearings.byId(state.clearing()).map(clearing -> {
            ClearArea project = new ClearArea(clearing, state.bounds(), state.priority());
            project.phase = state.phase();
            project.reported.addAll(state.reported());
            for (SliceCooldown cooldown : state.sliceCooldowns()) {
                project.sliceRetryAfter.put(cooldown.slice(), cooldown.retryAfter());
            }
            for (Target target : state.targets()) {
                project.ledger.put(target.anchor(), target);
            }
            project.clearedThisRound = state.clearedThisRound();
            project.passStartedAt = state.passStartedAt();
            project.refresh(now);
            return project;
        });
    }
}
