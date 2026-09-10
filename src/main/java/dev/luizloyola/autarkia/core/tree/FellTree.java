package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.BlockBreaker;
import dev.luizloyola.anima.core.brain.act.BreakState;
import dev.luizloyola.anima.core.brain.act.LeanState;
import dev.luizloyola.anima.core.brain.act.Leaner;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.act.Mover;
import dev.luizloyola.anima.core.brain.act.RiseState;
import dev.luizloyola.anima.core.brain.act.Riser;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.PrimitiveTask;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.autarkia.core.board.Stock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The eighth chop (decisions: Luiz, 2026-09-07 to 2026-09-09). Read which sides of the stump a
 * body could walk up to ({@link Approach}), take the cheapest, walk to its feet cell clearing the
 * leaves that side lists — only those — beginning on any of them the moment the arm can reach it,
 * mid-walk included. Work out the {@link Climb} from the cell it stands in. Then carry it out: open
 * the trunk and get in, hopping up onto the step up or digging in level with it; go up to the
 * height the tallest log demands — a lone trunk by rising on one placed log at a time, breaking
 * only what is over the head; a 2×2 giant by spiralling, a slot of three cells opened one higher
 * in the next column round and hopped into, the logs left every fourth level being the stairs —
 * break everything above from there; come down, breaking underfoot, or back down the spiral
 * breaking each stair as it is left and then the rest of the giant from the entry stand, until the
 * feet are back at floor level — and on the way down, from the top and from every level, take
 * every branch the arm reaches; step out and take the logs that may be left one below floor level
 * from outside. Then SUCCESS, with the count, or FAILED with the one reason: a rise refused,
 * nothing carried to rise on, the arm refused a block for long, every side given up on, or wood
 * left standing that the plan knew it could not reach.
 *
 * <p><b>The world holds the progress, the save holds the stage.</b> The survey is re-read every
 * second, the walk is re-ordered when the legs give up, a block is only ever begun on because the
 * probe still says it is there, and every stage after the plan reads the column afresh — so a
 * shove costs at most a second of looking. What the world cannot give back is written down
 * (decision: Luiz, 2026-09-07): the stage, the side taken and the plan, because once the trunk is
 * opened the plan can no longer be read off the tree. A fresh task that finds the body already
 * standing in the trunk plans from there rather than walking out to walk back in, which is what a
 * preemption's re-derived plan meets. The side taken is kept because selection is commitment: a
 * body walking past a symmetric pair of sides would otherwise flip between them as it went.
 *
 * <p><b>A walk onto leaves is ordered anyway</b> (decision: Luiz): the legs walk a partial route to
 * the nearest cell they can reach and fail there, which is inside the arm's reach of the leaves,
 * and the walk is ordered again once they are gone.
 *
 * <p>The seventh choreography — a compiled dance card and the 1600-line executor that walked it —
 * was deleted whole on 2026-09-06 for this redesign, the way the six before it went on
 * 2026-08-02. <b>Detection was not touched</b>: {@link TreeShape} still answers whose wood is whose.
 * The four callers that hand a tree to the axe — {@link ChopForLogs}, {@link TreeClearing}, the
 * chop wand and {@code /autarkia brain chop} — keep their shape and their tests.
 */
public final class FellTree implements PrimitiveTask {

    /**
     * How often the ground around the stump is re-read: every second, so a block placed beside the
     * tree shows up in the next readout, not the next order.
     */
    public static final int RESURVEY_TICKS = 20;

    /**
     * How long after the legs give up before the same walk is ordered again — two seconds, long
     * enough for a leaf to have come down in between, short enough not to read as sulking.
     */
    public static final int WALK_RETRY_TICKS = 40;

    /** How many times one walk may fail before the chop gives the tree up. */
    public static final int WALK_GIVE_UP = 5;

    /**
     * How long the arm may be refused every block it has left, with the legs done, before the
     * journal says what is in the way — two seconds, one line per block.
     */
    public static final int SWING_REFUSED_TICKS = 40;

    /**
     * How long that may go on before the chop gives the tree up — five seconds, and the reason it
     * was refused is the journal's last line.
     */
    public static final int GIVE_UP_TICKS = 100;

    /**
     * Rises that landed the body no higher before the chop gives up: a step that does not raise
     * the climber is not a step, and nothing else bounds it (the seventh's 185-iteration loop).
     */
    public static final int NO_GAIN_LIMIT = 3;

    /**
     * How long a tree may have no way in before the chop gives it up — five seconds of the ground
     * being re-read every second. The board paces the retry; a body holding the claim and looking
     * is a body doing nothing (Ash, alone at the tree in water, until loneliness took the task).
     */
    public static final int NO_WAY_IN_TICKS = 100;

    /**
     * How long a body may hang with nothing under its feet before its position is believed
     * anyway — two seconds; a fall is over well before, and a body stood on something the probe
     * calls air (a lily pad) should not wait forever.
     */
    public static final int AIRBORNE_TICKS = 40;

    /**
     * How far out from the trunk the wood is scanned for the tree's branches at plan time — a
     * fancy oak's reach and a little more. Once, a few thousand reads.
     */
    public static final int BRANCH_SCAN = 7;

    /**
     * How many cells of air a trunk is read across at plan time. A fell given up on from beside
     * leaves up to the arm's reach of air over the stump — three cells on 2026-09-10, where the
     * next settler read a one-log tree, took the stump, and left the rest floating over a closed
     * project (the seventh's finding four, again). Wider than that is somebody else's wood.
     */
    public static final int COLUMN_GAP = 5;

    /**
     * How many leaves in a row the arm chews through to reach the block it was refused. A crown
     * hides its own trunk from the side, and the leaf the breaker blames is as often as not in
     * front of another; past this many the swing is not worth the path.
     */
    public static final int CHEW_HOPS = 3;
    /**
     * How long a lean the body refuses is asked for again before it is given up: the body lands
     * a tick or two after the probe says there is a block under its feet, and refuses mid-air.
     */
    private static final int LEAN_RETRY_TICKS = 20;

    /**
     * The stages, in order. Written into the save by NAME, so add at the end and never rename: a
     * saved body mid-stage reads its stage back by it. PLANNED is the moment of standing in the
     * trunk with the plan, and hands straight on.
     */
    public enum Stage {
        APPROACH, OPEN, ENTER, PLANNED, RISE, CLEAR, DESCEND, GROUND, SPIRAL, UNWIND, BOTTOM
    }

    /** What the arm takes: a side's leaves; whatever the trunk is opened through; the wood itself. */
    private static final Set<BlockKind> LEAVES_ONLY = Set.of(BlockKind.LEAVES);
    private static final Set<BlockKind> WOOD_OR_LEAVES = Set.of(BlockKind.LOG, BlockKind.LEAVES);
    private static final Set<BlockKind> WOOD = Set.of(BlockKind.LOG);
    /** Half the body's width: what its box reaches into the next cell over when it leans. */
    private static final double BODY_HALF_WIDTH = 0.3;

    private final Pos anchor;
    private Stage stage = Stage.APPROACH;
    private @Nullable Approach approach;
    private @Nullable Climb climb;
    /** The ring cell of the side being taken — held while it stays approachable. */
    private @Nullable Pos chosen;
    /**
     * Sides given up on for this tree: the walk or the swing would not come off there. Written
     * down so the next choice is a different one — the seventh's lesson, which re-derived the
     * same plan off the same knowledge and ran it again.
     */
    private final Set<Pos> refusedSides = new HashSet<>();
    /** Sides already stood at for the branches from outside, so each is asked once. */
    private final Set<Pos> sidesTried = new HashSet<>();
    /** Branches whose refusal has been journalled, so each is said once. */
    private final Set<Pos> branchesRefused = new HashSet<>();
    /** The lean under way, if any — from its clearing to the body being back in the middle. */
    private Climb.@Nullable Lean leaning;
    private boolean leanDone;
    /** The tick a lean was first refused by the body, while it keeps being; -1 otherwise. */
    private int leanAskedAt = -1;
    /** Every cell the branches were looked for from, for the word on what was never in reach. */
    private final Set<Pos> stood = new LinkedHashSet<>();
    /** Branches leant for already, so each is leant for once. */
    private final Set<Pos> leansTried = new HashSet<>();
    /** Leans the plan did not make: for a branch counted on and refused from a stand a lean reaches it from. */
    private final List<Climb.Lean> fallbackLeans = new ArrayList<>();
    /** The feet cell the last move order was for; a different one is a new order. */
    private @Nullable Pos walkingTo;
    /** The tick the last move order went out — its state is readable only from the next one. */
    private int walkOrderedAt = -1;
    /** Whether the legs reported the current walk done. */
    private boolean arrived;
    /** The tick a failed walk may be ordered again. */
    private int walkRetryAt;
    /** The tick the tree was first found to have no way in, while that lasts; -1 otherwise. */
    private int noWayInSince = -1;
    /** The tick the body was first read with nothing under its feet, while that lasts; else -1. */
    private int airborneSince = -1;
    /** How many times the current walk has failed. */
    private int walkFailures;
    /** The block under the arm, or null, and what it was when the swing began. */
    private @Nullable Pos breaking;
    private @Nullable BlockKind breakingKind;
    /** The block the arm has been refused since {@code refusedSince}, while nothing else was begun. */
    private @Nullable Pos refused;
    private int refusedSince;
    /** Whether a rise step is in flight, and the feet height it was ordered from. */
    private boolean rising;
    private int riseFrom;
    private int noGain;
    /** This tree's own log, read off the trunk once — the first choice of block to rise on. */
    private @Nullable String treeLog;
    /** Logs broken and rises landed, so the felled count leaves the body's own steps out. */
    private int logsBroken;
    private int risen;
    /** Why the chop is giving up, set by whichever part found out; read by the stage in hand. */
    private @Nullable String stuck;
    private @Nullable String failure;
    private int ticks;
    /** The last summary journalled — a re-read that says the same thing says nothing. */
    private String told = "";
    private String phase = "looking";

    public FellTree(Pos anchor) {
        this.anchor = anchor;
    }

    /**
     * A task put back from a save: the stage it was at, the side it took and the plan it made. A
     * stage past the approach with no plan to work cannot be resumed, and starts over from the
     * look — the body's position then decides how much of the way back it really is.
     */
    public static FellTree restored(Pos anchor, Stage stage, Optional<Pos> chosen,
                                    Optional<Climb> climb) {
        FellTree task = new FellTree(anchor);
        task.chosen = chosen.orElse(null);
        task.climb = climb.orElse(null);
        task.stage = task.climb == null ? Stage.APPROACH : stage;
        return task;
    }

    public Stage stage() {
        return stage;
    }

    public Pos anchor() {
        return anchor;
    }

    /** What the last look found, once there has been one — the debug view's whole input. */
    public Optional<Approach> approach() {
        return Optional.ofNullable(approach);
    }

    /** The ring cell of the side being taken, once one is. */
    public Optional<Pos> chosen() {
        return Optional.ofNullable(chosen);
    }

    /** The reach plan, once the body stood beside the stump and worked one out. */
    public Optional<Climb> climb() {
        return Optional.ofNullable(climb);
    }

    /** {@code "walking to W (2)"}, {@code "opening the trunk"}, {@code "rising to -57 (-59)"}, … */
    public String phase() {
        return phase;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        if (approach == null || ticks % RESURVEY_TICKS == 0) {
            look(ctx);
        }
        // A stage that finishes hands straight on to the next, so no tick is spent idle between.
        TaskStatus status = switch (stage) {
            case APPROACH -> approach(ctx);
            case OPEN -> open(ctx);
            case ENTER -> enter(ctx);
            case PLANNED -> planned(ctx);
            case RISE -> rise(ctx);
            case SPIRAL -> spiral(ctx);
            case CLEAR -> clear(ctx);
            case UNWIND -> unwind(ctx);
            case BOTTOM -> bottom(ctx);
            case DESCEND -> descend(ctx);
            case GROUND -> ground(ctx);
        };
        ticks++;
        return status;
    }

    // ── the way in ───────────────────────────────────────────────────────────────────────────

    /** The first stage: take a side, walk to it clearing its leaves, then plan the climb. */
    private TaskStatus approach(BrainContext ctx) {
        Approach.Side side = take(ctx);
        if (side == null) {
            phase = "no way in";
            ctx.actuators().mover().stop();
            if (noWayInSince < 0) {
                noWayInSince = ticks;
            } else if (ticks - noWayInSince >= NO_WAY_IN_TICKS) {
                return fail(ctx, "no way in — " + approach.summary() + (refusedSides.isEmpty() ? ""
                        : " — " + refusedSides.size() + " side(s) given up on"));
            }
            return TaskStatus.RUNNING; // and keep looking: the ground may change
        }
        noWayInSince = -1;
        // Already standing where the plan would put it — in the trunk's column, on the step up or
        // dug in level with it — after a preemption, or a reload that lost its plan. Plan from
        // here, the way it got in; walking out to walk back in is not a step. Anywhere else in the
        // column (on top of the tree, say) is a walk.
        Pos at = ctx.percepts().position();
        int rise = at.y() - side.feet().y();
        if (at.x() == anchor.x() && at.z() == anchor.z() && (rise == 0 || rise == 1)) {
            plan(ctx, side, rise == 1);
            return stage == Stage.OPEN ? open(ctx) : clear(ctx);
        }
        boolean cleared = breakNext(ctx, side.leaves(), LEAVES_ONLY);
        boolean there = walk(ctx, side.feet());
        if (stuck != null) {
            return refuse(ctx, side);
        }
        String label = approach.bearing(side.cell());
        if (!there) {
            phase = "walking to " + label + " (" + Approach.fmt(side.score()) + ")";
            return TaskStatus.RUNNING;
        }
        if (!cleared) {
            phase = "clearing leaves at " + label;
            return TaskStatus.RUNNING;
        }
        phase = "at the tree, " + label + " side";
        plan(ctx, side, side.jumpRoom());
        return TaskStatus.RUNNING;
    }

    /**
     * This side is no good: cross it off and let the next look choose another. The legs and the
     * arm start over; the tree is given up only once no side is left.
     */
    private TaskStatus refuse(BrainContext ctx, Approach.Side side) {
        refusedSides.add(side.cell());
        say(ctx, "the " + approach.bearing(side.cell()) + " side is no good — " + stuck
                + "; trying another");
        stuck = null;
        chosen = null;
        walkingTo = null;
        arrived = false;
        walkFailures = 0;
        refused = null;
        ctx.actuators().mover().stop();
        ctx.actuators().breaker().abort();
        breaking = null;
        return TaskStatus.RUNNING;
    }

    /**
     * Work out the climb from beside the stump, and which stage it starts. The trunk is every
     * column the survey put in the base — one, or a giant's four — and the entry column is the one
     * beside the side taken; which way round a giant is spiralled is drawn here, once.
     */
    private void plan(BrainContext ctx, Approach.Side side, boolean jumpRoom) {
        int bodyCells = body(ctx);
        BlockProbe blocks = ctx.percepts().blocks();
        List<Pos> columns = approach.base();
        Pos entry = anchor;
        List<Pos> logs = new ArrayList<>();
        for (Pos column : columns) {
            logs.addAll(Climb.column(column, blocks, COLUMN_GAP));
            int apart = Math.abs(column.x() - side.cell().x())
                    + Math.abs(column.z() - side.cell().z());
            if (apart == 1) {
                entry = column;
            }
        }
        climb = Climb.plan(Climb.Arm.of(ctx.percepts()), columns, entry, logs,
                branchesOf(ctx, columns, logs), blocks, side.feet(), jumpRoom,
                ctx.random().nextBoolean(), bodyCells);
        say(ctx, "plan — " + climb.describe()
                + (climb.giant() ? (climb.clockwise() ? ", clockwise" : ", anticlockwise") : ""));
        stage = climb.stepsIn() ? Stage.OPEN : Stage.CLEAR;
    }

    /** Take out what stands in the body's way, lowest first. */
    private TaskStatus open(BrainContext ctx) {
        phase = "opening the trunk";
        if (breakNext(ctx, climb.stepIn(), WOOD_OR_LEAVES)) {
            stage = Stage.ENTER;
            return enter(ctx);
        }
        return stuck == null ? TaskStatus.RUNNING : fail(ctx, stuck);
    }

    /** Into the trunk's own column: a hop up onto the step up, or a flat step level with it. */
    private TaskStatus enter(BrainContext ctx) {
        phase = "stepping in";
        if (walk(ctx, climb.stand())) {
            stage = Stage.PLANNED;
            phase = "in the trunk — " + climb.describe();
            say(ctx, phase);
        }
        return stuck == null ? TaskStatus.RUNNING : fail(ctx, stuck);
    }

    /** Standing in the trunk with the plan: the moment lasts a tick. */
    private TaskStatus planned(BrainContext ctx) {
        stage = !climb.stepsIn() ? Stage.CLEAR : climb.giant() ? Stage.SPIRAL : Stage.RISE;
        return switch (stage) {
            case SPIRAL -> spiral(ctx);
            case RISE -> rise(ctx);
            default -> clear(ctx);
        };
    }

    // ── up, across, and down ─────────────────────────────────────────────────────────────────

    /**
     * One placed block at a time until the feet are at the height the tree demands, breaking only
     * what is in the way over the head. A step that lands the body no higher is counted against
     * it, and the riser's own refusal is taken at its word: not from here.
     */
    private TaskStatus rise(BrainContext ctx) {
        Riser riser = ctx.actuators().riser();
        Pos at = ctx.percepts().position();
        if (riser.state() == RiseState.RISING) {
            phase = "rising";
            return TaskStatus.RUNNING;
        }
        if (rising) {
            rising = false;
            if (riser.state() == RiseState.RISEN && at.y() > riseFrom) {
                risen++;
                noGain = 0;
            } else if (riser.state() == RiseState.RISEN && ++noGain >= NO_GAIN_LIMIT) {
                return fail(ctx, "a rise that raised nobody, " + NO_GAIN_LIMIT + " times, at "
                        + where(at));
            }
            // FAILED: the body's own retries are bounded; asking again is how the answer comes.
        }
        if (at.y() >= climb.needFeetY()) {
            stage = Stage.CLEAR;
            return clear(ctx);
        }
        phase = "rising to " + climb.needFeetY() + " (" + at.y() + ")";
        Pos over = new Pos(climb.stand().x(), at.y() + 2, climb.stand().z());
        BlockKind kind = ctx.percepts().blocks().at(over.x(), over.y(), over.z());
        if (kind == BlockKind.LOG || kind == BlockKind.LEAVES) {
            if (!breakNext(ctx, List.of(over), WOOD_OR_LEAVES)) {
                return stuck == null ? TaskStatus.RUNNING : fail(ctx, stuck);
            }
        } else if (kind != BlockKind.AIR) {
            return fail(ctx, "cannot rise — " + kind.key() + " over the head at " + where(over));
        }
        String item = riseOn(ctx);
        if (item == null) {
            return fail(ctx, "nothing to rise on — no log in the pack");
        }
        if (!riser.up(item)) {
            return fail(ctx, "the rise refused from " + where(at));
        }
        rising = true;
        riseFrom = at.y();
        return TaskStatus.RUNNING;
    }

    /**
     * The spiral: three cells opened one higher in the next column round, then a hop into that
     * slot, until the feet are at the height the tallest log demands. The world says where the
     * body is and which column it stands in; nothing else has to be remembered.
     */
    private TaskStatus spiral(BrainContext ctx) {
        Pos at = settled(ctx);
        if (at == null) {
            return TaskStatus.RUNNING;
        }
        if (at.y() >= climb.needFeetY()) {
            stage = Stage.CLEAR;
            return clear(ctx);
        }
        Pos here = climb.column(at);
        if (here == null) {
            return fail(ctx, "out of the trunk at " + where(at));
        }
        phase = "spiralling up to " + climb.needFeetY() + " (" + at.y() + ")";
        // The cell over the head first: a body that dug in at its own level opened two, and the
        // hop into the next column wants a third, which it takes from inside.
        Pos over = new Pos(here.x(), at.y() + 2, here.z());
        BlockKind overKind = ctx.percepts().blocks().at(over.x(), over.y(), over.z());
        if (overKind == BlockKind.LOG || overKind == BlockKind.LEAVES) {
            if (!breakNext(ctx, List.of(over), WOOD_OR_LEAVES)) {
                return stuck == null ? TaskStatus.RUNNING : fail(ctx, stuck);
            }
        } else if (overKind != BlockKind.AIR) {
            return fail(ctx, "cannot spiral — " + overKind.key() + " over the head at " + where(over));
        }
        Pos next = climb.next(here, true);
        List<Pos> slot = new ArrayList<>(Climb.SLOT);
        for (int y = at.y() + 1; y <= at.y() + Climb.SLOT; y++) {
            slot.add(new Pos(next.x(), y, next.z()));
        }
        if (!breakNext(ctx, slot, WOOD_OR_LEAVES)) {
            return stuck == null ? TaskStatus.RUNNING : fail(ctx, stuck);
        }
        walk(ctx, new Pos(next.x(), at.y() + 1, next.z()));
        return stuck == null ? TaskStatus.RUNNING : fail(ctx, stuck);
    }

    /**
     * Every log left from the feet up — from inside, in every column, or from beside — lowest
     * first, then the branches in reach from up here. A giant's neighbouring columns can hold a
     * log level with the head; it comes out here.
     */
    private TaskStatus clear(BrainContext ctx) {
        Pos at = settled(ctx);
        if (at == null) {
            return TaskStatus.RUNNING;
        }
        int feet = at.y();
        List<Pos> cells = logsFrom(ctx, climb.stepsIn() ? feet : feet + 1);
        phase = "clearing above (" + cells.size() + " left)";
        if (!breakNext(ctx, cells, WOOD)) {
            return stuck == null ? TaskStatus.RUNNING : fail(ctx, stuck);
        }
        if (!branches(ctx, at)) {
            return TaskStatus.RUNNING;
        }
        stage = !climb.stepsIn() ? Stage.GROUND : climb.giant() ? Stage.UNWIND : Stage.BOTTOM;
        return switch (stage) {
            case UNWIND -> unwind(ctx);
            case BOTTOM -> bottom(ctx);
            default -> ground(ctx);
        };
    }

    /**
     * Back down the spiral: whatever is left level with the feet or higher comes out first — the
     * stair just left — then a step down into the previous column's slot, onto its stair, until
     * the body is back on the entry stand.
     */
    private TaskStatus unwind(BrainContext ctx) {
        Pos at = settled(ctx);
        if (at == null) {
            return TaskStatus.RUNNING;
        }
        Pos here = climb.column(at);
        if (here == null) {
            return fail(ctx, "out of the trunk at " + where(at));
        }
        List<Pos> left = logsFrom(ctx, at.y());
        phase = "coming down the spiral (" + at.y() + ")";
        if (!breakNext(ctx, left, WOOD)) {
            return stuck == null ? TaskStatus.RUNNING : fail(ctx, stuck);
        }
        if (!branches(ctx, at)) {
            return TaskStatus.RUNNING;
        }
        if (here.equals(climb.column(climb.stand())) && at.y() <= climb.stand().y()) {
            stage = Stage.BOTTOM;
            return bottom(ctx);
        }
        Pos prev = climb.next(here, false);
        walk(ctx, new Pos(prev.x(), at.y() - 1, prev.z()));
        return stuck == null ? TaskStatus.RUNNING : fail(ctx, stuck);
    }

    /**
     * From the entry stand, whatever the other columns still hold from floor level up, highest
     * first — a giant's unvisited bases, the last stairs — and then down as a lone trunk goes.
     */
    private TaskStatus bottom(BrainContext ctx) {
        Pos at = settled(ctx);
        if (at == null || !branches(ctx, at)) {
            return TaskStatus.RUNNING;
        }
        Pos entry = climb.stand();
        List<Pos> cells = new ArrayList<>();
        for (Pos log : logsFrom(ctx, climb.floor())) {
            if (log.x() != entry.x() || log.z() != entry.z() || log.y() >= entry.y()) {
                cells.add(0, log); // highest first
            }
        }
        phase = "the rest of the trunk (" + cells.size() + " left)";
        if (breakNext(ctx, cells, WOOD)) {
            stage = Stage.DESCEND;
            return descend(ctx);
        }
        return stuck == null ? TaskStatus.RUNNING : fail(ctx, stuck);
    }

    /**
     * Break underfoot until the feet are back at floor level: the placed blocks first, then the
     * step-up log after a hop-in. Anything under the feet that is not a log is as far down as the
     * axe goes — the dirt under a sunken stump — and the way out from there is a step down.
     */
    private TaskStatus descend(BrainContext ctx) {
        Pos at = settled(ctx);
        if (at == null || !branches(ctx, at)) {
            return TaskStatus.RUNNING; // still falling from the last one, or a branch in reach
        }
        Pos below = new Pos(climb.stand().x(), at.y() - 1, climb.stand().z());
        if (at.y() <= climb.floor()
                || ctx.percepts().blocks().at(below.x(), below.y(), below.z()) != BlockKind.LOG) {
            stage = Stage.GROUND;
            return ground(ctx);
        }
        phase = "coming down (" + at.y() + ")";
        breakNext(ctx, List.of(below), WOOD);
        return stuck == null ? TaskStatus.RUNNING : fail(ctx, stuck);
    }

    /**
     * From outside: the log at floor level, if the body never stood on it, and the one below it.
     * Never the one two below — a body in a hole that deep cannot get out, so that one stays.
     */
    private TaskStatus ground(BrainContext ctx) {
        if (!breakNext(ctx, List.of(), WOOD)) {
            return TaskStatus.RUNNING; // the last swing on the way down is still landing
        }
        BlockProbe blocks = ctx.percepts().blocks();
        List<Pos> cells = new ArrayList<>();
        for (int y = climb.floor(); y >= climb.floor() - 1; y--) {
            for (Pos column : climb.columns()) {
                if (blocks.at(column.x(), y, column.z()) == BlockKind.LOG) {
                    cells.add(new Pos(column.x(), y, column.z()));
                }
            }
        }
        if (!cells.isEmpty()) {
            Approach.Side side = side();
            if (side == null || side.feet() == null) {
                return fail(ctx, "no side to step out to");
            }
            phase = "stepping out";
            if (!walk(ctx, side.feet())) {
                return stuck == null ? TaskStatus.RUNNING : fail(ctx, stuck);
            }
            phase = "the stump, from outside";
            if (!breakNext(ctx, cells, WOOD)) {
                return stuck == null ? TaskStatus.RUNNING : fail(ctx, stuck);
            }
        }
        if (!branches(ctx, ctx.percepts().position())) {
            return TaskStatus.RUNNING; // the low ones, from the ground
        }
        // Whatever still stands that some other side of the stump reaches: an acacia leans its
        // limbs out past the side the body came in by (2026-09-10). Each side once.
        Pos stand = outsideStand(ctx);
        if (stand != null) {
            phase = "branches from outside";
            if (!walk(ctx, stand)) {
                return stuck == null ? TaskStatus.RUNNING : fail(ctx, stuck);
            }
            if (!branches(ctx, ctx.percepts().position())) {
                return TaskStatus.RUNNING;
            }
            sidesTried.add(stand);
            return TaskStatus.RUNNING; // and look again: another branch, another side
        }
        return finish(ctx);
    }

    /**
     * The feet cell of the side nearest a branch still standing that the arm would reach from
     * there, among the sides not yet stood at for this; null when no side reaches anything left.
     */
    private @Nullable Pos outsideStand(BrainContext ctx) {
        Climb.Arm arm = Climb.Arm.of(ctx.percepts());
        BlockProbe blocks = ctx.percepts().blocks();
        Pos best = null;
        double nearest = Double.MAX_VALUE;
        for (Approach.Side side : approach.sides()) {
            Pos feet = side.feet();
            if (!side.verdict().approachable() || feet == null || sidesTried.contains(feet)) {
                continue;
            }
            for (Pos branch : climb.branches()) {
                if (blocks.at(branch.x(), branch.y(), branch.z()) != BlockKind.LOG
                        || !Climb.reaches(arm, feet.x(), feet.y(), feet.z(), branch, 0)) {
                    continue;
                }
                double d = Math.pow(branch.x() - feet.x(), 2) + Math.pow(branch.z() - feet.z(), 2);
                if (d < nearest) {
                    nearest = d;
                    best = feet;
                }
            }
        }
        return best;
    }

    /** The column read one last time: SUCCESS with the count, or what is left and why. */
    private TaskStatus finish(BrainContext ctx) {
        List<Pos> left = logsFrom(ctx, climb.floor() - 1);
        int buried = 0;
        for (Pos column : climb.columns()) {
            for (int y = anchor.y(); y < climb.floor() - 1; y++) {
                if (ctx.percepts().blocks().at(column.x(), y, column.z()) == BlockKind.LOG) {
                    buried++;
                }
            }
        }
        if (!left.isEmpty()) {
            return fail(ctx, left.size() + " left standing, the first at " + where(left.get(0))
                    + (climb.complete() ? "" : " — the plan knew"));
        }
        int felled = logsBroken - risen;
        int unreached = 0;
        Climb.Arm arm = Climb.Arm.of(ctx.percepts());
        for (Pos branch : climb.branches()) {
            if (ctx.percepts().blocks().at(branch.x(), branch.y(), branch.z()) != BlockKind.LOG) {
                continue;
            }
            unreached++;
            // The word on it: where the body came nearest, and how far that still was — what a
            // missed log is diagnosed from, once per tree that leaves one.
            Pos nearest = null;
            double best = Double.MAX_VALUE;
            for (Pos cell : stood) {
                double dx = branch.x() - cell.x();
                double dz = branch.z() - cell.z();
                double dy = branch.y() + 0.5 - (cell.y() + arm.eyeHeight());
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d < best) {
                    best = d;
                    nearest = cell;
                }
            }
            if (nearest != null) {
                say(ctx, "a branch at " + where(branch) + " was never in reach — "
                        + Math.round(best * 100) / 100.0 + " from " + where(nearest)
                        + ", the nearest the body stood");
            }
        }
        say(ctx, "felled the tree at " + where(anchor) + " — " + felled + " logs"
                + (buried == 0 ? "" : ", " + buried + " left buried below the ground")
                + (unreached == 0 ? "" : ", " + unreached + " branches out of reach"));
        phase = "felled";
        release(ctx);
        return TaskStatus.SUCCESS;
    }

    // ── the senses ───────────────────────────────────────────────────────────────────────────

    private void look(BrainContext ctx) {
        approach = Approach.survey(anchor, ctx.percepts().position(), ctx.percepts().blocks(),
                body(ctx));
        String now = approach.summary();
        if (!now.equals(told)) {
            say(ctx, "approach — " + now);
            told = now;
        }
    }

    /**
     * The side to work: the one already taken while the fresh survey still calls it approachable,
     * else the cheapest. Null when there is none.
     */
    private Approach.@Nullable Side take(BrainContext ctx) {
        Approach.Side side = side();
        if (side == null) {
            side = best();
            if (side != null && !side.cell().equals(chosen)) {
                say(ctx, "going " + approach.bearing(side.cell()) + " — " + side.describe()
                        + " (" + Approach.fmt(side.score()) + ")");
            } else if (side == null && chosen != null) {
                say(ctx, "no way in");
            }
            chosen = side == null ? null : side.cell();
        }
        return side;
    }

    /** The cheapest side not given up on, ties to compass order; null when there is none. */
    private Approach.@Nullable Side best() {
        Approach.Side best = null;
        for (Approach.Side side : approach.sides()) {
            if (side.verdict().approachable() && !refusedSides.contains(side.cell())
                    && (best == null || side.score() < best.score())) {
                best = side;
            }
        }
        return best;
    }

    /** The side taken, as the last look saw it, while it is still a way in. */
    private Approach.@Nullable Side side() {
        if (chosen != null) {
            for (Approach.Side candidate : approach.sides()) {
                if (candidate.cell().equals(chosen) && candidate.verdict().approachable()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * What the body rises on: this tree's own log while it carries any — the felled wood comes
     * back down with the pillar — else whatever log it does carry, since a body that has just
     * felled birches and arrives at an oak has no oak to stand on until the opening logs are picked
     * up. Null with no log in the pack at all.
     */
    private @Nullable String riseOn(BrainContext ctx) {
        Inventory pack = ctx.percepts().inventory();
        if (treeLog == null) {
            BlockProbe blocks = ctx.percepts().blocks();
            for (Pos log : logsFrom(ctx, anchor.y())) {
                String id = blocks.idAt(log.x(), log.y(), log.z());
                if (!id.isEmpty()) {
                    treeLog = id;
                    break;
                }
            }
        }
        if (treeLog != null && pack.count(treeLog) > 0) {
            return treeLog;
        }
        for (Inventory.Entry entry : pack.occupied()) {
            if (Stock.LOGS.matches(entry.stack().id())) {
                return entry.stack().id();
            }
        }
        return null;
    }

    /**
     * The logs standing in the trunk's columns from {@code fromY} up to the plan's top, lowest
     * first — read without the gap rule {@link Climb#column} has, because from inside the gap
     * between the stump and the next log is whatever the body has cleared so far.
     */
    private List<Pos> logsFrom(BrainContext ctx, int fromY) {
        BlockProbe blocks = ctx.percepts().blocks();
        List<Pos> logs = new ArrayList<>();
        for (int y = fromY; y <= climb.top(); y++) {
            for (Pos column : climb.columns()) {
                if (blocks.at(column.x(), y, column.z()) == BlockKind.LOG) {
                    logs.add(new Pos(column.x(), y, column.z()));
                }
            }
        }
        return logs;
    }

    private static int body(BrainContext ctx) {
        return MoveCapabilities.of(ctx.profile()).clearCells();
    }

    /**
     * The tree's logs off its columns, as detection would assign them: the wood around the trunk
     * is scanned, connected and split ({@link TreeShape}), and the tree whose base holds one of
     * {@code columns} is this one — so a neighbour's trunk within the arm's reach is never taken
     * for a branch. Nothing when the split finds no tree there: a bare trunk with no crown.
     */
    private List<Pos> branchesOf(BrainContext ctx, List<Pos> columns, List<Pos> logs) {
        BlockProbe blocks = ctx.percepts().blocks();
        int top = anchor.y();
        for (Pos log : logs) {
            top = Math.max(top, log.y());
        }
        Map<Pos, BlockKind> wood = new LinkedHashMap<>();
        for (int x = anchor.x() - BRANCH_SCAN; x <= anchor.x() + BRANCH_SCAN; x++) {
            for (int z = anchor.z() - BRANCH_SCAN; z <= anchor.z() + BRANCH_SCAN; z++) {
                for (int y = anchor.y() - 1; y <= top + BRANCH_SCAN; y++) {
                    BlockKind kind = blocks.at(x, y, z);
                    if (kind == BlockKind.LOG || kind == BlockKind.LEAVES) {
                        wood.put(new Pos(x, y, z), kind);
                    }
                }
            }
        }
        for (Map<Pos, BlockKind> mass : TreeMasses.connect(wood)) {
            if (!mass.containsKey(anchor)) {
                continue;
            }
            for (TreeShape.Trunk tree : TreeShape.split(mass, blocks)) {
                for (Pos base : tree.base()) {
                    if (columns.contains(base)) {
                        return tree.branches();
                    }
                }
            }
        }
        return List.of();
    }

    /**
     * Every branch still standing that the arm reaches from {@code at}, the ones the breaker will
     * take: a refused one is left for a lower level, or for good — never waited on, since the
     * trunk does not depend on it. Returns whether nothing more can be done from here.
     */
    private boolean branches(BrainContext ctx, Pos at) {
        if (climb.branches().isEmpty()) {
            return true;
        }
        Climb.Arm arm = Climb.Arm.of(ctx.percepts());
        BlockProbe blocks = ctx.percepts().blocks();
        // The plan counts on the arm less its margin; a try costs nothing but a refused swing, so
        // everything within the arm's whole length is asked for. A mega jungle's limb tip 4.27
        // from the one stand near it was never asked (2026-09-10).
        List<Pos> cells = new ArrayList<>();
        for (Pos log : climb.branches()) {
            if (blocks.at(log.x(), log.y(), log.z()) == BlockKind.LOG
                    && Climb.reaches(arm, at.x(), at.y(), at.z(), log, 0)) {
                cells.add(log);
            }
        }
        if (stood.add(at)) {
            int leansHere = 0;
            for (Climb.Lean lean : climb.leans()) {
                if (lean.stand().equals(at)) {
                    leansHere++;
                }
            }
            say(ctx, "standing at " + where(at) + " — " + cells.size() + " branches in reach"
                    + (leansHere == 0 ? "" : ", " + leansHere + " to lean for"));
        }
        if (cells.isEmpty()) {
            // Settle a swing in flight before anything else.
            return breakNext(ctx, List.of(), WOOD, false) && leans(ctx, at);
        }
        phase = "branches in reach (" + cells.size() + ")";
        if (!breakNext(ctx, cells, WOOD, false)) {
            return false;
        }
        // Nothing could be begun on, though the plan had every one of them in reach: say what the
        // arm said, once per branch, since a skip is silent by design and this is what a missed
        // log is made of.
        BlockBreaker breaker = ctx.actuators().breaker();
        for (Pos cell : cells) {
            boolean standing = blocks.at(cell.x(), cell.y(), cell.z()) == BlockKind.LOG;
            if (standing && branchesRefused.add(cell)) {
                Pos block = breaker.obstruction(cell);
                say(ctx, "a branch at " + where(cell) + " refused from " + where(at) + " — "
                        + (block == null ? "the arm calls it out of reach"
                        : where(block) + " is in the way"));
                // Counted on from here and refused as out of reach — a body lands off the middle
                // of its cell after a hop, and a mega jungle's limb 4.18 from the cell's centre
                // was refused from it (2026-09-10) — is what a lean from right here is for.
                if (block == null && Climb.reachesLeaning(arm, at.x(), at.y(), at.z(), cell)) {
                    fallbackLeans.add(new Climb.Lean(cell, at));
                }
            }
        }
        return leans(ctx, at);
    }

    /**
     * The lean (decision: Luiz, 2026-09-10): a branch the plan gave to a crouch at this stand's
     * edge is taken from there — the way cleared first, the lean held while the arm swings, and
     * let go of before anything else moves, since out at the edge the feet read as the next
     * cell over. Returns whether nothing more is to be done from here.
     */
    private boolean leans(BrainContext ctx, Pos at) {
        Leaner leaner = ctx.actuators().leaner();
        BlockProbe blocks = ctx.percepts().blocks();
        if (leaning == null) {
            List<Climb.Lean> candidates = new ArrayList<>(climb.leans());
            candidates.addAll(fallbackLeans);
            for (Climb.Lean lean : candidates) {
                Pos branch = lean.branch();
                if (lean.stand().equals(at) && !leansTried.contains(branch)
                        && blocks.at(branch.x(), branch.y(), branch.z()) == BlockKind.LOG) {
                    leaning = lean;
                    leanDone = false;
                    leansTried.add(branch);
                    break;
                }
            }
            if (leaning == null) {
                return true;
            }
        }
        Pos branch = leaning.branch();
        switch (leaner.state()) {
            case IDLE -> {
                if (leanDone || blocks.at(branch.x(), branch.y(), branch.z()) != BlockKind.LOG) {
                    leaning = null; // back in the middle, or nothing to lean for: the next one
                    return false;
                }
                List<Pos> way = wayToLean(ctx, at, branch);
                if (way == null) {
                    say(ctx, "no room to lean from " + where(at) + " for the branch at "
                            + where(branch));
                    leaning = null;
                    return false;
                }
                if (!way.isEmpty()) {
                    if (!breakNext(ctx, way, WOOD_OR_LEAVES, false)) {
                        return false;
                    }
                    List<Pos> still = wayToLean(ctx, at, branch);
                    if (still == null || !still.isEmpty()) {
                        say(ctx, "cannot clear the way to lean from " + where(at)
                                + " for the branch at " + where(branch));
                        leaning = null;
                        return false;
                    }
                }
                if (!leaner.toward(branch.x() + 0.5, branch.z() + 0.5)) {
                    if (leanAskedAt < 0) {
                        leanAskedAt = ticks;
                    }
                    if (ticks - leanAskedAt < LEAN_RETRY_TICKS) {
                        return false; // still landing, most likely: ask again
                    }
                    say(ctx, "the body will not lean from " + where(at) + " for the branch at "
                            + where(branch));
                    leaning = null;
                    leanAskedAt = -1;
                    return false;
                }
                leanAskedAt = -1;
                say(ctx, "leaning from " + where(at) + " for the branch at " + where(branch));
                phase = "leaning for a branch";
                return false;
            }
            case LEANING, RELEASING -> {
                return false;
            }
            case LEANT -> {
                if (!breakNext(ctx, List.of(branch), WOOD, false)) {
                    return false;
                }
                if (blocks.at(branch.x(), branch.y(), branch.z()) == BlockKind.LOG) {
                    Pos block = ctx.actuators().breaker().obstruction(branch);
                    say(ctx, "a branch at " + where(branch) + " refused even leaning from "
                            + where(at) + " — " + (block == null ? "the arm calls it out of reach"
                            : where(block) + " is in the way"));
                }
                leaner.release();
                leanDone = true;
                return false;
            }
            case FAILED -> {
                say(ctx, "the lean from " + where(at) + " failed — the branch at " + where(branch)
                        + " stays");
                leaner.release();
                leanDone = true;
                return false;
            }
        }
        return false;
    }

    /**
     * What a body leant from {@code at} toward {@code branch} puts its box into, at its feet and
     * its head, that is not air or water: the cells to clear first. Null when there is no lean to
     * make — the branch is over the stand — or a cell is not the axe's to clear: stone, or
     * another tree's wood.
     */
    private @Nullable List<Pos> wayToLean(BrainContext ctx, Pos at, Pos branch) {
        BlockProbe blocks = ctx.percepts().blocks();
        double dx = branch.x() - at.x();
        double dz = branch.z() - at.z();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1.0E-6) {
            return null;
        }
        double x = at.x() + 0.5 + dx / d * Leaner.MAX_LEAN;
        double z = at.z() + 0.5 + dz / d * Leaner.MAX_LEAN;
        List<Pos> way = new ArrayList<>();
        for (int y = at.y(); y <= at.y() + 1; y++) {
            for (int cx = (int) Math.floor(x - BODY_HALF_WIDTH);
                    cx <= (int) Math.floor(x + BODY_HALF_WIDTH); cx++) {
                for (int cz = (int) Math.floor(z - BODY_HALF_WIDTH);
                        cz <= (int) Math.floor(z + BODY_HALF_WIDTH); cz++) {
                    if (cx == at.x() && cz == at.z()) {
                        continue;
                    }
                    BlockKind kind = blocks.at(cx, y, cz);
                    if (kind == BlockKind.AIR || kind == BlockKind.WATER) {
                        continue;
                    }
                    Pos cell = new Pos(cx, y, cz);
                    boolean leaf = kind == BlockKind.LEAVES
                            || blocks.idAt(cx, y, cz).endsWith("_leaves");
                    if (!leaf && !(kind == BlockKind.LOG && climb.owns(cell))) {
                        return null;
                    }
                    way.add(cell);
                }
            }
        }
        return way;
    }

    /**
     * The feet cell once the body is on something — null while it is mid-hop or falling, when
     * the cell it reads from is not the one it will stand in. A hop ordered off a mid-air read
     * lands a cell too high, in a slot with no floor, and the body falls down the column (the
     * first spiral, 2026-09-09). Believed anyway after {@link #AIRBORNE_TICKS}.
     */
    private @Nullable Pos settled(BrainContext ctx) {
        if (leaning != null) {
            return leaning.stand(); // out at the edge the feet read as the next cell over
        }
        Pos at = ctx.percepts().position();
        BlockKind under = ctx.percepts().blocks().at(at.x(), at.y() - 1, at.z());
        if (under != BlockKind.AIR) {
            airborneSince = -1;
            return at;
        }
        if (airborneSince < 0) {
            airborneSince = ticks;
        }
        return ticks - airborneSince > AIRBORNE_TICKS ? at : null;
    }

    // ── the legs and the arm ─────────────────────────────────────────────────────────────────

    /**
     * The legs: order the walk to {@code feet}, re-order it when the target moves or the legs gave
     * up a while ago, and report whether the body is there. Too many failures on one walk set the
     * chop giving up.
     */
    private boolean walk(BrainContext ctx, Pos feet) {
        Mover mover = ctx.actuators().mover();
        if (ctx.percepts().position().equals(feet)) {
            walkingTo = feet; // standing there already is arriving: no order for the legs
            arrived = true;
            return true;
        }
        if (!feet.equals(walkingTo)) {
            walkFailures = 0;
            order(mover, feet);
            return false;
        }
        if (ticks == walkOrderedAt) {
            return false; // issue, don't read: the port promises progress from the next tick on
        }
        switch (mover.state()) {
            case MOVING:
                return false;
            case ARRIVED:
                // The legs' own word: inside the arrival radius, even if the feet cell disagrees
                // by a corner. Stopping here rather than fussing is what stops a body shuffling.
                arrived = true;
                return true;
            case FAILED:
            case IDLE:
            default:
                if (arrived) {
                    return true; // done earlier; the legs have since been idle, as they should be
                }
                if (walkRetryAt == 0) {
                    String why = "walk to " + where(feet) + " failed — " + mover.failure().describe();
                    if (++walkFailures >= WALK_GIVE_UP) {
                        stuck = why + ", " + walkFailures + " times";
                    } else {
                        say(ctx, why + "; trying again in " + WALK_RETRY_TICKS / 20 + "s");
                    }
                    walkRetryAt = ticks + WALK_RETRY_TICKS;
                } else if (ticks >= walkRetryAt) {
                    order(mover, feet);
                }
                return false;
        }
    }

    private void order(Mover mover, Pos feet) {
        mover.moveTo(feet.x(), feet.y(), feet.z());
        walkingTo = feet;
        walkOrderedAt = ticks;
        walkRetryAt = 0;
        arrived = false;
    }

    private boolean breakNext(BrainContext ctx, List<Pos> cells, Set<BlockKind> kinds) {
        return breakNext(ctx, cells, kinds, true);
    }

    /**
     * The arm: begin on the first of {@code cells} the probe still shows as one of {@code kinds}
     * and the arm will take, one at a time, whether or not the legs are still walking. When the
     * cells are a {@code must}, returns whether none of them stands any more, and a lasting
     * refusal is counted against the chop; otherwise returns whether nothing could be begun
     * this tick — gone or refused alike — and a refusal is nobody's problem.
     */
    private boolean breakNext(BrainContext ctx, List<Pos> cells, Set<BlockKind> kinds,
                              boolean must) {
        BlockBreaker breaker = ctx.actuators().breaker();
        if (breaking != null) {
            BreakState state = breaker.state();
            if (state == BreakState.BREAKING) {
                return false;
            }
            if (state == BreakState.FINISHED) {
                if (breakingKind == BlockKind.LOG) {
                    logsBroken++;
                }
                walkFailures = 0; // the arm is getting somewhere, so the legs are not stuck yet
                say(ctx, (breakingKind == BlockKind.LEAVES ? "cleared a leaf" : "broke a log")
                        + " at " + where(breaking));
            }
            breaking = null; // FAILED or stopped under us: re-tried below if it still stands
        }
        BlockProbe blocks = ctx.percepts().blocks();
        // The block under the feet is the floor, never something to chew: a branch below the
        // body puts it on the arm's line, and a cherry's stand was chewed out from under it for
        // a limb two down (2026-09-10).
        Pos feet = leaning != null ? leaning.stand() : ctx.percepts().position();
        Pos floorCell = new Pos(feet.x(), feet.y() - 1, feet.z());
        Pos first = null;
        for (Pos cell : cells) {
            BlockKind kind = blocks.at(cell.x(), cell.y(), cell.z());
            if (!kinds.contains(kind)) {
                continue; // already gone
            }
            if (first == null) {
                first = cell;
            }
            // A refusal is out of reach or a blocked swing. The walk cures the first. What is in
            // the way is cured here when the axe is for it: a leaf, or a log of this very tree —
            // chewed through, a hop at a time, before the block it hides. The crown's own leaves
            // and its own low branches stand between a body and its trunk, and none is listed.
            Pos target = cell;
            BlockKind targetKind = kind;
            for (int hop = 0; hop <= CHEW_HOPS; hop++) {
                if (breaker.begin(target)) {
                    breaking = target;
                    breakingKind = targetKind;
                    refused = null;
                    return false;
                }
                Pos block = breaker.obstruction(target);
                if (block == null || block.equals(floorCell)) {
                    break; // out of reach, or through the floor: the walk's problem, or nobody's
                }
                BlockKind inTheWay = blocks.at(block.x(), block.y(), block.z());
                // A leaf on its decay rim reads as plain solid to the probe — a dying canopy is
                // not a tree's — but the axe takes it like any leaf, and the trunk's own crown is
                // dying by the time the swing at a limb is refused for it (2026-09-10).
                boolean leaf = inTheWay == BlockKind.LEAVES
                        || blocks.idAt(block.x(), block.y(), block.z()).endsWith("_leaves");
                boolean ours = inTheWay == BlockKind.LOG && climb != null && climb.owns(block);
                if (!leaf && !ours) {
                    break; // something the axe is not for: the walk's problem, or nobody's
                }
                target = block;
                targetKind = leaf ? BlockKind.LEAVES : BlockKind.LOG;
            }
        }
        if (must && first != null && (arrived || walkingTo == null)) {
            refusedFor(ctx, breaker, first);
        }
        return !must || first == null;
    }

    /**
     * The arm has been refused everything it has left and the legs are not going to change that.
     * Say so once it has lasted, and say what the breaker blames; a while later, give the tree up
     * for the same reason.
     */
    private void refusedFor(BrainContext ctx, BlockBreaker breaker, Pos cell) {
        if (!cell.equals(refused)) {
            refused = cell;
            refusedSince = ticks;
            return;
        }
        int lasted = ticks - refusedSince;
        if (lasted == SWING_REFUSED_TICKS || lasted == GIVE_UP_TICKS) {
            Pos block = breaker.obstruction(cell);
            String why = "cannot swing at " + where(cell) + " from here — "
                    + (block == null ? "out of reach" : where(block) + " is in the way");
            if (lasted == SWING_REFUSED_TICKS) {
                say(ctx, why);
            } else {
                stuck = why;
            }
        }
    }

    // ── ends ─────────────────────────────────────────────────────────────────────────────────

    private TaskStatus fail(BrainContext ctx, String why) {
        failure = why;
        phase = "gave up — " + why;
        say(ctx, phase);
        release(ctx);
        return TaskStatus.FAILED;
    }

    private void release(BrainContext ctx) {
        ctx.actuators().mover().stop();
        ctx.actuators().breaker().abort();
        ctx.actuators().riser().abort();
        ctx.actuators().leaner().release();
        leaning = null;
        leanDone = false;
        breaking = null;
        refused = null;
        rising = false;
        walkingTo = null;
        arrived = false;
    }

    @Override
    public void cancel(BrainContext ctx) {
        release(ctx);
    }

    @Override
    public String describe() {
        return "fell the tree at " + where(anchor) + " — " + phase
                + (approach == null ? "" : " — " + approach.summary());
    }

    @Override
    public String failureDetail() {
        return failure == null ? describe() + " failed" : failure;
    }

    /**
     * True, and deliberately so: the chop puts a body somewhere precarious on purpose — in a trunk,
     * on a pillar of its own logs — and the escape drive must not preempt it there.
     */
    @Override
    public boolean reshapesGround() {
        return true;
    }

    private static void say(BrainContext ctx, String detail) {
        ctx.journal().record(Category.BRAIN, "chop", detail);
    }

    private static String where(Pos cell) {
        return "(" + cell.x() + ", " + cell.y() + ", " + cell.z() + ")";
    }
}
