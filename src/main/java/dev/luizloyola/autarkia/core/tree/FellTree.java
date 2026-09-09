package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.BlockBreaker;
import dev.luizloyola.anima.core.brain.act.BreakState;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.act.Mover;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.PrimitiveTask;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The eighth chop, being built one step at a time (decisions: Luiz, 2026-09-07). So far: read
 * which sides of the stump a body could walk up to ({@link Approach}), take the cheapest, walk to
 * its feet cell, and clear the leaves that side lists — only those — beginning on any of them the
 * moment the arm can reach it, mid-walk included. Then work out where the arm has to be to reach
 * every log ({@link Climb}), counting the trunk from the cell it stands in: if the top is out of
 * reach from there, open the body's height of trunk above that cell and step in onto whatever is
 * at its own height in the column — the base log, the log above it where the ground is raised,
 * the dirt under the base where it is sunken. Then stand there with the plan. It fells nothing
 * yet and never ends: RUNNING for as long as it is left in the slot, so each step can be watched
 * in-world ({@code /autarkia tree approach}) and the ground changed under it.
 *
 * <p><b>The world holds the progress, the save holds the stage.</b> The survey is re-read every
 * second, the walk is re-ordered when the legs give up, and a block is only ever begun on because
 * the probe still says it is there — so a shove costs at most a second of looking. What the world
 * cannot give back is written down (decision: Luiz, 2026-09-07): the stage, the side taken and the
 * plan, because once the trunk is opened the plan can no longer be read off the tree. A fresh task
 * that finds the body already standing in the trunk plans from there rather than walking out
 * to walk back in, which is what a preemption's re-derived plan meets. The side taken is kept
 * because selection is commitment: a body walking past a symmetric pair of sides would otherwise
 * flip between them as it went.
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

    /**
     * The stages so far, in order. Written into the save by NAME, so add at the end and never
     * rename: a saved body mid-stage reads its stage back by it.
     */
    public enum Stage { APPROACH, OPEN, ENTER, PLANNED }

    /** What the arm takes on the way in: a side's leaves; whatever the trunk is opened through. */
    private static final Set<BlockKind> LEAVES_ONLY = Set.of(BlockKind.LEAVES);
    private static final Set<BlockKind> WOOD_OR_LEAVES = Set.of(BlockKind.LOG, BlockKind.LEAVES);

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
    /** The block under the arm, or null, and what it was when the swing began. */
    private @Nullable Pos breaking;
    private @Nullable BlockKind breakingKind;
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

    /** {@code "walking to W (2)"}, {@code "clearing leaves at W"}, {@code "opening the trunk"}, … */
    public String phase() {
        return phase;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        if (approach == null || ticks % RESURVEY_TICKS == 0) {
            look(ctx);
        }
        // A stage that finishes hands straight on to the next, so no tick is spent idle between.
        switch (stage) {
            case APPROACH -> approach(ctx);
            case OPEN -> open(ctx);
            case ENTER -> enter(ctx);
            case PLANNED -> planned();
        }
        ticks++;
        return TaskStatus.RUNNING; // nothing here ends it yet
    }

    /** Take out what stands a body's height above the step up, lowest first. */
    private void open(BrainContext ctx) {
        phase = "opening the trunk";
        if (breakNext(ctx, climb.stepIn(), WOOD_OR_LEAVES)) {
            stage = Stage.ENTER;
            enter(ctx);
        }
    }

    /** Onto the step up, in the trunk's own column. */
    private void enter(BrainContext ctx) {
        phase = "stepping in";
        if (walk(ctx, climb.stand())) {
            stage = Stage.PLANNED;
            say(ctx, "in the trunk — " + climb.describe());
            planned();
        }
    }

    /** Stand with the plan; carrying it out is the next step. */
    private void planned() {
        phase = (climb.stepsIn() ? "in the trunk — " : "at the tree — ") + climb.describe();
    }

    /** The first stage: take a side, walk to it clearing its leaves, then plan the climb. */
    private void approach(BrainContext ctx) {
        Approach.Side side = take(ctx);
        if (side == null) {
            phase = "no way in";
            ctx.actuators().mover().stop();
            return; // and keep looking: the ground may change
        }
        // Already in the trunk's column, at or above the base: a fresh task after a preemption, or
        // a reload that lost its plan. Plan from here; walking out to walk back in is not a step.
        Pos at = ctx.percepts().position();
        if (at.x() == anchor.x() && at.z() == anchor.z() && at.y() >= anchor.y()) {
            plan(ctx, side);
            if (stage == Stage.OPEN) {
                open(ctx);
            }
            return;
        }
        boolean cleared = breakNext(ctx, side.leaves(), LEAVES_ONLY);
        boolean there = walk(ctx, side.feet());
        String label = approach.bearing(side.cell());
        if (!there) {
            phase = "walking to " + label + " (" + Approach.fmt(side.score()) + ")";
            return;
        }
        if (!cleared) {
            phase = "clearing leaves at " + label;
            return;
        }
        plan(ctx, side);
        phase = "at the tree, " + label + " side";
    }

    /** Work out the climb from beside the stump, and which stage it starts. */
    private void plan(BrainContext ctx, Approach.Side side) {
        int bodyCells = MoveCapabilities.of(ctx.profile()).clearCells();
        BlockProbe blocks = ctx.percepts().blocks();
        climb = Climb.plan(Climb.Arm.of(ctx.percepts()), anchor,
                Climb.column(anchor, blocks, bodyCells), blocks, side.feet(), bodyCells);
        say(ctx, "plan — " + climb.describe());
        stage = climb.stepsIn() ? Stage.OPEN : Stage.PLANNED;
    }

    private void look(BrainContext ctx) {
        approach = Approach.survey(anchor, ctx.percepts().position(), ctx.percepts().blocks(),
                MoveCapabilities.of(ctx.profile()).clearCells());
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
        Approach.Side side = null;
        if (chosen != null) {
            for (Approach.Side candidate : approach.sides()) {
                if (candidate.cell().equals(chosen) && candidate.verdict().approachable()) {
                    side = candidate;
                }
            }
        }
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

    /**
     * The legs: order the walk to {@code feet}, re-order it when the target moves or the legs gave
     * up a while ago, and report whether the body is there.
     */
    private boolean walk(BrainContext ctx, Pos feet) {
        Mover mover = ctx.actuators().mover();
        if (ctx.percepts().position().equals(feet)) {
            walkingTo = feet; // standing there already is arriving: no order for the legs
            arrived = true;
            return true;
        }
        if (!feet.equals(walkingTo)) {
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
                    say(ctx, "walk to " + where(feet) + " failed — " + mover.failure().describe()
                            + "; trying again in " + WALK_RETRY_TICKS / 20 + "s");
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
                say(ctx, (breakingKind == BlockKind.LEAVES ? "cleared a leaf" : "broke a log")
                        + " at " + where(breaking));
            }
            breaking = null; // FAILED or stopped under us: re-tried below if it still stands
        }
        boolean left = false;
        for (Pos cell : cells) {
            BlockKind kind = ctx.percepts().blocks().at(cell.x(), cell.y(), cell.z());
            if (!kinds.contains(kind)) {
                continue; // already gone
            }
            left = true;
            // A refusal is out of reach or a blocked swing — the walk cures both; ask again next tick.
            if (breaker.begin(cell)) {
                breaking = cell;
                breakingKind = kind;
                return false;
            }
        }
        return !left;
    }

    @Override
    public void cancel(BrainContext ctx) {
        ctx.actuators().mover().stop();
        ctx.actuators().breaker().abort();
        breaking = null;
        walkingTo = null;
        arrived = false;
    }

    @Override
    public String describe() {
        return "fell the tree at " + where(anchor) + " — " + phase
                + (approach == null ? "" : " — " + approach.summary());
    }

    @Override
    public String failureDetail() {
        return "nobody knows how to fell a tree yet";
    }

    /**
     * True already, and deliberately so: whatever the eighth turns out to be, it puts a body
     * somewhere precarious on purpose, and the escape drive must not preempt it. Declaring it here
     * keeps the exemption wired while the choreography is missing.
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
