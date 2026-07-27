package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.act.BlockBreaker;
import dev.luizloyola.autarkia.core.brain.act.MoveState;
import dev.luizloyola.autarkia.core.brain.act.ScaffoldState;
import dev.luizloyola.autarkia.core.brain.act.Scaffolder;
import dev.luizloyola.autarkia.core.brain.knowledge.BlockKind;
import dev.luizloyola.autarkia.core.brain.knowledge.BlockProbe;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiKind;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiMemory;
import dev.luizloyola.autarkia.core.brain.knowledge.RegionGrowth;
import dev.luizloyola.autarkia.core.brain.knowledge.TreeRule;
import dev.luizloyola.autarkia.core.brain.sense.Drop;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.log.Category;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Chop one whole tree, methodically. Walk near the remembered anchor, re-scan (perception's
 * {@link RegionGrowth} at task time), individuate our tree ({@link TreeSurvey}; no grounded trunk →
 * not a tree → forget), then run a deterministic sweep.
 *
 * <p><b>The trunk.</b> From the ground, second log up: break every trunk log the arm will take.
 * What stays out of reach is CLIMBED to — mount the first log and nerd-pole up the trunk's own
 * column, breaking the next log off the top of their head into the cell the step then fills
 * ({@link Scaffolder}), so the trunk feeds its own climb. The pillar is ledgered on the BODY
 * ({@code Scaffolder.placed()}), so a suspension never orphans it: a fresh instance un-builds any
 * standing ledger before walking anywhere ({@link PillarDescent}, shared with
 * {@code UnbuildPillar}).
 *
 * <p><b>The fork.</b> No branches → come down through the trunk, newest cell first, and fell the
 * last log from atop it. Branches → {@link Phase#LAYERS}, top-down and outermost-first within a
 * layer (the never-orphan order); what the arm cannot reach they WALK to across the canopy — stable
 * leaves are ground to the pathfinder — clearing the hemming leaves and re-swinging after each hop.
 * When the column is spent, the low layers are ordinary ground work.
 *
 * <p>Then the crown chores: stranded drops freed, saplings fished from the leftover canopy until
 * the pack holds the stump's pattern, the descent, the stump dead last, collection by flock
 * ({@link Flocks}), and one sapling per base cell — planted from BESIDE the footprint, because a
 * sapling goes into an occupied cell quite happily and the tree grows through them.
 *
 * <p><b>The guarantee.</b> After felling, what still stands is re-surveyed ({@link #standingLogs},
 * orphan sweep included, because a half-chop's air gap blinds the arrival scan) and re-rounds while
 * the rounds keep felling; when they run dry the chopper RE-APPROACHES for a whole fresh sweep, for
 * as long as each sweep takes something. Only a sweep that fells NOTHING ends the errand, and only
 * a clean survey fells the stump and forgets the anchor. Anything that survives <b>keeps its
 * stump</b>: a grounded base is the only thing keeping a remnant a surveyable tree.
 *
 * <p>The tree is a CLAIMED site, heartbeated every tick, so a second chopper rotates away instead
 * of felling this one's logs or its climber's scaffolding; every exit releases the claim.
 *
 * <p>Memory writes on the way out: ghost or ungrounded blob → forget + FAILED; felled whole →
 * forget + SUCCESS; felled in part → memory KEPT, SUCCESS, anchor avoided
 * {@link #PARTIAL_AVOID_TICKS}; real-but-unworkable → memory kept, FAILED, anchor AVOIDED so
 * retries rotate targets. {@link #failureDetail()} reports the real ending.
 */
public final class ChopTree implements PrimitiveTask {
    /** Close enough to the anchor to count as "at the tree" — the chop loop steps the rest. */
    public static final int APPROACH_NEAR = 6;
    /** Matches the sensor's own per-tick read budget. */
    public static final int SCAN_READS_PER_TICK = 64;
    /** Safety cap on leaves broken while freeing stranded drops. */
    public static final int FREED_LEAVES_LIMIT = 32;
    /** Ground margin around the tree bounds that still counts as the collection area. */
    public static final int COLLECT_MARGIN = 3;
    /** Obstruction-clearing swings allowed per target per stance (leaves/weeds in the arm's path). */
    public static final int CLEARS_PER_TARGET = 4;
    /**
     * Canopy hops toward one branch before conceding it. Each walk is a new stance with a fresh
     * clear budget, so one unreachable branch cannot eat the errand.
     */
    public static final int CANOPY_WALKS_PER_TARGET = 4;
    /**
     * Ticks the climb (or a layer's column adjustment) may take no action before conceding.
     * Conceding is safe: the recheck and the partial machinery pick up what stays standing.
     */
    public static final int STALL_TICKS = 3;
    /**
     * A trunk log this far above their feet is the climb's business, not the ground's: no
     * ground stance reaches it, and walking rungs at it just burns ticks.
     */
    public static final int GROUND_REACH_UP = 4;
    /**
     * How far (horizontally) a climb target's column may be for pillaring to converge: past
     * this, no height brings it into the arm's reach. Without the guard, a failed mount sent
     * them pillaring off-column to y+30 over an 8-log tree, stopped only by the body's cap.
     */
    public static final int PILLAR_HORIZONTAL = 3;
    /** Leaves broken while fishing for a replant sapling before giving up. */
    public static final int FISH_LIMIT = 24;
    /**
     * How far the orphan sweep climbs a stump's own column looking for wood a half-chop left
     * stranded — taller than any vanilla trunk, so nothing a tree could leave sits above it.
     */
    public static final int ORPHAN_SCAN_HEIGHT = 24;
    /**
     * How far around a replant site they look for a stance. Two blocks keeps every stance inside
     * the arm's reach of the whole footprint, even a 2×2 giant's.
     */
    public static final int REPLANT_STANCE_RANGE = 2;
    /**
     * Attempts to walk out of the stump's footprint before the replant is given up. Without it a
     * Person the mover cannot move (boxed in) retries the same step until the errand is
     * cancelled.
     */
    public static final int REPLANT_ASIDE_TRIES = 3;
    /**
     * How far (horizontally) from every planted site they stand before finishing. One aside only
     * clears the trunk's column and a crown is wider — a Person one block from their sapling
     * suffocated in oak leaves grown into their head cell. Three clears everything vanilla grows.
     */
    public static final int REPLANT_CLEAR_OFF = 3;
    /** Ticks an unworkable tree's anchor stays avoided, so retries rotate targets. */
    public static final int AVOID_TICKS = 6000;
    /** Shorter avoid for a tree someone ELSE is working — they'll likely be done in a minute. */
    public static final int CLAIMED_AVOID_TICKS = 1200;
    /**
     * Shorter avoid for a tree felled only in part: the leftovers are still theirs to finish,
     * and by then they are carrying its logs — the very blocks the climb needs.
     */
    public static final int PARTIAL_AVOID_TICKS = 1200;

    private enum Phase {
        APPROACH, SCAN, TRUNK, CLIMB, LAYERS, FREE_ITEMS, FISH, DESCEND, STUMP, COLLECT, REPLANT
    }

    private final PoiMemory memory;
    private final boolean replant;

    private Phase phase = Phase.APPROACH;
    private RegionGrowth scan;
    private TreeSurvey.Tree tree;
    /** The ground machine's work list: trunk logs in TRUNK, base cells in STUMP. */
    private final Deque<Pos> queue = new ArrayDeque<>();
    /** Trunk logs the ground pass could not take — the climb's work list, lowest first. */
    private final List<Pos> climbLogs = new ArrayList<>();
    /** Branch layers keyed by Y, highest first; each layer outermost-first (never-orphan). */
    private TreeMap<Integer, Deque<Pos>> layers;
    private final Set<Pos> triedStranded = new HashSet<>();
    /** The way down — un-builds the BODY's pillar ledger; also the resume-safe tower cleaner. */
    private final PillarDescent descent = new PillarDescent();
    /** Whether a descent is in flight — set on resume and at DESCEND, cleared when it completes. */
    private boolean descentActive;
    /** LEAVES cells from the arrival scan — the sapling-fishing menu. */
    private final List<Pos> canopy = new ArrayList<>();
    /** Wood still standing when they came down — non-empty means the stump STAYS, a grounded
     *  base being what keeps the remnant surveyable. */
    private List<Pos> leftStanding = List.of();

    /** The plain one-liner for a FAILED ending — what {@link #failureDetail()} reports. */
    private String ending;

    private Pos target;
    private Pos clearTarget;
    /** A pillar cell being broken to lower the center column — struck from the ledger when it falls. */
    private Pos reclaimTarget;
    private int clearsUsed;
    private int walksUsed;
    private boolean walked;
    private boolean walkIssued;
    private boolean breaking;
    /** Whether the climb has issued its walk onto the first log — the column's mounting step. */
    private boolean mounted;
    /** Consecutive action-less ticks of the climb or a layer adjustment — see {@link #STALL_TICKS}. */
    private int stalls;
    /** The lowest climb log the sightline clears are currently paying for, and their budget. */
    private Pos climbClearFocus;
    private int climbClears;
    /** The layer being worked; a change re-arms the per-layer one-shots below. */
    private int currentLayerY = Integer.MIN_VALUE;
    /** One return walk to the center column per layer — a failed return is worked around, not retried. */
    private boolean returnTried;
    /** Set when this layer's column adjustment ran out of moves — the layer is worked from here. */
    private boolean adjustDead;
    /** {@code felled} when the current re-round began; -1 before any. A round that does not
     *  move this number is the proof there are no moves left — see {@link #afterFelling}. */
    private int lastRoundFelled = -1;
    /** {@code felled} when the current SWEEP (approach-to-dry-rounds pass) began: a dry spell
     *  after a sweep that took wood earns a full re-approach, not a deferral. */
    private int sweepFelled;
    /** One "building up" aside per layer — the steps themselves would spam. */
    private boolean raiseNarrated;
    /** One walk to their own drops per climb, when the pack lacks a pillar block. */
    private boolean restockTried;
    /** One shift-over per climb, when the body refuses to jump from the current cell. */
    private boolean repositionTried;
    /** One "coming down" per descent. */
    private boolean descentNarrated;

    private int felled;
    private int skipped;
    private int freedLeaves;
    private int fished;
    private int fishCursor = -1;
    private boolean fishPassProgress;
    private int collectLaps;
    private int collectLapCap = -1;
    /** The species chopped, learned from the first log drop seen — names the sapling to plant. */
    private String speciesLogId;
    private final Deque<Pos> replantSites = new ArrayDeque<>();
    private int planted;
    /** Sites a sapling actually went into — what the finishing clear-off walk keeps away from. */
    private final List<Pos> plantedSites = new ArrayList<>();
    /** The one walk out from under the future crown, issued after the last sapling lands. */
    private boolean clearOffIssued;
    /** Steps spent trying to get out of the stump's footprint — see {@link #REPLANT_ASIDE_TRIES}. */
    private int asideTries;

    public ChopTree(PoiMemory memory, boolean replant) {
        this.memory = memory;
        this.replant = replant;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        // Heartbeat the site claim every tick. A refusal means someone else's LIVE claim — ours
        // lapsed during a long suspension — so rotate away briefly; the memory itself stays true.
        if (!ctx.claims().claim(PoiKind.TREE, memory.anchor(), ctx.percepts().time())) {
            return unworkable(ctx, "someone else is working this tree", CLAIMED_AVOID_TICKS);
        }
        return switch (phase) {
            case APPROACH -> approach(ctx);
            case SCAN -> scan(ctx);
            case TRUNK, STUMP -> chop(ctx);
            case CLIMB -> climb(ctx);
            case LAYERS -> layers(ctx);
            case FREE_ITEMS -> freeItems(ctx);
            case FISH -> fish(ctx);
            case DESCEND -> descend(ctx);
            case COLLECT -> collect(ctx);
            case REPLANT -> replant(ctx);
        };
    }

    @Override
    public void cancel(BrainContext ctx) {
        // Narrated because a stump can outlive its tree two ways — cut short here, or unreachable
        // by the arm — and only the phase name tells them apart.
        if (phase != Phase.APPROACH && phase != Phase.SCAN) {
            ctx.journal().record(Category.BRAIN, "chop", "cut short during "
                    + phase.name().toLowerCase(Locale.ROOT) + " (" + felled + " felled)");
        }
        ctx.actuators().breaker().abort();
        ctx.actuators().mover().stop();
        ctx.actuators().scaffolder().abort();
        // The claim goes with the task: a suspension frees the site, a resume re-claims or
        // rotates. Any standing pillar stays LEDGERED on the body for the next instance or the
        // descend instinct to un-build.
        ctx.claims().release(PoiKind.TREE, memory.anchor());
    }

    @Override
    public String failureDetail() {
        Pos a = memory.anchor();
        return "chop TREE (" + a.x() + ", " + a.y() + ", " + a.z() + "): "
                + (ending != null ? ending : "failed");
    }

    @Override
    public String describe() {
        Pos a = memory.anchor();
        return "chop TREE (" + a.x() + ", " + a.y() + ", " + a.z() + "): "
                + phase.name().toLowerCase(Locale.ROOT)
                + (felled > 0 ? " (" + felled + " felled)" : "");
    }

    // --- phases ----------------------------------------------------------------------------------

    private TaskStatus approach(BrainContext ctx) {
        // A fresh instance may inherit a standing ledger (the ledger is the BODY's): un-build a
        // suspended climb's tower before walking anywhere.
        if (!ctx.actuators().scaffolder().placed().isEmpty()) {
            descentActive = true;
        }
        if (unbuilding(ctx)) {
            return TaskStatus.RUNNING;
        }
        Pos here = ctx.percepts().position();
        if (horizontalDistSq(here, memory.anchor()) <= (long) APPROACH_NEAR * APPROACH_NEAR) {
            if (walkIssued) {
                ctx.actuators().mover().stop();
                walkIssued = false;
            }
            phase = Phase.SCAN;
            return TaskStatus.RUNNING;
        }
        if (!walkIssued) {
            Pos a = memory.anchor();
            ctx.actuators().mover().moveTo(a.x(), a.y(), a.z());
            walkIssued = true;
            return TaskStatus.RUNNING;
        }
        if (ctx.actuators().mover().state() == MoveState.MOVING) {
            return TaskStatus.RUNNING;
        }
        walkIssued = false;
        if (horizontalDistSq(ctx.percepts().position(), memory.anchor())
                <= (long) (APPROACH_NEAR * 2) * (APPROACH_NEAR * 2)) {
            phase = Phase.SCAN;
            return TaskStatus.RUNNING;
        }
        return unworkable(ctx, "could not reach the tree");
    }

    private TaskStatus scan(BrainContext ctx) {
        BlockProbe probe = ctx.percepts().blocks();
        if (scan == null) {
            Optional<Pos> seed = findSeed(probe);
            if (seed.isEmpty()) {
                return ghost(ctx);
            }
            scan = new RegionGrowth(TreeRule.INSTANCE, seed.get(),
                    probe.at(seed.get().x(), seed.get().y(), seed.get().z()));
        }
        scan.step(probe, SCAN_READS_PER_TICK);
        if (!scan.isDone()) {
            return TaskStatus.RUNNING;
        }
        for (var entry : scan.result().blocks().entrySet()) {
            if (entry.getValue() == BlockKind.LEAVES) {
                canopy.add(entry.getKey());
            }
        }
        List<TreeSurvey.Tree> trees = TreeSurvey.survey(scan.result().blocks(), probe);
        tree = TreeSurvey.nearest(trees, memory.anchor()).orElse(null);
        if (tree == null) {
            // No grounded trunk in the blob: not a tree anymore (decision: Luiz). Heals legacy
            // floater memories on first visit, and perception won't re-remember them.
            return ghost(ctx);
        }
        ctx.journal().record(Category.BRAIN, "chop",
                "felling (" + tree.logCount() + " logs, " + tree.branches().size() + " branches)");
        Pos a = memory.anchor();
        think(ctx, "started chopping the tree at (" + a.x() + ", " + a.y() + ", " + a.z()
                + ") — " + tree.logCount() + " logs" + (tree.branches().isEmpty() ? ""
                : ", " + tree.branches().size() + " branches"));
        think(ctx, "breaking the trunk");
        queue.addAll(tree.upper());
        phase = Phase.TRUNK;
        return TaskStatus.RUNNING;
    }

    /**
     * The ground machine — {@link Phase#TRUNK} (second log up, from around the tree) and
     * {@link Phase#STUMP} (the base, dead last, underfoot). Per target: swing → clear the arm's
     * path → walk once → concede. A TRUNK concession costs nothing: the log goes on the climb's
     * list.
     */
    private TaskStatus chop(BrainContext ctx) {
        if (breakInFlight(ctx)) {
            return TaskStatus.RUNNING;
        }
        if (walkIssued) {
            if (ctx.actuators().mover().state() == MoveState.MOVING) {
                return TaskStatus.RUNNING;
            }
            walkIssued = false;
        }
        if (target == null) {
            if (queue.isEmpty()) {
                return advanceGround(ctx);
            }
            target = queue.poll();
            walked = false;
            clearsUsed = 0;
        }
        BlockProbe probe = ctx.percepts().blocks();
        if (probe.at(target.x(), target.y(), target.z()) != BlockKind.LOG) {
            target = null; // already gone — not ours to count
            return TaskStatus.RUNNING;
        }
        BlockBreaker breaker = ctx.actuators().breaker();
        if (breaker.begin(target)) {
            breaking = true;
            return TaskStatus.RUNNING;
        }
        // A log well above their head is the climb's business — no stance down here reaches it.
        if (phase == Phase.TRUNK
                && target.y() - ctx.percepts().position().y() >= GROUND_REACH_UP) {
            climbLogs.add(target);
            target = null;
            return TaskStatus.RUNNING;
        }
        if (clearsUsed < CLEARS_PER_TARGET) {
            for (Pos blocker : obstructions(ctx, target)) {
                if (breaker.begin(blocker)) {
                    breaking = true;
                    clearTarget = blocker;
                    if (++clearsUsed == 1) {
                        think(ctx, " - breaking a leaf on my way");
                    }
                    return TaskStatus.RUNNING;
                }
            }
        }
        if (!walked) {
            walked = true;
            Pos stance = stanceNear(ctx, target);
            Pos goal = stance != null ? stance : target;
            ctx.actuators().mover().moveTo(goal.x(), goal.y(), goal.z());
            walkIssued = true;
            return TaskStatus.RUNNING;
        }
        if (phase == Phase.TRUNK) {
            climbLogs.add(target); // out of ground answers — the climb takes it from here
        } else {
            // The one skip that leaves permanent litter: with the crown down, a bare grounded log
            // has no sunlit leaf left to prove it a tree ({@link TreeRule}) — nobody sees it again.
            ctx.journal().record(Category.BRAIN, "chop", "could not fell the stump at ("
                    + target.x() + ", " + target.y() + ", " + target.z() + ")");
            skipped++;
        }
        target = null;
        return TaskStatus.RUNNING;
    }

    /** Where the ground machine goes when its list runs dry. */
    private TaskStatus advanceGround(BrainContext ctx) {
        if (phase == Phase.STUMP) {
            // Reaching STUMP at all means nothing of this tree was left standing (descend
            // routes a partial fell straight to COLLECT), so the count is the whole story.
            ctx.journal().record(Category.BRAIN, "chop", "felled (" + felled + " logs)");
            think(ctx, "all of it is down — picking up the wood");
            phase = Phase.COLLECT;
            return TaskStatus.RUNNING;
        }
        if (!climbLogs.isEmpty()) {
            climbLogs.sort(Comparator.comparingInt(Pos::y));
            ctx.journal().record(Category.BRAIN, "chop", "climbing the column for "
                    + climbLogs.size() + " high log" + (climbLogs.size() == 1 ? "" : "s"));
            think(ctx, "the trunk runs high — building up after it");
            phase = Phase.CLIMB;
            return TaskStatus.RUNNING;
        }
        return advanceFromClimb(ctx);
    }

    /**
     * The climb, in the trunk's own column: mount the first log, break the next off the top of
     * their head, nerd-pole into the cell it vacates ({@link Scaffolder#up}), repeat — each log
     * broken refunds the block the last step placed. Sightline clears (bounded per log) cover a
     * 2×2 giant's neighbour columns and a re-round's off-axis orphan. No action for
     * {@link #STALL_TICKS} ticks concedes the column to the recheck.
     */
    private TaskStatus climb(BrainContext ctx) {
        if (breakInFlight(ctx)) {
            return TaskStatus.RUNNING;
        }
        if (ctx.actuators().scaffolder().state() == ScaffoldState.RISING) {
            return TaskStatus.RUNNING;
        }
        if (walkIssued) {
            if (ctx.actuators().mover().state() == MoveState.MOVING) {
                return TaskStatus.RUNNING;
            }
            walkIssued = false;
        }
        BlockProbe probe = ctx.percepts().blocks();
        climbLogs.removeIf(cell -> probe.at(cell.x(), cell.y(), cell.z()) != BlockKind.LOG);
        if (climbLogs.isEmpty()) {
            return advanceFromClimb(ctx);
        }
        if (!mounted) {
            mounted = true;
            // The base-top cell still holds the trunk until log 2 falls, so walking AT it asks
            // for an unstandable goal and the zero-length partial moves nobody. Aim at the
            // nearest standable stance: the climb works from beside the column just as well.
            Pos top = tree.base().get(0);
            Pos baseTop = new Pos(top.x(), top.y() + 1, top.z());
            Pos goal = stanceNear(ctx, baseTop);
            if (goal == null) {
                goal = baseTop;
            }
            ctx.actuators().mover().moveTo(goal.x(), goal.y(), goal.z());
            walkIssued = true;
            return TaskStatus.RUNNING;
        }
        BlockBreaker breaker = ctx.actuators().breaker();
        for (int i = 0; i < climbLogs.size(); i++) {
            Pos log = climbLogs.get(i);
            if (breaker.begin(log)) {
                climbLogs.remove(i);
                target = log;
                breaking = true;
                stalls = 0;
                return TaskStatus.RUNNING;
            }
        }
        // Nothing in reach. Open the lowest log's sightline first (bounded per log) ...
        Pos lowest = climbLogs.get(0);
        if (!lowest.equals(climbClearFocus)) {
            climbClearFocus = lowest;
            climbClears = 0;
        }
        if (climbClears < CLEARS_PER_TARGET) {
            for (Pos blocker : obstructions(ctx, lowest)) {
                if (breaker.begin(blocker)) {
                    breaking = true;
                    clearTarget = blocker;
                    if (++climbClears == 1) {
                        think(ctx, " - breaking a leaf on my way");
                    }
                    stalls = 0;
                    return TaskStatus.RUNNING;
                }
            }
        }
        // Climbing is attempted only when it CONVERGES: the lowest remaining log overhead, its
        // column within the arm's horizontal reach of the feet. Anything else — a log at that
        // height, one already climbed past, one whose column no height brings closer — makes the
        // pillar a tower to get stranded on.
        Pos feet = ctx.percepts().position();
        boolean converges = lowest.y() > feet.y() + 1 && horizontalDistSq(feet, lowest)
                <= (long) PILLAR_HORIZONTAL * PILLAR_HORIZONTAL;
        if (converges) {
            // The next step needs the cell two above the feet empty — canopy or the next log,
            // both theirs. LEAVES and LOG only: this opens a tree, never someone's roof.
            if (clearHeadroom(ctx, probe)) {
                return TaskStatus.RUNNING;
            }
            // ... then rise one step on their own logs — restocking from their own drops first
            // when the pack is empty: the felled logs land at the tree's feet.
            String block = pillarBlock(ctx);
            if (block == null && restockForClimb(ctx)) {
                return TaskStatus.RUNNING;
            }
            if (block != null && ctx.actuators().scaffolder().up(block)) {
                stalls = 0;
                return TaskStatus.RUNNING;
            }
            // The body's step refusals are per-cell (a leaf over the real bounding box, a streak
            // of dead steps), so one cell over is a fresh ask; one reposition per climb bounds it.
            if (block != null && !repositionTried) {
                repositionTried = true;
                Pos stance = stanceNear(ctx, lowest, feet);
                if (stance != null) {
                    think(ctx, " - can't jump here — shifting over");
                    ctx.actuators().mover().moveTo(stance.x(), stance.y(), stance.z());
                    walkIssued = true;
                    stalls = 0;
                    return TaskStatus.RUNNING;
                }
            }
        }
        if (++stalls >= STALL_TICKS) {
            skipped += climbLogs.size();
            ctx.journal().record(Category.BRAIN, "chop",
                    "the column beat " + ctx.gender().objectPronoun() + " — "
                    + climbLogs.size() + " log" + (climbLogs.size() == 1 ? "" : "s")
                    + " staying up for now");
            climbLogs.clear();
            return advanceFromClimb(ctx);
        }
        return TaskStatus.RUNNING;
    }

    /** Where felling goes when the column work ends: remaining branch layers, else the recheck. */
    private TaskStatus advanceFromClimb(BrainContext ctx) {
        boolean layersRemain = layers == null ? !tree.branches().isEmpty() : !layers.isEmpty();
        if (layersRemain) {
            currentLayerY = Integer.MIN_VALUE;
            phase = Phase.LAYERS;
            return TaskStatus.RUNNING;
        }
        return afterFelling(ctx);
    }

    /**
     * Branch work, layer by layer from the crown down, outermost-first within a layer — the
     * never-orphan order. The rebuilt trunk column is the workbench: one return walk per layer,
     * bring its top to the layer ({@link #adjustColumn}), swing from there. What the arm refuses
     * they WALK to across the canopy (stable leaves are ground to the pathfinder) clearing the
     * hemming leaves; a branch that survives every stance is conceded to the recheck.
     */
    private TaskStatus layers(BrainContext ctx) {
        if (breakInFlight(ctx)) {
            return TaskStatus.RUNNING;
        }
        if (ctx.actuators().scaffolder().state() == ScaffoldState.RISING) {
            return TaskStatus.RUNNING;
        }
        if (walkIssued) {
            if (ctx.actuators().mover().state() == MoveState.MOVING) {
                return TaskStatus.RUNNING;
            }
            walkIssued = false;
        }
        BlockProbe probe = ctx.percepts().blocks();
        if (layers == null) {
            layers = new TreeMap<>(Comparator.reverseOrder());
            for (Pos branch : tree.branches()) {
                addToLayers(branch);
            }
            ctx.journal().record(Category.BRAIN, "chop", "working " + tree.branches().size()
                    + " branches in " + layers.size() + " layer"
                    + (layers.size() == 1 ? "" : "s"));
            think(ctx, "found " + tree.branches().size() + " branch"
                    + (tree.branches().size() == 1 ? "" : "es") + " — top ones first");
        }
        while (target == null && !layers.isEmpty()) { // prune the felled, drop spent layers
            Deque<Pos> top = layers.firstEntry().getValue();
            top.removeIf(cell -> probe.at(cell.x(), cell.y(), cell.z()) != BlockKind.LOG);
            if (top.isEmpty()) {
                layers.pollFirstEntry();
            } else {
                break;
            }
        }
        if (target == null && layers.isEmpty()) {
            return afterFelling(ctx);
        }
        int layerY = layers.isEmpty() ? currentLayerY : layers.firstKey();
        if (layerY != currentLayerY) {
            currentLayerY = layerY; // a new layer: re-arm its one-shots
            returnTried = false;
            adjustDead = false;
            raiseNarrated = false;
            stalls = 0;
        }
        if (target == null && !adjustDead && adjustColumn(ctx, probe, layerY)) {
            return TaskStatus.RUNNING;
        }
        if (target == null) {
            target = layers.firstEntry().getValue().poll();
            clearsUsed = 0;
            walksUsed = 0;
            think(ctx, "destroying the branch at (" + target.x() + ", " + target.y() + ", "
                    + target.z() + ")");
        }
        if (probe.at(target.x(), target.y(), target.z()) != BlockKind.LOG) {
            target = null; // gone since it was polled — decayed grip, or their clears took it
            return TaskStatus.RUNNING;
        }
        BlockBreaker breaker = ctx.actuators().breaker();
        if (breaker.begin(target)) {
            breaking = true;
            return TaskStatus.RUNNING;
        }
        if (clearsUsed < CLEARS_PER_TARGET) {
            for (Pos blocker : obstructions(ctx, target)) {
                if (breaker.begin(blocker)) {
                    breaking = true;
                    clearTarget = blocker;
                    if (++clearsUsed == 1) {
                        think(ctx, " - breaking a leaf on my way");
                    }
                    return TaskStatus.RUNNING;
                }
            }
        }
        if (walksUsed < CANOPY_WALKS_PER_TARGET) {
            walksUsed++;
            if (walksUsed == 1) {
                think(ctx, " - reaching for it");
            }
            clearsUsed = 0; // a new stance earns a fresh clear budget — new hemming leaves
            Pos stance = stanceNear(ctx, target);
            Pos goal = stance != null ? stance : target;
            ctx.actuators().mover().moveTo(goal.x(), goal.y(), goal.z());
            walkIssued = true;
            return TaskStatus.RUNNING;
        }
        skipped++; // out of answers from every stance — the recheck round is the real net
        target = null;
        return TaskStatus.RUNNING;
    }

    /**
     * One tick of bringing the workbench to the layer; {@code true} when it acted. Off the
     * column, one walk back per layer — a failed return works the layer from where they stand.
     * On it: build up while the layer is above the arm, break their own newest pillar cell
     * underfoot while it is below. A rung out of moves marks the adjustment dead for this layer.
     */
    private boolean adjustColumn(BrainContext ctx, BlockProbe probe, int layerY) {
        Pos feet = ctx.percepts().position();
        Scaffolder scaffolder = ctx.actuators().scaffolder();
        if (!inTrunkColumn(feet)) {
            boolean wanted = layerY > feet.y() + 2 || !scaffolder.placed().isEmpty();
            if (!returnTried && wanted) {
                returnTried = true;
                Pos top = columnTop(scaffolder);
                ctx.actuators().mover().moveTo(top.x(), top.y() + 1, top.z());
                walkIssued = true;
                return true;
            }
            adjustDead = true;
            return false;
        }
        if (layerY > feet.y() + 2) { // the layer is above the arm: build up to it
            if (clearHeadroom(ctx, probe)) {
                return true;
            }
            String block = pillarBlock(ctx);
            if (block != null && scaffolder.up(block)) {
                if (!raiseNarrated) {
                    raiseNarrated = true;
                    think(ctx, " - building up to reach it");
                }
                stalls = 0;
                return true;
            }
            if (++stalls >= STALL_TICKS) {
                adjustDead = true; // can't rise from here — the layer is worked at this height
            }
            return false;
        }
        if (layerY < feet.y() - 1 && !scaffolder.placed().isEmpty()) { // break one down
            Pos step = scaffolder.placed().get(0);
            if (step.x() == feet.x() && step.z() == feet.z() && step.y() == feet.y() - 1
                    && ctx.actuators().breaker().begin(step)) {
                breaking = true;
                reclaimTarget = step;
                return true;
            }
            adjustDead = true;
            return false;
        }
        return false; // at working height already
    }

    /**
     * Break whatever the next nerd-pole step needs out of the cell two above the feet: a canopy
     * leaf (off every arm ray, so only this rung can take it), or the tree's own next log.
     * LEAVES and LOG only: this opens a tree, never someone's roof. A LOG here counts as felled.
     */
    private boolean clearHeadroom(BrainContext ctx, BlockProbe probe) {
        Pos feet = ctx.percepts().position();
        Pos headroom = new Pos(feet.x(), feet.y() + 2, feet.z());
        BlockKind overhead = probe.at(headroom.x(), headroom.y(), headroom.z());
        if (overhead != BlockKind.LEAVES && overhead != BlockKind.LOG) {
            return false;
        }
        if (!ctx.actuators().breaker().begin(headroom)) {
            return false;
        }
        breaking = true;
        if (overhead == BlockKind.LOG) {
            target = headroom; // tree wood — a felled log, not a sightline clear
        } else {
            clearTarget = headroom;
        }
        stalls = 0;
        return true;
    }

    /**
     * The gate between felling and the exit chores. Re-survey what is still standing
     * ({@link #standingLogs}, orphan sweep included) and send it back through the machinery —
     * trunk-column cells to the climb, the rest to the layers — for as many rounds as keep
     * felling wood (decision: Luiz; one fixed re-round left close neighbours half-done).
     * Progress-gated, not counted: a round that fells ZERO proves there are no moves left, so at
     * most one round is spent per stuck tree.
     */
    private TaskStatus afterFelling(BrainContext ctx) {
        List<Pos> standing = standingLogs(ctx);
        if (standing.isEmpty()) {
            think(ctx, "no more branches, nothing standing — good");
            phase = Phase.FREE_ITEMS;
            return TaskStatus.RUNNING;
        }
        boolean progressed = lastRoundFelled < 0 || felled > lastRoundFelled;
        if (progressed) {
            lastRoundFelled = felled;
            ctx.journal().record(Category.BRAIN, "chop", standing.size() + " log"
                    + (standing.size() == 1 ? "" : "s") + " still standing — another round");
            think(ctx, standing.size() + " log" + (standing.size() == 1 ? "" : "s")
                    + " still up — going again");
            for (Pos cell : standing) {
                if (inTrunkColumn(cell)) {
                    climbLogs.add(cell);
                } else {
                    addToLayers(cell);
                }
            }
            if (!climbLogs.isEmpty()) {
                climbLogs.sort(Comparator.comparingInt(Pos::y));
                mounted = false;
                restockTried = false;
                repositionTried = false;
                stalls = 0;
                climbClearFocus = null;
                phase = Phase.CLIMB;
            } else {
                currentLayerY = Integer.MIN_VALUE;
                phase = Phase.LAYERS;
            }
            return TaskStatus.RUNNING;
        }
        // Rounds ran dry with wood still up. No partial is banked (decision: Luiz — finish one
        // tree completely before the next): while the sweep just ended took SOMETHING, they
        // re-approach for a fresh scan and fresh stances. Only a sweep that fells nothing is
        // proof of a dead end.
        if (felled > sweepFelled) {
            restartSweep(ctx, standing.size());
            return TaskStatus.RUNNING;
        }
        phase = Phase.FREE_ITEMS; // a whole sweep took nothing — the closing chores
        return TaskStatus.RUNNING;
    }

    /** Reset the felling machine for another full pass at the same tree, from the approach up.
     *  Counters survive ({@code felled} is cumulative; {@code sweepFelled} gates the next dry
     *  spell), and any standing pillar is un-built by APPROACH's resume path — where the
     *  holding-wood guard turns it into surveyable trunk for the fresh scan to re-fell. */
    private void restartSweep(BrainContext ctx, int standingCount) {
        ctx.journal().record(Category.BRAIN, "chop", standingCount + " log"
                + (standingCount == 1 ? "" : "s") + " still standing — re-approaching for"
                + " another sweep");
        think(ctx, "still wood up there — walking back in for another pass");
        sweepFelled = felled;
        descentNarrated = false;
        lastRoundFelled = -1;
        scan = null;
        tree = null;
        canopy.clear();
        queue.clear();
        climbLogs.clear();
        layers = null;
        target = null;
        clearTarget = null;
        reclaimTarget = null;
        walked = false;
        walkIssued = false;
        mounted = false;
        restockTried = false;
        repositionTried = false;
        stalls = 0;
        climbClears = 0;
        climbClearFocus = null;
        currentLayerY = Integer.MIN_VALUE;
        returnTried = false;
        adjustDead = false;
        phase = Phase.APPROACH;
    }

    private TaskStatus freeItems(BrainContext ctx) {
        if (breakInFlight(ctx)) {
            return TaskStatus.RUNNING;
        }
        if (freedLeaves >= FREED_LEAVES_LIMIT) {
            phase = wantsFishing(ctx) ? Phase.FISH : Phase.DESCEND;
            if (phase == Phase.FISH) {
                think(ctx, "no sapling in my pack — searching the leaves for one");
            }
            return TaskStatus.RUNNING;
        }
        BlockProbe probe = ctx.percepts().blocks();
        Pos here = ctx.percepts().position();
        Pos bestLeaf = null;
        long bestDist = Long.MAX_VALUE;
        for (Drop drop : ctx.percepts().drops()) {
            noteSpecies(drop);
            if (!inArea(drop.pos(), 1) || triedStranded.contains(drop.pos())) {
                continue;
            }
            Pos below = new Pos(drop.pos().x(), drop.pos().y() - 1, drop.pos().z());
            if (probe.at(below.x(), below.y(), below.z()) != BlockKind.LEAVES) {
                continue; // not stranded — the collect phase's business
            }
            if (below.x() == here.x() && below.y() == here.y() - 1 && below.z() == here.z()) {
                continue; // the leaf THEY stand on — freeing a drop does not justify the fall
            }
            long dist = distSq(here, below);
            if (dist < bestDist) {
                bestDist = dist;
                bestLeaf = below;
            }
        }
        if (bestLeaf == null) {
            phase = wantsFishing(ctx) ? Phase.FISH : Phase.DESCEND;
            if (phase == Phase.FISH) {
                think(ctx, "no sapling in my pack — searching the leaves for one");
            }
            return TaskStatus.RUNNING;
        }
        if (ctx.actuators().breaker().begin(bestLeaf)) {
            breaking = true;
            freedLeaves++;
        } else {
            triedStranded.add(new Pos(bestLeaf.x(), bestLeaf.y() + 1, bestLeaf.z()));
        }
        return TaskStatus.RUNNING;
    }

    /**
     * Un-build the rebuilt trunk, newest cell first (they leave no towers), then decide the
     * stump's fate. The pillar comes down before the count: their scaffolding is logs standing in
     * the trunk's own cells, and a step where a felled log used to be would read back as that log.
     *
     * <p><b>Wood still standing means the stump stays.</b> A grounded base is the only thing
     * making a remnant a surveyable tree ({@link TreeSurvey}); fell it under standing wood and
     * the leftovers survey as zero trees, ghosted and forgotten by every later visit.
     */
    private TaskStatus descend(BrainContext ctx) {
        if (!descentNarrated) {
            descentNarrated = true;
            think(ctx, "coming back down through the trunk");
        }
        descentActive = true;
        if (unbuilding(ctx)) {
            return TaskStatus.RUNNING;
        }
        leftStanding = standingLogs(ctx);
        if (!leftStanding.isEmpty()) {
            phase = Phase.COLLECT; // take the wood won, leave the stump holding the rest
            return TaskStatus.RUNNING;
        }
        think(ctx, "taking the stump last");
        queue.addAll(tree.base()); // breaking the block under their own feet is allowed —
        phase = Phase.STUMP;       // they drop one block, exactly like a player would
        return TaskStatus.RUNNING;
    }

    /**
     * This tree's wood above the stump that is still a log right now: surveyed cells still
     * standing, plus any log over a stump's own column this scan could not see. Their own
     * scaffolding never counts — pillar steps land in exactly these cells, and the body's ledger
     * knows which are the climber's.
     *
     * <p><b>The orphan sweep</b> covers the scan's blind spot: a chop cut short leaves a VERTICAL
     * AIR GAP, {@link RegionGrowth} cannot grow across air, and the next visit would survey a
     * one-log tree, fell the stump and forget the anchor — stranding the crown for good, since
     * {@link TreeSurvey} individuates nothing without a grounded base. Its reach is a FIXED
     * height, not the remembered bounds (a lone stump remembers one block), and it stops at the
     * first block that is neither air nor leaves, so it never runs up the inside of a house.
     */
    private List<Pos> standingLogs(BrainContext ctx) {
        BlockProbe probe = ctx.percepts().blocks();
        Set<Pos> mine = new HashSet<>(ctx.actuators().scaffolder().placed());
        Set<Pos> standing = new LinkedHashSet<>();
        for (Pos cell : tree.upper()) {
            addIfStanding(probe, mine, cell, standing);
        }
        for (Pos cell : tree.branches()) {
            addIfStanding(probe, mine, cell, standing);
        }
        for (Pos base : tree.base()) {
            for (int y = base.y() + 1; y <= base.y() + ORPHAN_SCAN_HEIGHT; y++) {
                Pos cell = new Pos(base.x(), y, base.z());
                BlockKind kind = probe.at(cell.x(), cell.y(), cell.z());
                if (kind == BlockKind.LOG) {
                    addIfStanding(probe, mine, cell, standing);
                } else if (kind != BlockKind.AIR && kind != BlockKind.LEAVES) {
                    break; // a ceiling, a floor, someone's build — the tree's column ends here
                }
            }
        }
        return List.copyOf(standing);
    }

    private static void addIfStanding(BlockProbe probe, Set<Pos> mine, Pos cell, Set<Pos> into) {
        if (!mine.contains(cell) && probe.at(cell.x(), cell.y(), cell.z()) == BlockKind.LOG) {
            into.add(cell);
        }
    }

    /**
     * The descend-first gate the resume path and the DESCEND phase route through: ticks the
     * shared {@link PillarDescent} while {@link #descentActive}, and reports whether this tick
     * was spent un-building (in which case the caller must do nothing else with the arm).
     */
    private boolean unbuilding(BrainContext ctx) {
        if (!descentActive) {
            return false;
        }
        if (descent.tick(ctx) == TaskStatus.RUNNING) {
            return true;
        }
        descentActive = false;
        return false;
    }

    private TaskStatus collect(BrainContext ctx) {
        List<Pos> ground = new ArrayList<>();
        BlockProbe probe = ctx.percepts().blocks();
        for (Drop drop : ctx.percepts().drops()) {
            noteSpecies(drop);
            if (inArea(drop.pos(), COLLECT_MARGIN)
                    && probe.at(drop.pos().x(), drop.pos().y() - 1, drop.pos().z()) != BlockKind.LEAVES) {
                ground.add(drop.pos());
            }
        }
        if (collectLapCap < 0) {
            collectLapCap = Flocks.count(ground) * 3 + 6;
        }
        if (ground.isEmpty() || collectLaps >= collectLapCap) {
            // No replanting under a tree that is still standing: the stump deliberately
            // left is occupying every site, and it is already the tree that would be planted.
            if (replant && !tree.base().isEmpty() && leftStanding.isEmpty()) {
                if (walkIssued) {
                    ctx.actuators().mover().stop();
                    walkIssued = false;
                }
                replantSites.addAll(tree.base());
                think(ctx, "replanting the stump");
                phase = Phase.REPLANT;
                return TaskStatus.RUNNING;
            }
            return finish(ctx);
        }
        if (walkIssued && ctx.actuators().mover().state() == MoveState.MOVING) {
            return TaskStatus.RUNNING;
        }
        Pos centroid = Flocks.nearestCentroid(ground, ctx.percepts().position());
        ctx.actuators().mover().moveTo(centroid.x(), centroid.y(), centroid.z());
        walkIssued = true;
        collectLaps++;
        return TaskStatus.RUNNING;
    }

    /**
     * No sapling in the pack? Fish for one (decision: Luiz): break leftover canopy leaves WHILE
     * Still up AT the CROWN (after the descent every leaf is out of arm's reach), and let the
     * ground sweep collect what falls. Yielding nothing skips, never fails.
     */
    private TaskStatus fish(BrainContext ctx) {
        String species = species(ctx);
        String sapling = species == null ? null : saplingFor(species);
        if (sapling == null || ctx.percepts().inventory().count(sapling) >= tree.base().size()
                || fished >= FISH_LIMIT) {
            phase = Phase.DESCEND; // the pack holds the stump's pattern (or the crown is spent)
            return TaskStatus.RUNNING;
        }
        if (breakInFlight(ctx)) {
            return TaskStatus.RUNNING;
        }
        // The canopy blocks its own interior (leaves are opaque to arms), so refused leaves
        // stay on the menu: breaking the outer shell is what exposes the next pass's targets.
        // A full pass with no swing means everything left is truly unreachable.
        BlockProbe probe = ctx.percepts().blocks();
        BlockBreaker breaker = ctx.actuators().breaker();
        Pos feet = ctx.percepts().position();
        if (fishCursor < 0) {
            fishCursor = canopy.size() - 1;
            fishPassProgress = false;
        }
        while (fishCursor >= 0) {
            if (fishCursor >= canopy.size()) {
                fishCursor = canopy.size() - 1;
                continue;
            }
            Pos leaf = canopy.get(fishCursor);
            if (probe.at(leaf.x(), leaf.y(), leaf.z()) != BlockKind.LEAVES) {
                canopy.remove(fishCursor--); // broken or decayed — off the menu
                continue;
            }
            if (leaf.x() == feet.x() && leaf.y() == feet.y() - 1 && leaf.z() == feet.z()) {
                fishCursor--; // the leaf under their own boots — never saw off the branch you
                continue;     // stand on; stays on the menu for a stance that isn't on it
            }
            if (breaker.begin(leaf)) {
                canopy.remove(fishCursor--);
                breaking = true;
                fished++;
                fishPassProgress = true;
                return TaskStatus.RUNNING;
            }
            fishCursor--; // out of reach this pass — the shell may open it up next pass
        }
        if (canopy.isEmpty() || !fishPassProgress) {
            phase = Phase.DESCEND; // exhausted, or the rest is truly unreachable
        } else {
            fishCursor = -1; // another pass: the broken shell exposed new targets
        }
        return TaskStatus.RUNNING;
    }

    /**
     * Whether the crown is worth fishing: replanting is on and the pack holds fewer saplings than
     * the stump's pattern needs — one per base cell (decision: Luiz).
     */
    private boolean wantsFishing(BrainContext ctx) {
        if (!replant || tree == null || tree.base().isEmpty() || fished >= FISH_LIMIT) {
            return false;
        }
        String species = species(ctx);
        String sapling = species == null ? null : saplingFor(species);
        return sapling != null && ctx.percepts().inventory().count(sapling) < tree.base().size();
    }

    /**
     * One sapling per stump log (decision: Luiz — 1×1 vs 2×2 falls out of the base size), planted
     * last so saplings gathered during this chop count, and planted from BESIDE the stump.
     *
     * <p>A sapling has no collision shape, so placing one into the cell being stood in
     * <em>succeeds</em> — and collection has usually just walked them onto the stump, where its
     * own drops land, leaving the trunk to grow through them. Too far to reach, get closer;
     * standing on it, step aside — outside the whole footprint, where the tree comes back.
     */
    private TaskStatus replant(BrainContext ctx) {
        if (walkIssued) {
            if (ctx.actuators().mover().state() == MoveState.MOVING) {
                return TaskStatus.RUNNING;
            }
            walkIssued = false;
        }
        if (target == null) {
            if (replantSites.isEmpty()) {
                // The last act: standing beside a sapling is standing inside the tree it becomes.
                if (planted > 0 && !clearOffIssued) {
                    clearOffIssued = true;
                    Pos off = standClear(ctx);
                    if (off != null) {
                        think(ctx, " - stepping clear of the sapling");
                        ctx.actuators().mover().moveTo(off.x(), off.y(), off.z());
                        walkIssued = true;
                        return TaskStatus.RUNNING;
                    }
                }
                if (planted > 0) {
                    ctx.journal().record(Category.BRAIN, "chop", "replanted " + planted + " sapling"
                            + (planted == 1 ? "" : "s"));
                }
                return finish(ctx);
            }
            target = replantSites.poll();
            walked = false;
        }
        String speciesId = species(ctx);
        String sapling = speciesId == null ? null : saplingFor(speciesId);
        if (sapling == null || ctx.percepts().inventory().count(sapling) == 0) {
            // Ran dry mid-pattern or never had one — say which, plainly. A partial square is
            // still planted: on every species but dark oak a lone sapling grows something.
            ctx.journal().record(Category.BRAIN, "chop", planted > 0
                    ? "replanted " + planted + " of " + tree.base().size() + " — out of saplings"
                    : "replant skipped (no sapling in the pack)");
            think(ctx, planted > 0 ? "out of saplings — leaving the rest bare"
                    : "no sapling to plant — leaving it bare");
            target = null;
            replantSites.clear();
            return finish(ctx);
        }
        if (inTrunkColumn(ctx.percepts().position())) {
            return stepOffTheStump(ctx);
        }
        if (ctx.actuators().placer().place(sapling, target)) {
            planted++;
            plantedSites.add(target);
            target = null;
            return TaskStatus.RUNNING;
        }
        if (!walked) {
            walked = true;
            Pos stance = stanceBeside(ctx, target);
            if (stance != null) {
                ctx.actuators().mover().moveTo(stance.x(), stance.y(), stance.z());
                walkIssued = true;
                return TaskStatus.RUNNING;
            }
        }
        target = null; // site blocked or out of reach even after the walk — let it go
        return TaskStatus.RUNNING;
    }

    /**
     * They are standing in the footprint: one step aside, and the site stays armed for the next
     * tick. Nowhere to step abandons the replant — better than a Person entombed in the tree
     * they planted.
     */
    private TaskStatus stepOffTheStump(BrainContext ctx) {
        if (asideTries < REPLANT_ASIDE_TRIES) {
            Pos aside = stanceBeside(ctx, ctx.percepts().position());
            if (aside != null) {
                asideTries++;
                walked = false; // the site keeps its one walk: this step was a step, not a walk
                think(ctx, " - stepping off the stump first");
                ctx.actuators().mover().moveTo(aside.x(), aside.y(), aside.z());
                walkIssued = true;
                return TaskStatus.RUNNING;
            }
        }
        ctx.journal().record(Category.BRAIN, "chop",
                "no room to step off the stump — replant skipped (planted " + planted + ")");
        target = null;
        replantSites.clear();
        return finish(ctx);
    }

    /**
     * Somewhere to stand near {@code site} but not in the stump's footprint: a standable cell
     * within {@link #REPLANT_STANCE_RANGE}, nearest to them right now — so "get close enough to
     * reach it" and "move one to the side" are the same search. Null when the stump is walled in.
     */
    private Pos stanceBeside(BrainContext ctx, Pos site) {
        BlockProbe probe = ctx.percepts().blocks();
        Pos here = ctx.percepts().position();
        Pos best = null;
        long bestDist = Long.MAX_VALUE;
        int r = REPLANT_STANCE_RANGE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Pos cell = new Pos(site.x() + dx, site.y() + dy, site.z() + dz);
                    if (inTrunkColumn(cell) || !standable(probe, cell)) {
                        continue;
                    }
                    long dist = distSq(cell, here);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = cell;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Somewhere to stand at least {@link #REPLANT_CLEAR_OFF} horizontally from every planted
     * site: the nearest standable cell. Null when the world offers nothing — they then finish
     * where they are.
     */
    private Pos standClear(BrainContext ctx) {
        BlockProbe probe = ctx.percepts().blocks();
        Pos here = ctx.percepts().position();
        int r = REPLANT_CLEAR_OFF + 2;
        Pos best = null;
        long bestDist = Long.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Pos cell = new Pos(here.x() + dx, here.y() + dy, here.z() + dz);
                    if (!standable(probe, cell) || !clearOfPlanted(cell)) {
                        continue;
                    }
                    long dist = distSq(cell, here);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = cell;
                    }
                }
            }
        }
        return best;
    }

    /**
     * A walk goal that can actually be arrived at, for a swing at {@code target}: the standable
     * cell within 2 of it nearest to them. Walking at the target CELL asks the pathfinder for an
     * unreachable goal, and the zero-length partial that comes back reads as "no path" and moves
     * nobody. Null when nothing within 2 is standable; the raw target is then the best ask.
     */
    private Pos stanceNear(BrainContext ctx, Pos target) {
        return stanceNear(ctx, target, null);
    }

    /** {@link #stanceNear} with one cell ruled out — the reposition rung's "anywhere but
     *  HERE": the body's step refusals are per-cell, so a different cell is a fresh ask. */
    private Pos stanceNear(BrainContext ctx, Pos target, Pos not) {
        BlockProbe probe = ctx.percepts().blocks();
        Pos here = ctx.percepts().position();
        Pos best = null;
        long bestToTarget = Long.MAX_VALUE;
        long bestToHer = Long.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    Pos cell = new Pos(target.x() + dx, target.y() + dy, target.z() + dz);
                    if (cell.equals(not) || !standable(probe, cell)) {
                        continue;
                    }
                    long toTarget = distSq(cell, target);
                    long toHer = distSq(cell, here);
                    if (toTarget < bestToTarget
                            || (toTarget == bestToTarget && toHer < bestToHer)) {
                        bestToTarget = toTarget;
                        bestToHer = toHer;
                        best = cell;
                    }
                }
            }
        }
        return best;
    }

    /** Whether a cell keeps the clear-off distance from every site a sapling went into. */
    private boolean clearOfPlanted(Pos cell) {
        for (Pos site : plantedSites) {
            if (horizontalDistSq(cell, site) < (long) REPLANT_CLEAR_OFF * REPLANT_CLEAR_OFF) {
                return false;
            }
        }
        return true;
    }

    /** Whether a Person fits standing in this cell: two cells of air over solid footing. */
    private static boolean standable(BlockProbe probe, Pos cell) {
        return probe.at(cell.x(), cell.y(), cell.z()) == BlockKind.AIR
                && probe.at(cell.x(), cell.y() + 1, cell.z()) == BlockKind.AIR
                && isFooting(probe.at(cell.x(), cell.y() - 1, cell.z()));
    }

    private static boolean isFooting(BlockKind kind) {
        return kind == BlockKind.OTHER || kind == BlockKind.LOG || kind == BlockKind.LEAVES;
    }

    // --- transitions -----------------------------------------------------------------------------

    private TaskStatus finish(BrainContext ctx) {
        if (felled > 0) {
            // Forgetting is FINAL, so it is earned by the world, not by a snapshot: re-ask what
            // still stands, base cells included — a stump the STUMP phase could not fell arrives
            // here with a clean descend-time leftStanding, and used to be forgotten standing.
            leftStanding = stillStanding(ctx);
            if (!leftStanding.isEmpty()) {
                return partial(ctx);
            }
            think(ctx, "done with this tree");
            ctx.knowledge().forget(PoiKind.TREE, memory.anchor());
            ctx.claims().release(PoiKind.TREE, memory.anchor());
            return TaskStatus.SUCCESS;
        }
        return unworkable(ctx, "nothing reachable to fell");
    }

    /** Everything of this tree still standing right NOW — {@link #standingLogs} plus the
     *  stump itself, which that method leaves to the STUMP phase. */
    private List<Pos> stillStanding(BrainContext ctx) {
        List<Pos> standing = new ArrayList<>(standingLogs(ctx));
        BlockProbe probe = ctx.percepts().blocks();
        for (Pos base : tree.base()) {
            if (probe.at(base.x(), base.y(), base.z()) == BlockKind.LOG
                    && !standing.contains(base)) {
                standing.add(base);
            }
        }
        return List.copyOf(standing);
    }

    /**
     * Wood felled, wood left — reached only after a whole fresh approach felled nothing, never as
     * a deferral. A SUCCESS: the remnant stands on its stump for a later trip, when leaves have
     * decayed or drops cleared. The memory is KEPT and the anchor briefly avoided so the next
     * errand does not bounce off the same wall.
     */
    private TaskStatus partial(BrainContext ctx) {
        ctx.knowledge().avoid(PoiKind.TREE, memory.anchor(),
                ctx.percepts().time() + PARTIAL_AVOID_TICKS);
        ctx.claims().release(PoiKind.TREE, memory.anchor());
        ctx.journal().record(Category.BRAIN, "chop", "felled " + felled + ", left "
                + leftStanding.size() + " standing on the stump — coming back for the rest");
        think(ctx, "this one has beaten me — leaving it on its stump for now");
        return TaskStatus.SUCCESS;
    }

    /** A real tree that cannot be worked right now: keep the memory, avoid it, fail outright. */
    private TaskStatus unworkable(BrainContext ctx, String why) {
        return unworkable(ctx, why, AVOID_TICKS);
    }

    private TaskStatus unworkable(BrainContext ctx, String why, int avoidTicks) {
        ending = why;
        ctx.knowledge().avoid(PoiKind.TREE, memory.anchor(), ctx.percepts().time() + avoidTicks);
        ctx.claims().release(PoiKind.TREE, memory.anchor()); // only removes what is OURS
        ctx.journal().record(Category.BRAIN, "chop", why + " — avoiding it a while");
        think(ctx, "can't work this tree — " + why);
        return TaskStatus.FAILED;
    }

    private TaskStatus ghost(BrainContext ctx) {
        Pos a = memory.anchor();
        ctx.knowledge().forget(PoiKind.TREE, a);
        ctx.claims().release(PoiKind.TREE, a);
        ending = "no tree here anymore — forgot it";
        ctx.journal().record(Category.BRAIN, "chop",
                "grove gone — forgot TREE (" + a.x() + ", " + a.y() + ", " + a.z() + ")");
        think(ctx, "there's no tree here after all — never mind");
        return TaskStatus.FAILED;
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * One thinking-out-loud line: journaled under the {@code think} event, forwarded to chat when
     * narration is on ({@code /autarkia think}). Present tense, first person; sub-steps start
     * with " - ".
     */
    private void think(BrainContext ctx, String thought) {
        ctx.journal().record(Category.BRAIN, "think", thought);
    }

    /**
     * Ticks a break in flight; {@code true} means this tick was the arm's. Outcome
     * bookkeeping: a reclaim strikes the fallen pillar cell from the body's ledger, a clear
     * re-opens the sightline (the real target stays armed for the next swing), a real target
     * counts felled — or, when the break died under them, skipped; the recheck round re-finds
     * anything skipped that still stands.
     */
    private boolean breakInFlight(BrainContext ctx) {
        if (!breaking) {
            return false;
        }
        switch (ctx.actuators().breaker().state()) {
            case BREAKING:
                return true;
            case FINISHED:
                breaking = false;
                if (reclaimTarget != null) {
                    ctx.actuators().scaffolder().reclaim(reclaimTarget);
                    reclaimTarget = null;
                } else if (clearTarget != null) {
                    clearTarget = null;
                } else if (target != null) {
                    felled++;
                    target = null;
                }
                return true;
            default:
                breaking = false;
                if (reclaimTarget != null) {
                    reclaimTarget = null;
                } else if (clearTarget != null) {
                    clearTarget = null;
                } else if (target != null) {
                    if (phase == Phase.STUMP) {
                        // The refusal path journals "could not fell the stump"; a break that
                        // BEGAN and died was silent, leaving stumps with no journal trail.
                        ctx.journal().record(Category.BRAIN, "chop", "the stump break died at ("
                                + target.x() + ", " + target.y() + ", " + target.z() + ")");
                    }
                    skipped++;
                    target = null;
                }
                return true;
        }
    }

    /** Files a cell into the layer map, creating the map on first use (the recheck's path in). */
    private void addToLayers(Pos cell) {
        if (layers == null) {
            layers = new TreeMap<>(Comparator.reverseOrder());
        }
        layers.computeIfAbsent(cell.y(), y -> new ArrayDeque<>()).add(cell);
    }

    /** Whether a cell stands in one of the trunk's own columns — the base's x,z footprint. */
    private boolean inTrunkColumn(Pos cell) {
        for (Pos base : tree.base()) {
            if (cell.x() == base.x() && cell.z() == base.z()) {
                return true;
            }
        }
        return false;
    }

    /** The center column's current top cell: the newest pillar cell, or the base log itself. */
    private Pos columnTop(Scaffolder scaffolder) {
        List<Pos> placed = scaffolder.placed();
        return placed.isEmpty() ? tree.base().get(0) : placed.get(0);
    }

    /**
     * The blocked cells on the arm's straight path (eye-height approximation over the feet cell)
     * to the target's center, NEAR END first — the core-side twin of the breaker's arm check,
     * used to pick what to CLEAR. Empty means clear; leaves and weeds both read as blockers and
     * both break in a swing.
     *
     * <p>Every hit rather than the first: the nearest blocker is regularly one the arm cannot
     * swing at, and answering "blocked, by that" left the caller with a cell it could neither
     * break nor see past. The ray starts from the middle of the feet cell (core has no fractional
     * position), so it can disagree with the arm; every rung treats a wrong answer as survivable.
     */
    private List<Pos> obstructions(BrainContext ctx, Pos to) {
        BlockProbe probe = ctx.percepts().blocks();
        Pos feet = ctx.percepts().position();
        List<Pos> hits = new ArrayList<>();
        double fx = feet.x() + 0.5;
        double fy = feet.y() + 1.6;
        double fz = feet.z() + 0.5;
        double tx = to.x() + 0.5;
        double ty = to.y() + 0.5;
        double tz = to.z() + 0.5;
        double dist = Math.sqrt((tx - fx) * (tx - fx) + (ty - fy) * (ty - fy) + (tz - fz) * (tz - fz));
        int steps = (int) Math.ceil(dist * 2.0);
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            Pos cell = new Pos((int) Math.floor(fx + (tx - fx) * t),
                    (int) Math.floor(fy + (ty - fy) * t), (int) Math.floor(fz + (tz - fz) * t));
            if (cell.equals(to) || cell.equals(feet)
                    || (cell.x() == feet.x() && cell.y() == feet.y() + 1 && cell.z() == feet.z())) {
                continue;
            }
            BlockKind kind = probe.at(cell.x(), cell.y(), cell.z());
            if (kind != BlockKind.AIR && kind != BlockKind.WATER && !hits.contains(cell)) {
                hits.add(cell);
            }
        }
        return hits;
    }

    /**
     * The climb wants a block and the pack has none: walk to the nearest log DROP in the
     * collection area (once per climb) — the walk-over pickup stocks them, and the next tick's
     * up() has its material. {@code false} when there is nothing to fetch or it was tried.
     */
    private boolean restockForClimb(BrainContext ctx) {
        if (restockTried) {
            return false;
        }
        Pos here = ctx.percepts().position();
        Pos best = null;
        long bestDist = Long.MAX_VALUE;
        for (Drop drop : ctx.percepts().drops()) {
            if (!(drop.itemId().endsWith("_log") || drop.itemId().endsWith("_stem"))
                    || !inArea(drop.pos(), COLLECT_MARGIN)) {
                continue;
            }
            long dist = distSq(here, drop.pos());
            if (dist < bestDist) {
                bestDist = dist;
                best = drop.pos();
            }
        }
        if (best == null) {
            return false;
        }
        restockTried = true;
        think(ctx, " - need a log to build with — grabbing my drops");
        ctx.actuators().mover().moveTo(best.x(), best.y(), best.z());
        walkIssued = true;
        stalls = 0;
        return true;
    }

    /** A carried block to pillar with: their own logs. */
    private String pillarBlock(BrainContext ctx) {
        if (speciesLogId != null && ctx.percepts().inventory().count(speciesLogId) > 0) {
            return speciesLogId;
        }
        for (var entry : ctx.percepts().inventory().occupied()) {
            String id = entry.stack().id();
            if (id.endsWith("_log") || id.endsWith("_stem")) {
                return id;
            }
        }
        return null;
    }

    /** Remembers the chopped species from the first log-ish drop sighted. */
    private void noteSpecies(Drop drop) {
        if (speciesLogId == null
                && (drop.itemId().endsWith("_log") || drop.itemId().endsWith("_stem"))) {
            speciesLogId = drop.itemId();
        }
    }

    /**
     * The species, from sightings or — since the walk-over pickup usually vacuums drops before a
     * percept loop sees them — from their own pack.
     */
    private String species(BrainContext ctx) {
        if (speciesLogId == null) {
            for (var entry : ctx.percepts().inventory().occupied()) {
                String id = entry.stack().id();
                if (id.endsWith("_log") || id.endsWith("_stem")) {
                    speciesLogId = id;
                    break;
                }
            }
        }
        return speciesLogId;
    }

    /**
     * The sapling that regrows this log. String-level vanilla knowledge with the irregular
     * families special-cased — provisional until a compat materials lens exists.
     */
    static String saplingFor(String logId) {
        if (logId.endsWith("mangrove_log")) {
            return "minecraft:mangrove_propagule";
        }
        if (logId.endsWith("crimson_stem")) {
            return "minecraft:crimson_fungus";
        }
        if (logId.endsWith("warped_stem")) {
            return "minecraft:warped_fungus";
        }
        if (logId.endsWith("_log")) {
            return logId.substring(0, logId.length() - "_log".length()) + "_sapling";
        }
        return null;
    }

    /** Seed for the re-scan: the anchor, or a cell just above it (the anchor may be chopped). */
    private Optional<Pos> findSeed(BlockProbe probe) {
        Pos a = memory.anchor();
        for (int dy = 0; dy <= 2; dy++) {
            Pos p = new Pos(a.x(), a.y() + dy, a.z());
            BlockKind kind = probe.at(p.x(), p.y(), p.z());
            if (kind == BlockKind.LOG || kind == BlockKind.LEAVES) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    private boolean inArea(Pos p, int margin) {
        return p.x() >= memory.bounds().min().x() - margin && p.x() <= memory.bounds().max().x() + margin
                && p.y() >= memory.bounds().min().y() - margin && p.y() <= memory.bounds().max().y() + margin
                && p.z() >= memory.bounds().min().z() - margin && p.z() <= memory.bounds().max().z() + margin;
    }

    private static long horizontalDistSq(Pos a, Pos b) {
        long dx = a.x() - b.x();
        long dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    private static long distSq(Pos a, Pos b) {
        long dx = a.x() - b.x();
        long dy = a.y() - b.y();
        long dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
