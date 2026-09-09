package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.BlockBreaker;
import dev.luizloyola.anima.core.brain.act.BreakState;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The eighth chop (decisions: Luiz, 2026-09-07 to 2026-09-09). Read which sides of the stump a
 * body could walk up to ({@link Approach}), take the cheapest, walk to its feet cell clearing the
 * leaves that side lists — only those — beginning on any of them the moment the arm can reach it,
 * mid-walk included. Work out the {@link Climb} from the cell it stands in. Then carry it out: open
 * the trunk and get in, hopping up onto the step up or digging in level with it; rise to the
 * height the tallest log demands, one placed log at a time, breaking only what is over the head;
 * break everything above from there; come down breaking underfoot until the feet are back at
 * floor level; step out and take the one log that may be left below floor level from outside. Then
 * SUCCESS, with the count, or FAILED with the one reason: a rise refused, nothing carried to rise
 * on, the arm refused a block for long, the legs gave up on a walk, or wood left standing that the
 * plan knew it could not reach.
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
     * How many leaves in a row the arm chews through to reach the block it was refused. A crown
     * hides its own trunk from the side, and the leaf the breaker blames is as often as not in
     * front of another; past this many the swing is not worth the path.
     */
    public static final int CHEW_HOPS = 3;

    /**
     * The stages, in order. Written into the save by NAME, so add at the end and never rename: a
     * saved body mid-stage reads its stage back by it. PLANNED is the moment of standing in the
     * trunk with the plan, and hands straight on.
     */
    public enum Stage { APPROACH, OPEN, ENTER, PLANNED, RISE, CLEAR, DESCEND, GROUND }

    /** What the arm takes: a side's leaves; whatever the trunk is opened through; the wood itself. */
    private static final Set<BlockKind> LEAVES_ONLY = Set.of(BlockKind.LEAVES);
    private static final Set<BlockKind> WOOD_OR_LEAVES = Set.of(BlockKind.LOG, BlockKind.LEAVES);
    private static final Set<BlockKind> WOOD = Set.of(BlockKind.LOG);

    private final Pos anchor;
    private Stage stage = Stage.APPROACH;
    private @Nullable Approach approach;
    private @Nullable Climb climb;
    /** The ring cell of the side being taken — held while it stays approachable. */
    private @Nullable Pos chosen;
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
            case CLEAR -> clear(ctx);
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
                return fail(ctx, "no way in — " + approach.summary());
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
            return fail(ctx, stuck);
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

    /** Work out the climb from beside the stump, and which stage it starts. */
    private void plan(BrainContext ctx, Approach.Side side, boolean jumpRoom) {
        int bodyCells = body(ctx);
        BlockProbe blocks = ctx.percepts().blocks();
        climb = Climb.plan(Climb.Arm.of(ctx.percepts()), anchor,
                Climb.column(anchor, blocks, bodyCells), blocks, side.feet(), jumpRoom, bodyCells);
        say(ctx, "plan — " + climb.describe());
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
        stage = climb.stepsIn() ? Stage.RISE : Stage.CLEAR;
        return stage == Stage.RISE ? rise(ctx) : clear(ctx);
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
        Pos over = new Pos(anchor.x(), at.y() + 2, anchor.z());
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

    /** Every log left in the column above the head — from inside, or from beside — lowest first. */
    private TaskStatus clear(BrainContext ctx) {
        int feet = ctx.percepts().position().y();
        List<Pos> cells = logsFrom(ctx, climb.stepsIn() ? feet + 2 : feet + 1);
        phase = "clearing above (" + cells.size() + " left)";
        if (breakNext(ctx, cells, WOOD)) {
            stage = climb.stepsIn() ? Stage.DESCEND : Stage.GROUND;
            return stage == Stage.DESCEND ? descend(ctx) : ground(ctx);
        }
        return stuck == null ? TaskStatus.RUNNING : fail(ctx, stuck);
    }

    /**
     * Break underfoot until the feet are back at floor level: the placed blocks first, then the
     * step-up log after a hop-in. Anything under the feet that is not a log is as far down as the
     * axe goes — the dirt under a sunken stump — and the way out from there is a step down.
     */
    private TaskStatus descend(BrainContext ctx) {
        Pos at = ctx.percepts().position();
        Pos below = new Pos(anchor.x(), at.y() - 1, anchor.z());
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
            if (blocks.at(anchor.x(), y, anchor.z()) == BlockKind.LOG) {
                cells.add(new Pos(anchor.x(), y, anchor.z()));
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
        return finish(ctx);
    }

    /** The column read one last time: SUCCESS with the count, or what is left and why. */
    private TaskStatus finish(BrainContext ctx) {
        List<Pos> left = logsFrom(ctx, climb.floor() - 1);
        int buried = 0;
        for (int y = anchor.y(); y < climb.floor() - 1; y++) {
            if (ctx.percepts().blocks().at(anchor.x(), y, anchor.z()) == BlockKind.LOG) {
                buried++;
            }
        }
        if (!left.isEmpty()) {
            return fail(ctx, left.size() + " left standing, the first at " + where(left.get(0))
                    + (climb.complete() ? "" : " — the plan knew"));
        }
        int felled = logsBroken - risen;
        say(ctx, "felled the tree at " + where(anchor) + " — " + felled + " logs"
                + (buried == 0 ? "" : ", " + buried + " left buried below the ground"));
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
            side = approach.best().orElse(null);
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
     * The logs standing in the trunk's column from {@code fromY} up to the plan's top, lowest
     * first — read without the gap rule {@link Climb#column} has, because from inside the gap
     * between the stump and the next log is whatever the body has cleared so far.
     */
    private List<Pos> logsFrom(BrainContext ctx, int fromY) {
        BlockProbe blocks = ctx.percepts().blocks();
        List<Pos> logs = new ArrayList<>();
        for (int y = fromY; y <= climb.top(); y++) {
            if (blocks.at(anchor.x(), y, anchor.z()) == BlockKind.LOG) {
                logs.add(new Pos(anchor.x(), y, anchor.z()));
            }
        }
        return logs;
    }

    private static int body(BrainContext ctx) {
        return MoveCapabilities.of(ctx.profile()).clearCells();
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

    /**
     * The arm: begin on the first of {@code cells} the probe still shows as one of {@code kinds}
     * and the arm will take, one at a time, whether or not the legs are still walking. Returns
     * whether none of them stands any more.
     */
    private boolean breakNext(BrainContext ctx, List<Pos> cells, Set<BlockKind> kinds) {
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
        Pos first = null;
        for (Pos cell : cells) {
            BlockKind kind = blocks.at(cell.x(), cell.y(), cell.z());
            if (!kinds.contains(kind)) {
                continue; // already gone
            }
            if (first == null) {
                first = cell;
            }
            // A refusal is out of reach or a blocked swing. The walk cures the first. A leaf in the
            // way is cured here: chewed through, a hop at a time, before the block it hides — the
            // crown's own leaves stand between a body and its trunk, and none of them is listed.
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
                if (block == null
                        || blocks.at(block.x(), block.y(), block.z()) != BlockKind.LEAVES) {
                    break; // out of reach, or something the axe is not for: the walk's problem
                }
                target = block;
                targetKind = BlockKind.LEAVES;
            }
        }
        if (first != null && (arrived || walkingTo == null)) {
            refusedFor(ctx, breaker, first);
        }
        return first == null;
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
