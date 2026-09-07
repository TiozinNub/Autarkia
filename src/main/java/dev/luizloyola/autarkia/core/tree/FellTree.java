package dev.luizloyola.autarkia.core.tree;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.BlockBreaker;
import dev.luizloyola.anima.core.brain.act.BreakState;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.act.Mover;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.PrimitiveTask;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The eighth chop, being built one step at a time (decisions: Luiz, 2026-09-07). So far: read
 * which sides of the stump a body could walk up to ({@link Approach}), take the cheapest, walk to
 * its feet cell, and clear the leaves that side lists — only those — beginning on any of them the
 * moment the arm can reach it, mid-walk included. Then stand there. It fells nothing yet and never
 * ends: RUNNING for as long as it is left in the slot, so each step can be watched in-world
 * ({@code /autarkia tree approach}) and the ground changed under it.
 *
 * <p><b>The world holds the progress.</b> The survey is re-read every second, the walk is
 * re-ordered when the legs give up, and a leaf is only ever begun on because the probe still says
 * it is there — so a reload, a shove or a preemption costs at most a second of looking, and the
 * codec carries the anchor alone. The one thing kept is the side taken: selection is commitment,
 * or a body walking past a symmetric pair of sides would flip between them as it went.
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

    private final Pos anchor;
    private @Nullable Approach approach;
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
    /** The leaf under the arm, or null. */
    private @Nullable Pos breaking;
    private int ticks;
    /** The last summary journalled — a re-read that says the same thing says nothing. */
    private String told = "";
    private String phase = "looking";

    public FellTree(Pos anchor) {
        this.anchor = anchor;
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

    /** {@code "walking to W (2)"}, {@code "clearing leaves at W"}, {@code "at the tree, W side"}. */
    public String phase() {
        return phase;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        if (approach == null || ticks % RESURVEY_TICKS == 0) {
            look(ctx);
        }
        Approach.Side side = take(ctx);
        if (side == null) {
            phase = "no way in";
            ctx.actuators().mover().stop();
            ticks++;
            return TaskStatus.RUNNING; // and keep looking: the ground may change
        }
        boolean cleared = clear(ctx, side);
        boolean there = walk(ctx, side);
        String label = approach.bearing(side.cell());
        phase = there ? (cleared ? "at the tree, " + label + " side" : "clearing leaves at " + label)
                : "walking to " + label + " (" + Approach.fmt(side.score()) + ")";
        ticks++;
        return TaskStatus.RUNNING; // nothing here ends it yet
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
     * The legs: order the walk to the side's feet cell, re-order it when the target moves or the
     * legs gave up a while ago, and report whether the body is there.
     */
    private boolean walk(BrainContext ctx, Approach.Side side) {
        Mover mover = ctx.actuators().mover();
        Pos feet = side.feet();
        if (!feet.equals(walkingTo)) {
            order(mover, feet);
            return false;
        }
        if (ctx.percepts().position().equals(feet)) {
            return true;
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
     * The arm: begin on any listed leaf the probe still shows and the arm will take, one at a
     * time; whether or not the legs are still walking. Returns whether nothing is left to clear.
     */
    private boolean clear(BrainContext ctx, Approach.Side side) {
        BlockBreaker breaker = ctx.actuators().breaker();
        if (breaking != null) {
            BreakState state = breaker.state();
            if (state == BreakState.BREAKING) {
                return false;
            }
            if (state == BreakState.FINISHED) {
                say(ctx, "cleared a leaf at " + where(breaking));
            }
            breaking = null; // FAILED or stopped under us: the leaf is re-tried below if it stands
        }
        boolean left = false;
        for (Pos leaf : side.leaves()) {
            if (ctx.percepts().blocks().at(leaf.x(), leaf.y(), leaf.z()) != BlockKind.LEAVES) {
                continue; // already gone; the next survey drops it from the list
            }
            left = true;
            // A refusal is out of reach or a blocked swing — the walk cures both; ask again next tick.
            if (breaker.begin(leaf)) {
                breaking = leaf;
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
