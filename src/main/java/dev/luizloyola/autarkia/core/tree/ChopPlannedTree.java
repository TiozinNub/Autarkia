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
    /** Everything the card promised that this run could not serve — the reckoning of the exit. */
    private final List<Pos> leftovers = new ArrayList<>();
    private String ending;

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
        while (!mastAhead.isEmpty() && mastAhead.peek().y() <= plan.entry().y() + 1) {
            Pos cell = mastAhead.peek();
            if (probe.at(cell.x(), cell.y(), cell.z()) == BlockKind.LOG) {
                return breakWithinReach(ctx, cell, "the way in");
            }
            mastAhead.poll();
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
        // Digs in card order, each from wherever she stands when it comes up in reach.
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
            break; // walk closer before the next dig
        }
        Pos target = move.target();
        if (probe.at(target.x(), target.y(), target.z()) == BlockKind.AIR) {
            finishMove(ctx);
            return TaskStatus.RUNNING;
        }
        if (move.boost() && !boostUp && atStand(ctx, move)) {
            String log = carriedLog(ctx);
            if (log == null || !ctx.actuators().riser().up(log)) {
                giveUpMove(ctx, "no boost from " + shortPos(move.stand()));
                return TaskStatus.RUNNING;
            }
            riseIssued = true;
            return TaskStatus.RUNNING;
        }
        if (inReach(ctx, target) && (!move.boost() || boostUp)) {
            beginWorkBreak(ctx, target, "the mark");
            return TaskStatus.RUNNING;
        }
        if (!walkIssued) {
            if (atStand(ctx, move)) {
                // At the stand and still short an arm: the world drifted from the card.
                giveUpMove(ctx, "cannot serve " + shortPos(target) + " from " + shortPos(move.stand()));
                return TaskStatus.RUNNING;
            }
            ctx.actuators().mover().moveTo(move.stand().x(), move.stand().y(), move.stand().z());
            walkIssued = true;
            walkTicks = 0;
            return TaskStatus.RUNNING;
        }
        if (ctx.actuators().mover().state() == MoveState.MOVING && ++walkTicks < WALK_TIMEOUT_TICKS) {
            return TaskStatus.RUNNING;
        }
        walkIssued = false;
        if (!atStand(ctx, move) && !inReach(ctx, target)) {
            giveUpMove(ctx, "no way to the stand at " + shortPos(move.stand()));
        }
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

    private TaskStatus breakWithinReach(BrainContext ctx, Pos cell, String what) {
        if (inReach(ctx, cell)) {
            walkIssued = false;
            if (ctx.actuators().breaker().begin(cell)) {
                breaking = true;
                return TaskStatus.RUNNING;
            }
            // In reach and refused: almost always the ARM PATH — the breaker will not swing
            // through the canopy. The first of this tree's cells on the eye line is, by being
            // first, the one cell whose own arm path is clear: chew it and the line shortens.
            Pos blocker = firstBlockerToward(ctx, cell);
            if (blocker != null && ctx.actuators().breaker().begin(blocker)) {
                breaking = true;
                return TaskStatus.RUNNING;
            }
            return fail("the arm refused " + what + " at " + shortPos(cell));
        }
        Pos blocker = firstBlockerToward(ctx, cell);
        if (blocker != null && inReach(ctx, blocker)
                && ctx.actuators().breaker().begin(blocker)) {
            walkIssued = false;
            breaking = true;
            return TaskStatus.RUNNING;
        }
        if (!walkIssued) {
            ctx.actuators().mover().moveTo(cell.x(), cell.y(), cell.z());
            walkIssued = true;
            walkTicks = 0;
            return TaskStatus.RUNNING;
        }
        if (ctx.actuators().mover().state() == MoveState.MOVING
                && ++walkTicks < WALK_TIMEOUT_TICKS) {
            return TaskStatus.RUNNING;
        }
        walkIssued = false;
        if (inReach(ctx, cell) || (blocker != null && inReach(ctx, blocker))) {
            return TaskStatus.RUNNING; // try the arm again from where the walk ended
        }
        return fail("cannot get near " + what + " at " + shortPos(cell));
    }

    /**
     * The first of this tree's own cells still standing on the eye line toward {@code cell} —
     * what "break all the blocks in the way" eats next. Null when the line is clear (the
     * distance itself is the problem) or the blocker is somebody else's.
     */
    private Pos firstBlockerToward(BrainContext ctx, Pos cell) {
        Pos feet = ctx.percepts().position();
        double ex = feet.x() + 0.5;
        double ey = feet.y() + EYE;
        double ez = feet.z() + 0.5;
        double dx = cell.x() + 0.5 - ex;
        double dy = cell.y() + 0.5 - ey;
        double dz = cell.z() + 0.5 - ez;
        int steps = (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / 0.25);
        BlockProbe probe = ctx.percepts().blocks();
        Pos last = null;
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            Pos on = new Pos((int) Math.floor(ex + dx * t), (int) Math.floor(ey + dy * t),
                    (int) Math.floor(ez + dz * t));
            if (on.equals(last) || on.equals(cell)) {
                last = on;
                continue;
            }
            last = on;
            BlockKind kind = probe.at(on.x(), on.y(), on.z());
            if ((kind == BlockKind.LEAVES || kind == BlockKind.LOG) && treeBlocks.contains(on)) {
                return on;
            }
        }
        return null;
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
        if (ctx.actuators().breaker().begin(cell)) {
            breaking = true;
            return;
        }
        Pos blocker = firstBlockerToward(ctx, cell);
        if (blocker != null && ctx.actuators().breaker().begin(blocker)) {
            breaking = true;
            return;
        }
        giveUpMove(ctx, "the arm refused " + what + " at " + shortPos(cell));
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
