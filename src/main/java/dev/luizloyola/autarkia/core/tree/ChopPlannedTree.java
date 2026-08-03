package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.BlockBreaker;
import dev.luizloyola.anima.core.brain.act.BreakState;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.act.RiseState;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.RegionGrowth;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.PrimitiveTask;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.autarkia.core.board.Stock;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * The seventh chop's executor: walk the {@link ChopPlan} dance card, never improvise —
 * approach, survey, compile, enter the shaft, ride the mast up
 * ({@link dev.luizloyola.anima.core.brain.act.Riser rise-one}), then work the layers down,
 * mining her own pillar underfoot between them. The card is a pure function of the shape, so
 * resuming after any interruption is re-running the task: the remnant world compiles to the
 * remaining dance.
 *
 * <p>A move that cannot be served is a LEFTOVER, not a retry loop. Leaves are left to decay and
 * nothing is replanted. The exit guarantee: SUCCESS and forget only when nothing the card
 * promised is still standing and nothing was refused — anything less keeps the memory.
 */
public final class ChopPlannedTree implements PrimitiveTask {

    /** Matches {@link ChopPlan}'s arm: swings and digs happen inside this reach of the eyes. */
    private static final double REACH = 4.0;
    private static final double EYE = 1.62;

    /** How close (horizontally) the approach must get before the survey starts. */
    private static final int APPROACH_NEAR = 3;

    /** Probe reads the budgeted survey spends per tick — the old chop's proven rate. */
    private static final int SCAN_READS_PER_TICK = 256;

    /** Ticks to linger waiting for broken-log drops to hop into the pack before rising. */
    private static final int PICKUP_WAIT_TICKS = 40;

    /** Ticks a single walk order may run before the move is declared unservable. */
    private static final int WALK_TIMEOUT_TICKS = 200;

    /**
     * The watchdog: this long with the feet in one cell and no break or rise in flight means
     * some loop is waiting on something that will never come. Generous over every legitimate
     * stationary wait — drops hopping into the pack, a walk order mid-compute.
     */
    private static final int STUCK_TICKS = 300;

    /** How far from the anchor the tail chases this fell's log drops, and how many walks. */
    private static final int GATHER_RADIUS = 10;
    private static final int GATHER_WALKS_MAX = 16;

    /** Per-layer canopy-drop walks before descending — the original "logs on this canopy". */
    private static final int LAYER_GATHER_WALKS = 4;

    /**
     * How long the tail loiters for the dying canopy to rain its wood down. Decay is
     * random-tick, so this scales with the game clock; a felled crown empties well inside it.
     */
    private static final int DECAY_WAIT_TICKS = 1600;

    /** How long a tree that beat this task stays off the producer's menu. */
    private static final int AVOID_TICKS = 2400;

    private enum Phase { APPROACH, SURVEY, ENTER, ASCEND, WORK, GATHER, VERIFY }

    private final Pos anchor;

    private Phase phase = Phase.APPROACH;
    private boolean walkIssued;
    private int walkTicks;
    private RegionGrowth scan;
    private TreeShape.Trunk tree;
    private ChopPlan plan;
    /** Mast cells still to break on the way up, bottom-first (entry pair handled by ENTER). */
    private Deque<Pos> mastAhead;
    /** Break in flight — poll the breaker before doing anything else. */
    private boolean breaking;
    private boolean riseIssued;
    private int pickupWait;
    private int layerIndex;
    private int moveIndex;
    /** The current move's digs still standing, in card order. */
    private Deque<Pos> digsAhead;
    /** Every cell the surveyed tree owns — what the chew-through is allowed to eat. */
    private java.util.Set<Pos> treeBlocks;
    private boolean boostUp;
    private Pos boostCell;
    /** Whether the rise in flight is a boost (stand +1) or a climb up the mast to a layer. */
    private boolean riseForBoost;
    /** Everything the card promised that this run could not serve — the reckoning of the exit. */
    private final List<Pos> leftovers = new ArrayList<>();
    private String ending;
    /**
     * The working axis: the column she climbs and returns to. The trunk's own when the card
     * says {@link ChopPlan#climbsTheTrunk}, the pillar site beside it otherwise, and the entry
     * column when there is no climb at all.
     */
    private int siteX;
    private int siteZ;
    /** Sides of the stump already tried as a doorstep — a real forest walls some of them. */
    private int doorstepsTried;
    /** A failing ascent unwinds first: the pillar is mined back down, every log refunded. */
    private boolean bailing;
    private String bailReason;
    /** Walks spent collecting the fell's drops — the tail's budget. */
    private int gatherWalks;
    /** Ticks spent standing by for the canopy to drop what it holds. */
    private int decayWait;
    /** Walks spent on this layer's canopy drops before descending — Luiz's original step 4. */
    private int layerGatherWalks;
    /** The return-to-axis walk gets one fallback: down to the site's ground, then re-climb. */
    private boolean axisFallback;
    /** Where the feet last were, and since when — the stuck watchdog's memory. */
    private Pos lastSpot;
    private long restingSince = -1;

    public ChopPlannedTree(Pos anchor) {
        this.anchor = anchor;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        // Selection is commitment: the claim heartbeats every tick so a dead claimant lapses
        // in one TTL, and a rival's live claim ends this task before it swings once.
        if (!ctx.claims().claim(Pois.TREE, anchor, ctx.percepts().time())) {
            ending = "the tree at " + shortPos(anchor) + " is claimed by someone else";
            ctx.journal().record(Category.BRAIN, "chop", "FAILED — " + ending);
            return TaskStatus.FAILED; // contention, not brokenness: no avoidance
        }
        // The stuck watchdog: feet in one cell with no arm or rise working, for longer than
        // any legitimate wait — end the run outright; a re-order replans from the remnant.
        Pos here = ctx.percepts().position();
        long now = ctx.percepts().time();
        boolean busy = breaking || riseIssued
                || ctx.actuators().breaker().state() == BreakState.BREAKING
                || ctx.actuators().riser().state() == RiseState.RISING;
        boolean moved = !here.equals(lastSpot);
        if (moved) {
            walkTicks = 0; // a walk that is moving is not stuck — sprint cannot expire it
        }
        if (moved || busy || phase == Phase.SURVEY || phase == Phase.GATHER) {
            lastSpot = here;
            restingSince = now;
        } else if (restingSince > 0 && now - restingSince > STUCK_TICKS) {
            return fail(ctx, "stuck at " + shortPos(here) + " during " + phase
                    + " — nothing moved for " + STUCK_TICKS + " ticks");
        }
        return switch (phase) {
            case APPROACH -> approach(ctx);
            case SURVEY -> survey(ctx);
            case ENTER -> enter(ctx);
            case ASCEND -> ascend(ctx);
            case WORK -> work(ctx);
            case GATHER -> gather(ctx);
            case VERIFY -> verify(ctx);
        };
    }

    private TaskStatus approach(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        long dist = TreeShape.horizontalDistSq(here, anchor);
        if (dist <= (long) APPROACH_NEAR * APPROACH_NEAR) {
            if (walkIssued) {
                ctx.actuators().mover().stop();
                walkIssued = false;
            }
            phase = Phase.SURVEY;
            return TaskStatus.RUNNING;
        }
        if (!walkIssued) {
            ctx.actuators().mover().moveTo(anchor.x(), anchor.y(), anchor.z());
            walkIssued = true;
            walkTicks = 0;
            return TaskStatus.RUNNING;
        }
        if (ctx.actuators().mover().state() == MoveState.MOVING && ++walkTicks < WALK_TIMEOUT_TICKS) {
            return TaskStatus.RUNNING;
        }
        walkIssued = false;
        if (TreeShape.horizontalDistSq(ctx.percepts().position(), anchor)
                <= (long) (APPROACH_NEAR * 2) * (APPROACH_NEAR * 2)) {
            phase = Phase.SURVEY;
            return TaskStatus.RUNNING;
        }
        return fail(ctx, "could not reach the tree at " + shortPos(anchor));
    }

    private TaskStatus survey(BrainContext ctx) {
        BlockProbe probe = ctx.percepts().blocks();
        if (scan == null) {
            Optional<Pos> seed = findSeed(probe);
            if (seed.isEmpty()) {
                return ghost(ctx);
            }
            scan = new RegionGrowth(TreeRule.INSTANCE, seed.get(),
                    probe.at(seed.get().x(), seed.get().y(), seed.get().z()), ctx.profile());
        }
        scan.step(probe, SCAN_READS_PER_TICK);
        if (!scan.isDone()) {
            return TaskStatus.RUNNING;
        }
        SplitReport report = SplitReport.of(scan.result().blocks(), probe);
        tree = nearestTrunk(report.trees());
        if (tree != null
                && TreeShape.horizontalDistSq(tree.base().get(0), anchor) > 9) {
            tree = null; // a NEIGHBOUR'S trunk in the same mass — not the tree this claim is for
        }
        boolean grounded = tree != null;
        if (tree == null) {
            tree = remnantTrunk(scan.result().blocks());
            if (tree != null) {
                ctx.journal().record(Category.BRAIN, "chop", "resuming a half-felled remnant ("
                        + tree.logCount() + " logs still up)");
            }
        }
        if (tree == null) {
            return ghost(ctx);
        }
        treeBlocks = new HashSet<>(tree.leaves());
        treeBlocks.addAll(tree.base());
        treeBlocks.addAll(tree.column());
        treeBlocks.addAll(tree.branches());
        // A remnant's lowest log hangs in mid-air, so the card cannot take it for the floor: it
        // is told the level she surveyed from, which she walked to and can therefore walk on. A
        // real tree's stump is the floor.
        plan = grounded ? ChopPlan.of(tree)
                : ChopPlan.of(tree, ctx.percepts().position().y());
        mastAhead = new ArrayDeque<>(plan.mast());
        Pos site = plan.mast().isEmpty() ? plan.entry() : plan.mast().get(0);
        siteX = site.x();
        siteZ = site.z();
        ctx.journal().record(Category.BRAIN, "chop",
                "the card says "
                        + (plan.chopCount() + plan.mast().size() + plan.ascentChops())
                        + (plan.climbsTheTrunk() ? " chops up its own trunk, " : " chops, ")
                        + plan.digCount() + " digs, "
                        + plan.layers().size() + " layers"
                        + (plan.climbsAboveTheMast() == 0 ? ""
                                : " (" + plan.climbsAboveTheMast() + " climbed mid-fell)")
                        + (plan.refusals().isEmpty() ? ""
                                : ", " + plan.refusals().size() + " refused"));
        phase = Phase.ENTER;
        return TaskStatus.RUNNING;
    }

    /**
     * Reach the pillar site — the column BESIDE the tree the whole dance works from. Nothing of
     * the trunk is touched on the way in: the grounding invariant is that the tree floats at no
     * instant of the dance. A mast-free card (a short tree) skips straight to the work.
     */
    private TaskStatus enter(BrainContext ctx) {
        if (pollBreak(ctx)) {
            return TaskStatus.RUNNING;
        }
        if (plan.climbsTheTrunk()) {
            return enterTheTrunk(ctx);
        }
        Pos feet = ctx.percepts().position();
        // A mast-free tree still needs its site PREPARED when any layer sits above ground reach:
        // the climbs work from that column, and a site buried in low canopy has no standable
        // cell to return to.
        boolean needSite = !plan.mast().isEmpty()
                || (!plan.layers().isEmpty()
                        && plan.layers().get(0).y() > plan.entry().y() + 1);
        if (!needSite || (feet.x() == siteX && feet.z() == siteZ)) {
            ctx.actuators().mover().stop();
            walkIssued = false;
            pickupWait = 0;
            if (plan.mast().isEmpty()) {
                phase = Phase.WORK;
                layerIndex = 0;
                moveIndex = -1;
            } else {
                phase = Phase.ASCEND;
            }
            return TaskStatus.RUNNING;
        }
        BlockProbe probe = ctx.percepts().blocks();
        Pos siteGround = plan.mast().isEmpty()
                ? new Pos(siteX, plan.entry().y(), siteZ) : plan.mast().get(0);
        for (int dy = 0; dy <= 1; dy++) {
            Pos c = new Pos(siteX, siteGround.y() + dy, siteZ);
            BlockKind k = probe.at(c.x(), c.y(), c.z());
            if ((k == BlockKind.LEAVES || k == BlockKind.LOG) && treeBlocks.contains(c)
                    && tryArm(ctx, c)) {
                return TaskStatus.RUNNING;
            }
        }
        if (!walkIssued) {
            ctx.actuators().mover().moveTo(siteGround.x(), siteGround.y(), siteGround.z());
            walkIssued = true;
            walkTicks = 0;
            return TaskStatus.RUNNING;
        }
        if (ctx.actuators().mover().state() == MoveState.MOVING
                && ++walkTicks < WALK_TIMEOUT_TICKS) {
            return TaskStatus.RUNNING;
        }
        walkIssued = false;
        Pos blocker = chewMark(ctx, siteGround);
        if (!blocker.equals(siteGround) && ctx.actuators().breaker().begin(blocker)) {
            breaking = true;
            return TaskStatus.RUNNING;
        }
        return fail(ctx, "cannot reach the pillar site at " + shortPos(siteGround));
    }

    /**
     * The other way in, for a plain tree the card sends up its own trunk: cut the DOORWAY — the
     * stump and the log above it — from a standable cell beside the tree, then step into the
     * shaft. Those two logs are the ascent's seed money. That is what lets a Person with an
     * empty pack fell anything at all.
     *
     * <p>Swing at the doorway only from a FACE-adjacent cell — a corner approach gets the swing
     * blocked by the block it is reaching for. Find each side's standing level inside a
     * two-block slope window, because hillsides offer no doorstep at the stump's height. Chew
     * the tree's own leaves out of the doorstep first (a sapling oak's canopy fills the cells
     * around its trunk), and try all four sides before giving up — a real forest roots trees
     * against cliffs and thickets.
     */
    private TaskStatus enterTheTrunk(BrainContext ctx) {
        BlockProbe probe = ctx.percepts().blocks();
        Pos doorway = null;
        while (!mastAhead.isEmpty() && mastAhead.peek().y() <= plan.entry().y() + 1) {
            Pos cell = mastAhead.peek();
            if (probe.at(cell.x(), cell.y(), cell.z()) == BlockKind.LOG) {
                doorway = cell;
                break;
            }
            mastAhead.poll();
        }
        if (doorway != null) {
            if (besideEntry(ctx)) {
                walkIssued = false;
                if (tryArm(ctx, doorway)) {
                    return TaskStatus.RUNNING;
                }
                return fail(ctx, "the arm refused the way in " + armForensics(ctx, doorway));
            }
            Pos stand = nearestDoorstep(ctx, doorstepsTried);
            for (int dy = 0; dy <= 1; dy++) {
                Pos c = new Pos(stand.x(), stand.y() + dy, stand.z());
                BlockKind k = probe.at(c.x(), c.y(), c.z());
                if ((k == BlockKind.LEAVES || k == BlockKind.LOG) && treeBlocks.contains(c)
                        && inReach(ctx, c) && tryArm(ctx, c)) {
                    return TaskStatus.RUNNING;
                }
            }
            if (!walkIssued) {
                ctx.actuators().mover().moveTo(stand.x(), stand.y(), stand.z());
                walkIssued = true;
                walkTicks = 0;
                return TaskStatus.RUNNING;
            }
            if (ctx.actuators().mover().state() == MoveState.MOVING
                    && ++walkTicks < WALK_TIMEOUT_TICKS) {
                return TaskStatus.RUNNING;
            }
            walkIssued = false;
            if (besideEntry(ctx)) {
                return beginBreak(ctx, doorway, "the way in");
            }
            Pos blocker = chewMark(ctx, stand);
            if (!blocker.equals(stand) && ctx.actuators().breaker().begin(blocker)) {
                breaking = true;
                return TaskStatus.RUNNING;
            }
            if (++doorstepsTried < 4) {
                return TaskStatus.RUNNING;
            }
            return fail(ctx, "cannot stand beside the doorway at " + shortPos(plan.entry()));
        }
        Pos feet = ctx.percepts().position();
        if (feet.x() == plan.entry().x() && feet.z() == plan.entry().z()) {
            ctx.actuators().mover().stop();
            walkIssued = false;
            pickupWait = 0;
            phase = Phase.ASCEND;
            return TaskStatus.RUNNING;
        }
        if (!walkIssued) {
            ctx.actuators().mover().moveTo(
                    plan.entry().x(), plan.entry().y(), plan.entry().z());
            walkIssued = true;
            walkTicks = 0;
            return TaskStatus.RUNNING;
        }
        if (ctx.actuators().mover().state() == MoveState.MOVING
                && ++walkTicks < WALK_TIMEOUT_TICKS) {
            return TaskStatus.RUNNING;
        }
        walkIssued = false;
        // Hemmed beside the doorway (a low canopy walls the one-block step): chew the tree's
        // own cell between her and the shaft, then try the step again — each bite is finite.
        Pos hem = chewMark(ctx, plan.entry());
        if (!hem.equals(plan.entry()) && ctx.actuators().breaker().begin(hem)) {
            breaking = true;
            return TaskStatus.RUNNING;
        }
        return fail(ctx, "could not step into the shaft at " + shortPos(plan.entry()));
    }

    /** Whether her feet are face-adjacent to the entry column, within the slope window. */
    private boolean besideEntry(BrainContext ctx) {
        Pos feet = ctx.percepts().position();
        return Math.abs(feet.x() - plan.entry().x())
                + Math.abs(feet.z() - plan.entry().z()) == 1
                && Math.abs(feet.y() - plan.entry().y()) <= 2;
    }

    /**
     * The nearest standable cell face-adjacent to the entry — the doorstep, each side's real
     * level found inside a two-block slope window. A cell holding the tree's own leaves still
     * counts: the chew is what makes it standable.
     */
    private Pos nearestDoorstep(BrainContext ctx, int skip) {
        BlockProbe probe = ctx.percepts().blocks();
        Pos feet = ctx.percepts().position();
        List<long[]> ranked = new ArrayList<>();
        for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int x = plan.entry().x() + side[0];
            int z = plan.entry().z() + side[1];
            for (int y = plan.entry().y() + 2; y >= plan.entry().y() - 2; y--) {
                BlockKind at = probe.at(x, y, z);
                boolean standable = (at == BlockKind.AIR || at == BlockKind.LEAVES)
                        && probe.at(x, y - 1, z) != BlockKind.AIR;
                if (!standable) {
                    continue;
                }
                long dx = x - feet.x();
                long dz = z - feet.z();
                long dy = y - plan.entry().y();
                ranked.add(new long[] {dx * dx + dz * dz + dy * dy, x, y, z});
                break; // the topmost standable spot is this side's doorstep
            }
        }
        ranked.sort(java.util.Comparator.comparingLong(r -> r[0]));
        if (ranked.isEmpty()) {
            return new Pos(plan.entry().x() + 1, plan.entry().y(), plan.entry().z());
        }
        long[] pick = ranked.get(Math.min(skip, ranked.size() - 1));
        return new Pos((int) pick[1], (int) pick[2], (int) pick[3]);
    }

    /**
     * Ride the mast, whichever column the card chose: clear this tree's own matter out of the
     * headroom, then rise one on a carried log, until her feet reach the card's last rung. The
     * loop is the same in both modes; only the ECONOMY differs. Up the trunk, the block cleared
     * overhead is a log, so the climb is the fell and the wait for the drop to hop into the pack
     * is the whole transaction. Beside the tree, every rung is a log she brought — the grounded
     * design's price for never floating a branch, paid back by the fell's own harvest.
     */
    private TaskStatus ascend(BrainContext ctx) {
        if (pollBreak(ctx)) {
            return TaskStatus.RUNNING;
        }
        if (riseIssued) {
            switch (ctx.actuators().riser().state()) {
                case RISING -> {
                    return TaskStatus.RUNNING;
                }
                case RISEN, IDLE -> riseIssued = false;
                case FAILED -> riseIssued = false; // ask once more; the body caps its retries
            }
        }
        Pos feet = ctx.percepts().position();
        if (bailing) {
            // The ascent is over but the pillar is hers: mine it back down before failing,
            // so the spent logs come home and nothing of hers is left standing either.
            if (feet.y() > plan.mast().get(0).y()
                    && feet.x() == siteX && feet.z() == siteZ) {
                Pos below = new Pos(feet.x(), feet.y() - 1, feet.z());
                if (ctx.percepts().blocks().at(below.x(), below.y(), below.z())
                        != BlockKind.AIR) {
                    return beginBreak(ctx, below, "the pillar, refunded");
                }
                return TaskStatus.RUNNING;
            }
            return fail(ctx, bailReason);
        }
        int targetFeet = plan.mast().get(plan.mast().size() - 1).y() + 1;
        if (feet.y() >= targetFeet) {
            phase = Phase.WORK;
            layerIndex = 0;
            moveIndex = -1;
            return TaskStatus.RUNNING;
        }
        BlockProbe probe = ctx.percepts().blocks();
        for (int dy = 1; dy <= 2; dy++) {
            Pos c = new Pos(siteX, feet.y() + dy, siteZ);
            BlockKind k = probe.at(c.x(), c.y(), c.z());
            if ((k == BlockKind.LEAVES || k == BlockKind.LOG) && treeBlocks.contains(c)) {
                if (tryArm(ctx, c)) {
                    return TaskStatus.RUNNING;
                }
                return bailOut("cannot clear the pillar's headroom at " + shortPos(c));
            }
        }
        String log = carriedLog(ctx);
        if (log == null) {
            if (++pickupWait <= PICKUP_WAIT_TICKS) {
                return TaskStatus.RUNNING;
            }
            return bailOut(plan.climbsTheTrunk()
                    ? "out of logs mid-shaft — the wood she just cut never reached the pack"
                    : "out of logs mid-pillar — a tree this tall needs "
                            + plan.mast().size() + " carried");
        }
        pickupWait = 0;
        if (ctx.actuators().riser().up(log)) {
            riseIssued = true;
            return TaskStatus.RUNNING;
        }
        return bailOut("the rise refused at " + shortPos(feet));
    }

    /** Flip the ascent into its unwind: the failure is delivered once the pillar is down. */
    private TaskStatus bailOut(String why) {
        bailing = true;
        bailReason = why;
        return TaskStatus.RUNNING;
    }

    /**
     * Work the card's layers top-down, mining the pillar underfoot between them — which is the
     * harvest of her own placed wood. A move that cannot be served goes to {@link #leftovers},
     * not a retry loop.
     */
    private TaskStatus work(BrainContext ctx) {
        if (pollBreak(ctx)) {
            return TaskStatus.RUNNING;
        }
        if (riseIssued) {
            RiseState rise = ctx.actuators().riser().state();
            if (rise == RiseState.RISING) {
                return TaskStatus.RUNNING;
            }
            riseIssued = false;
            if (rise == RiseState.RISEN) {
                boostUp = riseForBoost;
            } else if (riseForBoost) {
                giveUpMove(ctx, "the boost refused");
            } else {
                return fail(ctx, "the climb refused on the way to a high layer");
            }
        }
        if (layerIndex >= plan.layers().size()) {
            return descendToGround(ctx);
        }
        ChopPlan.Layer layer = plan.layers().get(layerIndex);
        Pos feet = ctx.percepts().position();
        boolean onAxis = feet.x() == siteX && feet.z() == siteZ;
        if (moveIndex < 0) {
            // Between layers: back to center, then to the layer's height — mining the pillar
            // underfoot on the way down, RISING on her own logs on the way up: a layer above the
            // trunk top is the card's mast extension, and walking can never gain that height.
            if (feet.y() != layer.y()) {
                if (!onAxis) {
                    return walkToAxis(ctx, "the mast at layer " + layer.y());
                }
                walkIssued = false;
                if (feet.y() > layer.y()) {
                    Pos below = new Pos(feet.x(), feet.y() - 1, feet.z());
                    if (ctx.percepts().blocks().at(below.x(), below.y(), below.z())
                            != BlockKind.AIR) {
                        return beginBreak(ctx, below, "the pillar underfoot");
                    }
                    // Air below the feet cell, yet not falling: a neighbouring block's edge
                    // is holding her up (the recurring stuck-at-height). Mine the supporter —
                    // it is tree wood or her own pillar, so it is harvest either way — and
                    // gravity settles her into the shaft.
                    for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        Pos support = new Pos(feet.x() + side[0], feet.y() - 1,
                                feet.z() + side[1]);
                        BlockKind kind = ctx.percepts().blocks()
                                .at(support.x(), support.y(), support.z());
                        if (kind != BlockKind.AIR && tryArm(ctx, support)) {
                            return TaskStatus.RUNNING;
                        }
                    }
                    return TaskStatus.RUNNING; // mid-fall between pillar cells
                }
                return climbOne(ctx, "layer " + layer.y());
            }
            moveIndex = 0;
            return TaskStatus.RUNNING;
        }
        if (moveIndex >= layer.moves().size()) {
            // A branch broken outward drops onto the canopy SHE is STANDING ON, where the ground
            // sweep after the fell can never reach it — so it is collected here, at its own
            // layer, before the descent: bounded walks, this tree's spread only.
            if (layerGatherWalks < LAYER_GATHER_WALKS) {
                Pos feetNow = ctx.percepts().position();
                Pos dropTarget = null;
                long best = Long.MAX_VALUE;
                for (var drop : ctx.percepts().drops()) {
                    if (!Stock.LOGS.matches(drop.itemId())
                            || Math.abs(drop.pos().y() - feetNow.y()) > 2) {
                        continue;
                    }
                    if (TreeShape.horizontalDistSq(drop.pos(),
                            new Pos(siteX, feetNow.y(), siteZ)) > 100) {
                        continue;
                    }
                    long toMe = TreeShape.horizontalDistSq(drop.pos(), feetNow);
                    if (toMe < best) {
                        best = toMe;
                        dropTarget = drop.pos();
                    }
                }
                if (dropTarget != null) {
                    if (!walkIssued) {
                        ctx.actuators().mover().moveTo(
                                dropTarget.x(), dropTarget.y(), dropTarget.z());
                        walkIssued = true;
                        walkTicks = 0;
                        layerGatherWalks++;
                        return TaskStatus.RUNNING;
                    }
                    if (ctx.actuators().mover().state() == MoveState.MOVING
                            && ++walkTicks < WALK_TIMEOUT_TICKS) {
                        return TaskStatus.RUNNING;
                    }
                    walkIssued = false;
                    return TaskStatus.RUNNING;
                }
            }
            walkIssued = false;
            layerGatherWalks = 0;
            layerIndex++;
            moveIndex = -1;
            return TaskStatus.RUNNING;
        }
        ChopPlan.Move move = layer.moves().get(moveIndex);
        BlockProbe probe = ctx.percepts().blocks();
        if (digsAhead == null) {
            digsAhead = new ArrayDeque<>(move.digs());
        }
        Pos target = move.target();
        if (probe.at(target.x(), target.y(), target.z()) == BlockKind.AIR) {
            // The mark is down, but the card charged this move with en-route WOOD (bonus chops
            // riding the digs list). Those are promised logs, not access: break what still
            // stands before the move closes, or the verify finds wood nobody was assigned.
            while (digsAhead != null && !digsAhead.isEmpty()) {
                Pos dig = digsAhead.peek();
                if (probe.at(dig.x(), dig.y(), dig.z()) != BlockKind.LOG) {
                    digsAhead.poll();
                    continue;
                }
                if (tryArm(ctx, dig)) {
                    return TaskStatus.RUNNING;
                }
                leftovers.add(dig);
                ctx.journal().record(Category.BRAIN, "chop", "left " + shortPos(dig)
                        + " standing — a promised bonus chop out of the arm's answers "
                        + armForensics(ctx, dig));
                digsAhead.poll();
            }
            finishMove(ctx);
            return TaskStatus.RUNNING;
        }
        // ARM first: the chew-chained swing from wherever she stands is the cheapest answer, and
        // begin() is the one authority on whether it lands — demanding the exact stand for
        // swings the arm could already serve is what the "no way to the stand" partials were.
        if ((!move.boost() || boostUp) && tryArm(ctx, target)) {
            return TaskStatus.RUNNING;
        }
        if (!atStand(ctx, move)) {
            Pos stand = move.stand();
            if (stand.x() == siteX && stand.z() == siteZ
                    && stand.y() > feet.y() && onAxis) {
                // The stand is the mast itself, above her: this layer rides the extension.
                return climbOne(ctx, "the stand at " + shortPos(stand));
            }
            for (int dy = 0; dy <= 1; dy++) {
                Pos c = new Pos(stand.x(), stand.y() + dy, stand.z());
                BlockKind k = probe.at(c.x(), c.y(), c.z());
                // On the SITE column a log is either the tree's or her own pillar — a gather can
                // bring her back at height with no descent to reclaim it — and either way it is
                // hers to bite.
                boolean hers = treeBlocks.contains(c)
                        || (c.x() == siteX && c.z() == siteZ);
                if ((k == BlockKind.LEAVES || k == BlockKind.LOG) && hers && tryArm(ctx, c)) {
                    return TaskStatus.RUNNING;
                }
            }
            while (!digsAhead.isEmpty()) {
                Pos dig = digsAhead.peek();
                if (probe.at(dig.x(), dig.y(), dig.z()) == BlockKind.AIR) {
                    digsAhead.poll();
                    continue;
                }
                if (inReach(ctx, dig) && tryArm(ctx, dig)) {
                    return TaskStatus.RUNNING;
                }
                break;
            }
            if (!walkIssued) {
                ctx.actuators().mover().moveTo(stand.x(), stand.y(), stand.z());
                walkIssued = true;
                walkTicks = 0;
                return TaskStatus.RUNNING;
            }
            if (ctx.actuators().mover().state() == MoveState.MOVING
                    && ++walkTicks < WALK_TIMEOUT_TICKS) {
                return TaskStatus.RUNNING;
            }
            walkIssued = false;
            if (!atStand(ctx, move)) {
                giveUpMove(ctx, "no way to the stand at " + shortPos(stand)
                        + " " + armForensics(ctx, target));
            }
            return TaskStatus.RUNNING;
        }
        walkIssued = false;
        // At the stand. Digs still standing and in reach are the swing's line; digs beyond
        // reach were the tunnel here, and standing here proves that access — drop them.
        while (!digsAhead.isEmpty()) {
            Pos dig = digsAhead.peek();
            if (probe.at(dig.x(), dig.y(), dig.z()) == BlockKind.AIR || !inReach(ctx, dig)) {
                digsAhead.poll();
                continue;
            }
            if (tryArm(ctx, dig)) {
                return TaskStatus.RUNNING;
            }
            giveUpMove(ctx, "the arm refused the way through " + armForensics(ctx, dig));
            return TaskStatus.RUNNING;
        }
        if (move.boost() && !boostUp) {
            String log = carriedLog(ctx);
            if (log == null || !ctx.actuators().riser().up(log)) {
                giveUpMove(ctx, "no boost from " + shortPos(move.stand()));
                return TaskStatus.RUNNING;
            }
            riseForBoost = true;
            riseIssued = true;
            return TaskStatus.RUNNING;
        }
        if (tryArm(ctx, target)) {
            return TaskStatus.RUNNING;
        }
        giveUpMove(ctx, "the arm refused the mark " + armForensics(ctx, target));
        return TaskStatus.RUNNING;
    }

    /**
     * One rise on her own log toward something above — a climb, not a boost. It CHEWS first, for
     * the same reason the ascent does: a rise needs the two cells overhead empty, and in a
     * tree's column that is usually one of its leaves. Sizing the mast to the arm moved the
     * climbing here from the ascent, and one leaf at head height stopped a Person dead on her
     * own pillar with an axe and seventy-nine logs in the pack.
     */
    private TaskStatus climbOne(BrainContext ctx, String toward) {
        Pos feet = ctx.percepts().position();
        BlockProbe probe = ctx.percepts().blocks();
        for (int dy = 1; dy <= 2; dy++) {
            Pos c = new Pos(siteX, feet.y() + dy, siteZ);
            BlockKind k = probe.at(c.x(), c.y(), c.z());
            if ((k == BlockKind.LEAVES || k == BlockKind.LOG) && treeBlocks.contains(c)) {
                if (tryArm(ctx, c)) {
                    return TaskStatus.RUNNING;
                }
                return fail(ctx, "cannot clear the climb's headroom at " + shortPos(c)
                        + " " + armForensics(ctx, c));
            }
        }
        String log = carriedLog(ctx);
        if (log == null) {
            return fail(ctx, "no log to climb on toward " + toward);
        }
        if (ctx.actuators().riser().up(log)) {
            riseForBoost = false;
            riseIssued = true;
            return TaskStatus.RUNNING;
        }
        return fail(ctx, "the climb refused toward " + toward + " — overhead "
                + shortPos(new Pos(siteX, feet.y() + 1, siteZ)) + " is "
                + probe.at(siteX, feet.y() + 1, siteZ) + ", "
                + shortPos(new Pos(siteX, feet.y() + 2, siteZ)) + " is "
                + probe.at(siteX, feet.y() + 2, siteZ));
    }

    /**
     * Walk back onto the site column, aiming at its HIGHEST STANDABLE CELL within a jump of her,
     * probed from the world: atop the pillar mid-descent, atop the remnant after mine-below,
     * plain ground when nothing stands. Every computed formula (layer height, her height, the
     * ground cell) had a moment where it pointed inside her own pillar or into mid-air.
     */
    private TaskStatus walkToAxis(BrainContext ctx, String why) {
        if (!walkIssued) {
            Pos feet = ctx.percepts().position();
            BlockProbe probe = ctx.percepts().blocks();
            int goalY = plan.entry().y();
            for (int y = feet.y() + 1; y >= plan.entry().y(); y--) {
                if (probe.at(siteX, y, siteZ) == BlockKind.AIR
                        && probe.at(siteX, y - 1, siteZ) != BlockKind.AIR) {
                    goalY = y;
                    break;
                }
            }
            ctx.actuators().mover().moveTo(siteX, goalY, siteZ);
            walkIssued = true;
            walkTicks = 0;
            return TaskStatus.RUNNING;
        }
        if (ctx.actuators().mover().state() == MoveState.MOVING
                && ++walkTicks < WALK_TIMEOUT_TICKS) {
            return TaskStatus.RUNNING;
        }
        walkIssued = false;
        Pos now = ctx.percepts().position();
        if (!(now.x() == siteX && now.z() == siteZ)) {
            // A pillar remnant two or more cells tall cannot be MOUNTED from the ground: "no
            // path" was the pathfinder being right. Bite it down from beside, her own logs
            // dropped and refunded, until its top is one jump up; a later layer climbs it back
            // on the same wood.
            BlockProbe siteProbe = ctx.percepts().blocks();
            int solidTop = Integer.MIN_VALUE;
            for (int y = now.y() + 3; y >= plan.entry().y(); y--) {
                if (siteProbe.at(siteX, y, siteZ) != BlockKind.AIR) {
                    solidTop = y;
                    break;
                }
            }
            if (solidTop > now.y()) {
                Pos shave = new Pos(siteX, solidTop, siteZ);
                Pos mark = chewMark(ctx, shave);
                if (ctx.actuators().breaker().begin(mark.equals(shave) ? shave : mark)) {
                    breaking = true;
                    return TaskStatus.RUNNING;
                }
            }
            // The site column can also be hemmed by the tree's own canopy — bite that too.
            for (int y = now.y() + 2; y >= plan.entry().y(); y--) {
                Pos c = new Pos(siteX, y, siteZ);
                BlockKind k = siteProbe.at(c.x(), c.y(), c.z());
                if ((k == BlockKind.LEAVES || k == BlockKind.LOG) && treeBlocks.contains(c)
                        && tryArm(ctx, c)) {
                    return TaskStatus.RUNNING;
                }
            }
            if (!axisFallback) {
                // One more try from wherever the first walk ended — a drop chase can strand
                // her on a canopy pocket whose first path attempt fails mid-decay.
                axisFallback = true;
                return TaskStatus.RUNNING;
            }
            return fail(ctx, "could not return to " + why);
        }
        axisFallback = false;
        return TaskStatus.RUNNING;
    }

    /**
     * After the last layer: go back to the working column and mine whatever pillar still stands
     * there, down to the ground.
     *
     * <p>Mining only what happened to be under HER abandoned the pillar, since the last move of
     * a fell almost never leaves her on the axis, and the verify's own-log sweep sits behind the
     * gather and the decay loiter, so the wood came home a minute or more later. The return is
     * best-effort — the tree is already down, so a walk that cannot make it is not worth failing
     * a finished fell over.
     */
    private TaskStatus descendToGround(BrainContext ctx) {
        if (pollBreak(ctx)) {
            return TaskStatus.RUNNING;
        }
        Pos feet = ctx.percepts().position();
        boolean onAxis = feet.x() == siteX && feet.z() == siteZ;
        if (onAxis && feet.y() > plan.entry().y()) {
            Pos below = new Pos(feet.x(), feet.y() - 1, feet.z());
            if (ctx.percepts().blocks().at(below.x(), below.y(), below.z()) != BlockKind.AIR) {
                return beginBreak(ctx, below, "the last of the pillar");
            }
        }
        if (!onAxis && pillarStands(ctx)) {
            if (!walkIssued) {
                ctx.actuators().mover().moveTo(siteX, plan.entry().y(), siteZ);
                walkIssued = true;
                walkTicks = 0;
                return TaskStatus.RUNNING;
            }
            if (ctx.actuators().mover().state() == MoveState.MOVING
                    && ++walkTicks < WALK_TIMEOUT_TICKS) {
                return TaskStatus.RUNNING;
            }
            walkIssued = false;
            // Within arm's length is as good as underfoot — a rung she can reach from beside
            // the column comes down without the walk landing exactly.
            Pos rung = lowestOwnRung(ctx);
            if (rung != null && inReach(ctx, rung) && tryArm(ctx, rung)) {
                return TaskStatus.RUNNING;
            }
            ctx.journal().record(Category.BRAIN, "chop", "could not get back to the pillar at "
                    + shortPos(new Pos(siteX, plan.entry().y(), siteZ))
                    + " — leaving it to the verify sweep");
        }
        walkIssued = false;
        phase = Phase.GATHER;
        gatherWalks = 0;
        return TaskStatus.RUNNING;
    }

    private boolean pillarStands(BrainContext ctx) {
        return lowestOwnRung(ctx) != null;
    }

    /** The lowest log still standing in the working column, ground upward — or null. */
    private Pos lowestOwnRung(BrainContext ctx) {
        BlockProbe probe = ctx.percepts().blocks();
        int top = plan.entry().y() + plan.mast().size() + 2;
        for (ChopPlan.Layer layer : plan.layers()) {
            top = Math.max(top, layer.y());
        }
        for (int y = plan.entry().y(); y <= top; y++) {
            if (probe.at(siteX, y, siteZ) == BlockKind.LOG) {
                return new Pos(siteX, y, siteZ);
            }
        }
        return null;
    }

    /**
     * The tail's first half: the fell's own harvest comes home. Walk to each log drop near the
     * anchor until none remain or the budget runs out (drops in a creek can drift), picked up by
     * the body's own walk-over. Leaves and saplings stay where decay puts them; this is about
     * the WOOD the card promised.
     */
    private TaskStatus gather(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        Pos nearest = null;
        long bestDist = Long.MAX_VALUE;
        for (var drop : ctx.percepts().drops()) {
            if (!Stock.LOGS.matches(drop.itemId())
                    || !dev.luizloyola.anima.core.brain.task.Flocks.gatherable(drop.pos(), ctx)) {
                continue;
            }
            long dist = TreeShape.horizontalDistSq(drop.pos(), anchor);
            if (dist > (long) GATHER_RADIUS * GATHER_RADIUS) {
                continue; // some other fell's litter — not this dance's to chase
            }
            long toMe = TreeShape.horizontalDistSq(drop.pos(), here);
            if (toMe < bestDist) {
                bestDist = toMe;
                nearest = drop.pos();
            }
        }
        if (nearest == null || gatherWalks >= GATHER_WALKS_MAX) {
            // Nothing walkable right now — but the dying canopy may still HOLD wood, and it
            // rains down as decay eats the leaves. Loiter for it (bounded), gathering each
            // log as it lands, and only call the tail done when the crown is empty or the
            // patience is spent.
            if (nearest == null && gatherWalks < GATHER_WALKS_MAX
                    && perchedWoodRemains(ctx) && ++decayWait <= DECAY_WAIT_TICKS) {
                ctx.actuators().mover().stop();
                walkIssued = false;
                return TaskStatus.RUNNING;
            }
            ctx.actuators().mover().stop();
            walkIssued = false;
            phase = Phase.VERIFY;
            return TaskStatus.RUNNING;
        }
        decayWait = 0;
        if (!walkIssued) {
            ctx.actuators().mover().moveTo(nearest.x(), nearest.y(), nearest.z());
            walkIssued = true;
            walkTicks = 0;
            gatherWalks++;
            return TaskStatus.RUNNING;
        }
        if (ctx.actuators().mover().state() == MoveState.MOVING
                && ++walkTicks < WALK_TIMEOUT_TICKS) {
            return TaskStatus.RUNNING;
        }
        walkIssued = false;
        return TaskStatus.RUNNING;
    }

    /** Whether any of this fell's wood still sits somewhere a walk cannot yet reach. */
    private boolean perchedWoodRemains(BrainContext ctx) {
        for (var drop : ctx.percepts().drops()) {
            if (Stock.LOGS.matches(drop.itemId())
                    && TreeShape.horizontalDistSq(drop.pos(), anchor)
                            <= (long) GATHER_RADIUS * GATHER_RADIUS) {
                return true;
            }
        }
        return false;
    }

    /**
     * The exit guarantee: SUCCESS and forget only when nothing the card promised still stands
     * and nothing was refused or left over. Anything less keeps the memory — a crownless remnant
     * will never re-individuate for perception, so a partial tree must stay findable.
     */
    private TaskStatus verify(BrainContext ctx) {
        if (pollBreak(ctx)) {
            return TaskStatus.RUNNING;
        }
        BlockProbe probe = ctx.percepts().blocks();
        // Nothing of HERS stays either: the pillar column and every boosted stand are swept
        // before the census. Bounded — an unreachable straggler is journaled loudly rather than
        // blocking the verdict forever.
        Pos hers = null;
        // As high as she ever WORKED, not as high as the card prepaid: the pillar stops at the
        // arm's height and every layer above it was climbed on her own logs during the work, so
        // measuring the sweep by the mast alone leaves that extension standing.
        int worked = plan.entry().y() + plan.mast().size();
        for (ChopPlan.Layer layer : plan.layers()) {
            worked = Math.max(worked, layer.y());
        }
        for (int y = plan.entry().y(); y <= worked + 6 && hers == null; y++) {
            Pos c = new Pos(siteX, y, siteZ);
            if (probe.at(c.x(), c.y(), c.z()) == BlockKind.LOG) {
                hers = c;
            }
        }
        for (ChopPlan.Layer layer : plan.layers()) {
            for (ChopPlan.Move move : layer.moves()) {
                if (hers != null) {
                    break;
                }
                if (move.boost() && probe.at(move.stand().x(), move.stand().y(),
                        move.stand().z()) == BlockKind.LOG) {
                    hers = move.stand();
                }
            }
        }
        if (hers != null) {
            if (tryArm(ctx, hers)) {
                return TaskStatus.RUNNING;
            }
            if (!walkIssued) {
                ctx.actuators().mover().moveTo(hers.x(), hers.y(), hers.z());
                walkIssued = true;
                walkTicks = 0;
                return TaskStatus.RUNNING;
            }
            if (ctx.actuators().mover().state() == MoveState.MOVING
                    && ++walkTicks < WALK_TIMEOUT_TICKS) {
                return TaskStatus.RUNNING;
            }
            walkIssued = false;
            if (tryArm(ctx, hers)) {
                return TaskStatus.RUNNING;
            }
            ctx.journal().record(Category.BRAIN, "chop",
                    "left one of her own logs at " + shortPos(hers) + " — unreachable");
        }
        List<Pos> standing = new ArrayList<>();
        for (Pos cell : allPromisedLogs()) {
            if (probe.at(cell.x(), cell.y(), cell.z()) == BlockKind.LOG) {
                standing.add(cell);
            }
        }
        // The world's census is the only judge: a "leftover" whose cell a later chew broke
        // anyway is a breadcrumb, not a debt — 0 standing with 1 listed once read as partial.
        if (standing.isEmpty() && plan.refusals().isEmpty()) {
            ctx.journal().record(Category.BRAIN, "chop", "felled clean at " + shortPos(anchor)
                    + " — the canopy is decay's problem now");
            ctx.knowledge().forget(Pois.TREE, anchor);
            ctx.claims().release(Pois.TREE, anchor);
            return TaskStatus.SUCCESS;
        }
        ending = standing.size() + " standing, " + plan.refusals().size() + " refused, "
                + leftovers.size() + " left over at " + shortPos(anchor);
        ctx.journal().record(Category.BRAIN, "chop", "partial — " + ending);
        ctx.claims().release(Pois.TREE, anchor);
        return TaskStatus.FAILED;
    }

    @Override
    public void cancel(BrainContext ctx) {
        ctx.actuators().breaker().abort();
        ctx.actuators().riser().abort();
        ctx.actuators().mover().stop();
        ctx.claims().release(Pois.TREE, anchor);
    }

    @Override
    public String failureDetail() {
        return ending != null ? ending : describe() + " failed";
    }

    @Override
    public String describe() {
        return "chop the tree at " + shortPos(anchor);
    }

    // ---- the small mechanics ----

    /** True while a break is in flight; a finished/failed break clears for the next order. */
    private boolean pollBreak(BrainContext ctx) {
        if (!breaking) {
            return false;
        }
        return switch (ctx.actuators().breaker().state()) {
            case BREAKING -> true;
            case FINISHED, IDLE, FAILED -> {
                breaking = false;
                yield false;
            }
        };
    }

    /**
     * The cell the arm should actually bite on the way to {@code cell}: the obstruction CHAIN,
     * walked until the arm agrees its mark is clear. One hop is not enough —
     * {@code obstruction(x)} marches toward x's centre while {@code begin(blocker)} checks the
     * line to the BLOCKER's centre, and those lines can clip different cells. Only this tree's
     * own cells are chosen; bounded hops.
     */
    private Pos chewMark(BrainContext ctx, Pos cell) {
        Pos mark = cell;
        for (int hop = 0; hop < 4; hop++) {
            Pos b = ctx.actuators().breaker().obstruction(mark);
            if (b == null || b.equals(mark) || !treeBlocks.contains(b)) {
                break;
            }
            mark = b;
        }
        return mark;
    }

    private TaskStatus beginBreak(BrainContext ctx, Pos cell, String what) {
        if (ctx.actuators().breaker().begin(cell)) {
            breaking = true;
            return TaskStatus.RUNNING;
        }
        // Refused (out of reach, unbreakable): inside a WORK move, the move gives way and the
        // dance goes on; anywhere else (the shaft, the pillar) the run is over outright.
        if (phase == Phase.WORK && moveIndex >= 0) {
            giveUpMove(ctx, "the arm refused " + what + " at " + shortPos(cell));
            return TaskStatus.RUNNING;
        }
        return fail(ctx, "the arm refused " + what + " at " + shortPos(cell));
    }

    /**
     * One real swing attempt from wherever she stands: chew-chain the mark, then let
     * {@code begin()} decide. True means the arm is working; false means this spot cannot serve
     * the cell.
     */
    private boolean tryArm(BrainContext ctx, Pos cell) {
        Pos mark = chewMark(ctx, cell);
        if (ctx.actuators().breaker().begin(mark)) {
            breaking = true;
            return true;
        }
        return false;
    }

    /** The give-up evidence: feet, the arm's path answer, and what the probe calls the mark. */
    private String armForensics(BrainContext ctx, Pos cell) {
        Pos mark = chewMark(ctx, cell);
        Pos feet = ctx.percepts().position();
        return "[feet " + shortPos(feet)
                + ", path " + (mark.equals(cell) ? "clear" : "blocked by " + shortPos(mark))
                + ", mark is " + ctx.percepts().blocks().at(mark.x(), mark.y(), mark.z()) + "]";
    }

    /** Abandon the current move cleanly: its target is a leftover, the dance goes on. */
    private void giveUpMove(BrainContext ctx, String why) {
        ChopPlan.Move move = plan.layers().get(layerIndex).moves().get(moveIndex);
        leftovers.add(move.target());
        ctx.journal().record(Category.BRAIN, "chop", "left " + shortPos(move.target())
                + " standing — " + why);
        digsAhead = null;
        walkIssued = false;
        boostUp = false;
        moveIndex++;
    }

    private void finishMove(BrainContext ctx) {
        if (boostUp) {
            // Step down off the boost block by mining it back — her own log, reclaimed.
            Pos feet = ctx.percepts().position();
            Pos below = new Pos(feet.x(), feet.y() - 1, feet.z());
            if (ctx.percepts().blocks().at(below.x(), below.y(), below.z()) != BlockKind.AIR) {
                beginBreak(ctx, below, "the boost block");
            }
            boostUp = false;
            return;
        }
        digsAhead = null;
        walkIssued = false;
        moveIndex++;
    }

    private boolean inReach(BrainContext ctx, Pos cell) {
        Pos feet = ctx.percepts().position();
        double dx = cell.x() - feet.x();
        double dy = cell.y() + 0.5 - (feet.y() + EYE);
        double dz = cell.z() - feet.z();
        return dx * dx + dy * dy + dz * dz <= REACH * REACH;
    }

    private boolean atStand(BrainContext ctx, ChopPlan.Move move) {
        // Horizontally EXACT: a diagonal cell of slop let swings happen from cells the card never
        // chose, past the arm — a mark 5.1 out with the path clear. Vertical half-step slop
        // stays: steps and jumps land unevenly.
        Pos feet = ctx.percepts().position();
        return feet.x() == move.stand().x()
                && feet.z() == move.stand().z()
                && Math.abs(feet.y() - move.stand().y()) <= 1;
    }

    /** The first carried log item — what the mast and the boosts are financed with. */
    private String carriedLog(BrainContext ctx) {
        Inventory inv = ctx.percepts().inventory();
        for (int slot = 0; slot < Inventory.SIZE; slot++) {
            var stack = inv.get(slot);
            if (!stack.isEmpty() && Stock.LOGS.matches(stack.id())) {
                return stack.id();
            }
        }
        return null;
    }

    private List<Pos> allPromisedLogs() {
        List<Pos> cells = new ArrayList<>(tree.base());
        cells.addAll(tree.column());
        cells.addAll(tree.branches());
        return cells;
    }

    private Optional<Pos> findSeed(BlockProbe probe) {
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int x = anchor.x() + dx;
                    int y = anchor.y() + dy;
                    int z = anchor.z() + dz;
                    if (probe.at(x, y, z) == BlockKind.LOG) {
                        return Optional.of(new Pos(x, y, z));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private TreeShape.Trunk nearestTrunk(List<TreeShape.Trunk> trees) {
        TreeShape.Trunk best = null;
        long bestDist = Long.MAX_VALUE;
        for (TreeShape.Trunk candidate : trees) {
            long dist = TreeShape.horizontalDistSq(candidate.base().get(0), anchor);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * A mid-dance remnant, reclaimed. A tree whose entry was already eaten FLOATS, and
     * individuation rightly refuses floating wood, so resumption authority comes from the memory
     * and the claim, never from re-individuation. The remnant's lowest log becomes the entry, the
     * vertical run above it the mast; the ascent already knows how to pillar through the air gap.
     */
    private TreeShape.Trunk remnantTrunk(java.util.Map<Pos, BlockKind> blocks) {
        List<Pos> logs = new ArrayList<>();
        for (var cell : blocks.entrySet()) {
            if (cell.getValue() == BlockKind.LOG
                    && TreeShape.horizontalDistSq(cell.getKey(), anchor) <= 64) {
                logs.add(cell.getKey());
            }
        }
        if (logs.isEmpty()) {
            return null;
        }
        logs.sort(java.util.Comparator.comparingInt(Pos::y)
                .thenComparingLong(cell -> TreeShape.horizontalDistSq(cell, anchor)));
        Pos entry = logs.get(0);
        List<Pos> column = new ArrayList<>();
        List<Pos> branches = new ArrayList<>();
        java.util.Set<Pos> logSet = new HashSet<>(logs);
        Pos up = new Pos(entry.x(), entry.y() + 1, entry.z());
        while (logSet.contains(up)) {
            column.add(up);
            up = new Pos(up.x(), up.y() + 1, up.z());
        }
        for (Pos log : logs) {
            if (!log.equals(entry) && !column.contains(log)) {
                branches.add(log);
            }
        }
        List<Pos> leaves = new ArrayList<>();
        for (var cell : blocks.entrySet()) {
            if (cell.getValue() == BlockKind.LEAVES
                    && TreeShape.horizontalDistSq(cell.getKey(), anchor) <= 64) {
                leaves.add(cell.getKey());
            }
        }
        return new TreeShape.Trunk(List.of(entry), column, branches, leaves);
    }

    /** The memory was wrong — no tree here. Heal the belief and end. */
    private TaskStatus ghost(BrainContext ctx) {
        ctx.journal().record(Category.BRAIN, "chop", "no tree at " + shortPos(anchor)
                + " — forgetting it");
        ctx.knowledge().forget(Pois.TREE, anchor);
        ctx.claims().release(Pois.TREE, anchor);
        ending = "the remembered tree is gone";
        return TaskStatus.FAILED;
    }

    private TaskStatus fail(BrainContext ctx, String why) {
        // Every ending reaches the ring: a failure nobody journals is a stall nobody can
        // diagnose. And a tree that beat her goes off the menu for a while — without the
        // avoidance the producer re-offers the same nearest unreachable tree forever, and fifty
        // Persons hammering doomed approaches was most of a real forest's lost TPS.
        ending = why;
        ctx.journal().record(Category.BRAIN, "chop", "FAILED — " + why);
        ctx.knowledge().avoid(Pois.TREE, anchor, ctx.percepts().time() + AVOID_TICKS);
        ctx.claims().release(Pois.TREE, anchor);
        return TaskStatus.FAILED;
    }

    private static String shortPos(Pos p) {
        return "(" + p.x() + ", " + p.y() + ", " + p.z() + ")";
    }
}
