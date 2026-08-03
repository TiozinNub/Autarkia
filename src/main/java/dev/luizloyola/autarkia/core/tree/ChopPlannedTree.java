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

    private enum Phase { APPROACH, SURVEY, ENTER, ASCEND, WORK, VERIFY }

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
    /** One exact re-walk to the stand before a refusal becomes a give-up — stand slop is real. */
    private boolean standRetried;
    private boolean retryWalking;
    /** Everything the card promised that this run could not serve — the reckoning of the exit. */
    private final List<Pos> leftovers = new ArrayList<>();
    private String ending;
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
            return fail("the tree at " + shortPos(anchor) + " is claimed by someone else");
        }
        // The stuck watchdog: feet in one cell with no arm or rise working, for longer than
        // any legitimate wait — end the run outright; a re-order replans from the remnant.
        Pos here = ctx.percepts().position();
        long now = ctx.percepts().time();
        boolean busy = breaking || riseIssued
                || ctx.actuators().breaker().state() == BreakState.BREAKING
                || ctx.actuators().riser().state() == RiseState.RISING;
        if (!here.equals(lastSpot) || busy || phase == Phase.SURVEY) {
            lastSpot = here;
            restingSince = now;
        } else if (restingSince > 0 && now - restingSince > STUCK_TICKS) {
            return fail("stuck at " + shortPos(here) + " during " + phase
                    + " — nothing moved for " + STUCK_TICKS + " ticks");
        }
        return switch (phase) {
            case APPROACH -> approach(ctx);
            case SURVEY -> survey(ctx);
            case ENTER -> enter(ctx);
            case ASCEND -> ascend(ctx);
            case WORK -> work(ctx);
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
        return fail("could not reach the tree at " + shortPos(anchor));
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
        if (tree == null) {
            return ghost(ctx);
        }
        treeBlocks = new HashSet<>(tree.leaves());
        treeBlocks.addAll(tree.base());
        treeBlocks.addAll(tree.column());
        treeBlocks.addAll(tree.branches());
        plan = ChopPlan.of(tree);
        mastAhead = new ArrayDeque<>(plan.mast());
        ctx.journal().record(Category.BRAIN, "chop",
                "the card says " + (plan.chopCount() + plan.mast().size()) + " chops, "
                        + plan.digCount() + " digs"
                        + (plan.refusals().isEmpty() ? ""
                                : ", " + plan.refusals().size() + " refused"));
        phase = Phase.ENTER;
        return TaskStatus.RUNNING;
    }

    /**
     * Break into the shaft: the entry cell and the one above it, then step inside. A mast too
     * short to stand in (a bush) skips the step-in — everything is worked from outside.
     */
    private TaskStatus enter(BrainContext ctx) {
        if (pollBreak(ctx)) {
            return TaskStatus.RUNNING;
        }
        BlockProbe probe = ctx.percepts().blocks();
        Pos pairCell = null;
        while (!mastAhead.isEmpty() && mastAhead.peek().y() <= plan.entry().y() + 1) {
            Pos cell = mastAhead.peek();
            if (probe.at(cell.x(), cell.y(), cell.z()) == BlockKind.LOG) {
                pairCell = cell;
                break;
            }
            mastAhead.poll();
        }
        if (pairCell != null) {
            // Stand FACE-ADJACENT before swinging: a face-adjacent break cannot be corner-blocked,
            // and a sapling oak was refused from 0.34 off-centre.
            if (besideEntry(ctx)) {
                walkIssued = false;
                return beginBreak(ctx, pairCell, "the way in");
            }
            Pos stand = nearestDoorstep(ctx);
            // The doorstep must be STANDABLE before a walk can deliver her — a sapling oak's own
            // canopy fills the cells beside its trunk. Chew that column first.
            for (int dy = 0; dy <= 1; dy++) {
                Pos c = new Pos(stand.x(), stand.y() + dy, stand.z());
                BlockKind k = probe.at(c.x(), c.y(), c.z());
                if ((k == BlockKind.LEAVES || k == BlockKind.LOG) && treeBlocks.contains(c)
                        && inReach(ctx, c)) {
                    Pos blocker = ctx.actuators().breaker().obstruction(c);
                    Pos mark = blocker != null && treeBlocks.contains(blocker) ? blocker : c;
                    if (ctx.actuators().breaker().begin(mark)) {
                        breaking = true;
                        return TaskStatus.RUNNING;
                    }
                    return fail("the arm refused the doorstep at " + shortPos(mark));
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
                return beginBreak(ctx, pairCell, "the way in");
            }
            // Hemmed short of the doorstep: chew the tree's own cell in the way and retry.
            Pos blocker = ctx.actuators().breaker().obstruction(stand);
            if (blocker != null && treeBlocks.contains(blocker)
                    && ctx.actuators().breaker().begin(blocker)) {
                breaking = true;
                return TaskStatus.RUNNING;
            }
            return fail("cannot stand beside the doorway at " + shortPos(plan.entry()));
        }
        if (plan.mast().size() < 3) {
            phase = Phase.WORK;
            return TaskStatus.RUNNING;
        }
        Pos here = ctx.percepts().position();
        if (here.x() == plan.entry().x() && here.z() == plan.entry().z()) {
            ctx.actuators().mover().stop();
            walkIssued = false;
            pickupWait = 0;
            phase = Phase.ASCEND;
            return TaskStatus.RUNNING;
        }
        if (!walkIssued) {
            ctx.actuators().mover().moveTo(plan.entry().x(), plan.entry().y(), plan.entry().z());
            walkIssued = true;
            walkTicks = 0;
            return TaskStatus.RUNNING;
        }
        if (ctx.actuators().mover().state() == MoveState.MOVING && ++walkTicks < WALK_TIMEOUT_TICKS) {
            return TaskStatus.RUNNING;
        }
        // Hemmed beside the doorway (a low canopy walls the one-block step): chew the tree's
        // own cell between her and the entry, then try the step again — each bite is finite.
        Pos doorway = ctx.actuators().breaker().obstruction(plan.entry());
        if (doorway != null && treeBlocks.contains(doorway)
                && ctx.actuators().breaker().begin(doorway)) {
            breaking = true;
            return TaskStatus.RUNNING;
        }
        return fail("could not step into the shaft at " + shortPos(plan.entry()));
    }

    /**
     * Ride the mast: break the next shaft cell above, then rise one on a log of her own —
     * repeat until the column's top is broken. The rise refusing with an empty pack waits a
     * moment for the drops underfoot to hop in; refusing for any other reason ends the run
     * (the body has already retried the cell itself — see the riser's contract).
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
                case FAILED -> {
                    riseIssued = false; // one more ask below; the body caps its own retries
                }
            }
        }
        BlockProbe probe = ctx.percepts().blocks();
        while (!mastAhead.isEmpty()) {
            Pos cell = mastAhead.peek();
            if (probe.at(cell.x(), cell.y(), cell.z()) == BlockKind.LOG) {
                Pos feet = ctx.percepts().position();
                if (cell.y() - feet.y() <= 2) {
                    return beginBreak(ctx, cell, "the shaft");
                }
                break; // above the arm: rise first
            }
            mastAhead.poll();
        }
        if (mastAhead.isEmpty()) {
            phase = Phase.WORK;
            layerIndex = 0;
            moveIndex = -1;
            return TaskStatus.RUNNING;
        }
        String log = carriedLog(ctx);
        if (log == null) {
            if (++pickupWait <= PICKUP_WAIT_TICKS) {
                return TaskStatus.RUNNING; // the broken logs are still hopping into the pack
            }
            return fail("no log to rise on below " + shortPos(mastAhead.peek()));
        }
        pickupWait = 0;
        if (ctx.actuators().riser().up(log)) {
            riseIssued = true;
            return TaskStatus.RUNNING;
        }
        return fail("the rise refused below " + shortPos(mastAhead.peek()));
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
            boostUp = rise == RiseState.RISEN;
            if (!boostUp) {
                giveUpMove(ctx, "the boost refused");
                return TaskStatus.RUNNING;
            }
        }
        if (layerIndex >= plan.layers().size()) {
            return descendToGround(ctx);
        }
        ChopPlan.Layer layer = plan.layers().get(layerIndex);
        if (moveIndex < 0) {
            // Between layers: back to center, then mine the pillar underfoot down to this layer.
            Pos feet = ctx.percepts().position();
            boolean onAxis = feet.x() == plan.entry().x() && feet.z() == plan.entry().z();
            if (feet.y() > layer.y()) {
                if (onAxis) {
                    walkIssued = false;
                    Pos below = new Pos(feet.x(), feet.y() - 1, feet.z());
                    if (ctx.percepts().blocks().at(below.x(), below.y(), below.z())
                            != BlockKind.AIR) {
                        return beginBreak(ctx, below, "the pillar underfoot");
                    }
                    return TaskStatus.RUNNING; // mid-fall between pillar cells
                }
                if (!walkIssued) {
                    ctx.actuators().mover().moveTo(
                            plan.entry().x(), feet.y(), plan.entry().z());
                    walkIssued = true;
                    walkTicks = 0;
                    return TaskStatus.RUNNING;
                }
                if (ctx.actuators().mover().state() == MoveState.MOVING
                        && ++walkTicks < WALK_TIMEOUT_TICKS) {
                    return TaskStatus.RUNNING;
                }
                walkIssued = false;
                if (!(ctx.percepts().position().x() == plan.entry().x()
                        && ctx.percepts().position().z() == plan.entry().z())) {
                    return fail("could not return to the mast at layer " + layer.y());
                }
                return TaskStatus.RUNNING;
            }
            moveIndex = 0;
            return TaskStatus.RUNNING;
        }
        if (moveIndex >= layer.moves().size()) {
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
            finishMove(ctx);
            return TaskStatus.RUNNING;
        }
        // STAND first, then dig, then swing: every line was computed from the card's stand, and
        // swinging from elsewhere cost the fancy oak two moves. En route the stand's column is
        // chewed standable and in-reach tunnel digs are eaten — they ARE the way there.
        if (!atStand(ctx, move)) {
            for (int dy = 0; dy <= 1; dy++) {
                Pos c = new Pos(move.stand().x(), move.stand().y() + dy, move.stand().z());
                BlockKind k = probe.at(c.x(), c.y(), c.z());
                if ((k == BlockKind.LEAVES || k == BlockKind.LOG) && treeBlocks.contains(c)
                        && inReach(ctx, c)) {
                    beginWorkBreak(ctx, c, "the stand");
                    return TaskStatus.RUNNING;
                }
            }
            while (!digsAhead.isEmpty()) {
                Pos dig = digsAhead.peek();
                if (probe.at(dig.x(), dig.y(), dig.z()) == BlockKind.AIR) {
                    digsAhead.poll();
                    continue;
                }
                if (inReach(ctx, dig)) {
                    beginWorkBreak(ctx, dig, "the way through");
                    return TaskStatus.RUNNING;
                }
                break;
            }
            if (!walkIssued) {
                ctx.actuators().mover().moveTo(
                        move.stand().x(), move.stand().y(), move.stand().z());
                walkIssued = true;
                walkTicks = 0;
                return TaskStatus.RUNNING;
            }
            if (ctx.actuators().mover().state() == MoveState.MOVING
                    && ++walkTicks < WALK_TIMEOUT_TICKS) {
                return TaskStatus.RUNNING;
            }
            walkIssued = false;
            if (!atStand(ctx, move) && !inReach(ctx, target)) {
                giveUpMove(ctx, "no way to the stand at " + shortPos(move.stand()));
            }
            return TaskStatus.RUNNING;
        }
        walkIssued = false;
        if (retryWalking) {
            if (ctx.actuators().mover().state() == MoveState.MOVING
                    && ++walkTicks < WALK_TIMEOUT_TICKS) {
                return TaskStatus.RUNNING;
            }
            retryWalking = false; // landed (or gave up landing) — one more try at the arm work
        }
        // At the stand. Digs still standing and in reach are the swing's line; digs beyond
        // reach were the tunnel here, and standing here proves that access — drop them.
        while (!digsAhead.isEmpty()) {
            Pos dig = digsAhead.peek();
            if (probe.at(dig.x(), dig.y(), dig.z()) == BlockKind.AIR || !inReach(ctx, dig)) {
                digsAhead.poll();
                continue;
            }
            beginWorkBreak(ctx, dig, "the way through");
            return TaskStatus.RUNNING;
        }
        if (move.boost() && !boostUp) {
            String log = carriedLog(ctx);
            if (log == null || !ctx.actuators().riser().up(log)) {
                giveUpMove(ctx, "no boost from " + shortPos(move.stand()));
                return TaskStatus.RUNNING;
            }
            riseIssued = true;
            return TaskStatus.RUNNING;
        }
        // The swing: no second-guessing the arm's reach — begin() decides, the chew inside
        // handles the path, and its give-up is the only "cannot serve" left.
        beginWorkBreak(ctx, target, "the mark");
        return TaskStatus.RUNNING;
    }

    /** After the last layer: mine whatever pillar still stands underfoot, down to the ground. */
    private TaskStatus descendToGround(BrainContext ctx) {
        Pos feet = ctx.percepts().position();
        if (feet.x() == plan.entry().x() && feet.z() == plan.entry().z()
                && feet.y() > plan.entry().y()) {
            Pos below = new Pos(feet.x(), feet.y() - 1, feet.z());
            if (ctx.percepts().blocks().at(below.x(), below.y(), below.z()) != BlockKind.AIR) {
                return beginBreak(ctx, below, "the last of the pillar");
            }
        }
        phase = Phase.VERIFY;
        return TaskStatus.RUNNING;
    }

    /**
     * The exit guarantee: SUCCESS and forget only when nothing the card promised still stands
     * and nothing was refused or left over. Anything less keeps the memory — a crownless remnant
     * will never re-individuate for perception, so a partial tree must stay findable.
     */
    private TaskStatus verify(BrainContext ctx) {
        BlockProbe probe = ctx.percepts().blocks();
        List<Pos> standing = new ArrayList<>();
        for (Pos cell : allPromisedLogs()) {
            if (probe.at(cell.x(), cell.y(), cell.z()) == BlockKind.LOG) {
                standing.add(cell);
            }
        }
        if (standing.isEmpty() && plan.refusals().isEmpty() && leftovers.isEmpty()) {
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

    private boolean besideEntry(BrainContext ctx) {
        Pos feet = ctx.percepts().position();
        return Math.abs(feet.x() - plan.entry().x()) + Math.abs(feet.z() - plan.entry().z()) == 1
                && Math.abs(feet.y() - plan.entry().y()) <= 1;
    }

    private Pos nearestDoorstep(BrainContext ctx) {
        Pos feet = ctx.percepts().position();
        Pos best = null;
        long bestDist = Long.MAX_VALUE;
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] side : sides) {
            Pos cell = new Pos(plan.entry().x() + side[0], plan.entry().y(),
                    plan.entry().z() + side[1]);
            long dx = cell.x() - feet.x();
            long dz = cell.z() - feet.z();
            long dist = dx * dx + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                best = cell;
            }
        }
        return best;
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
        return fail("the arm refused " + what + " at " + shortPos(cell));
    }

    /**
     * Begin a WORK-phase break, chewing the eye line when the arm path refuses: the card's digs
     * were planned from its stands, and real feet leave one more leaf on the line. When even the
     * chew refuses, the move gives way.
     */
    private void beginWorkBreak(BrainContext ctx, Pos cell, String what) {
        // Chew-first, same as the shaft, and the blocker is the ARM'S own ANSWER: a self-sampled
        // line measures from somewhere the eyes are not and disagrees with the refusal it cures.
        Pos blocker = ctx.actuators().breaker().obstruction(cell);
        Pos mark = blocker != null && treeBlocks.contains(blocker) ? blocker : cell;
        if (ctx.actuators().breaker().begin(mark)) {
            breaking = true;
            return;
        }
        if (!standRetried) {
            // Once per move: walk the stand again, exactly, before surrendering — the first
            // walk's arrival tolerance is a whole cell of slop the arm does not have.
            standRetried = true;
            retryWalking = true;
            ChopPlan.Move move = plan.layers().get(layerIndex).moves().get(moveIndex);
            ctx.actuators().mover().moveTo(move.stand().x(), move.stand().y(), move.stand().z());
            walkTicks = 0;
            return;
        }
        // Forensic give-up: enough evidence that the next grind run convicts a cause, not a
        // symptom.
        Pos feet = ctx.percepts().position();
        giveUpMove(ctx, "the arm refused " + what + " at " + shortPos(mark)
                + " [feet " + shortPos(feet)
                + ", path " + (blocker == null ? "clear" : "blocked by " + shortPos(blocker))
                + ", mark is " + ctx.percepts().blocks().at(mark.x(), mark.y(), mark.z()) + "]");
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
        standRetried = false;
        retryWalking = false;
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
        standRetried = false;
        retryWalking = false;
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
        Pos feet = ctx.percepts().position();
        return Math.abs(feet.x() - move.stand().x()) <= 1
                && Math.abs(feet.z() - move.stand().z()) <= 1
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

    /** The memory was wrong — no tree here. Heal the belief and end. */
    private TaskStatus ghost(BrainContext ctx) {
        ctx.journal().record(Category.BRAIN, "chop", "no tree at " + shortPos(anchor)
                + " — forgetting it");
        ctx.knowledge().forget(Pois.TREE, anchor);
        ctx.claims().release(Pois.TREE, anchor);
        ending = "the remembered tree is gone";
        return TaskStatus.FAILED;
    }

    private TaskStatus fail(String why) {
        ending = why;
        return TaskStatus.FAILED;
    }

    private static String shortPos(Pos p) {
        return "(" + p.x() + ", " + p.y() + ", " + p.z() + ")";
    }
}
