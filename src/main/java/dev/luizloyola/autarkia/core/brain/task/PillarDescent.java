package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.act.BlockBreaker;
import dev.luizloyola.autarkia.core.brain.act.BreakState;
import dev.luizloyola.autarkia.core.brain.act.MoveState;
import dev.luizloyola.autarkia.core.brain.act.ScaffoldState;
import dev.luizloyola.autarkia.core.brain.act.Scaffolder;
import dev.luizloyola.autarkia.core.brain.knowledge.BlockKind;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.log.Category;
import java.util.List;

/**
 * The one way down: un-build the body's standing ledger ({@link Scaffolder#placed()}), highest cell
 * first, column by column, broken with the {@link BlockBreaker} and reclaimed by the walk-over
 * pickup. Shared by {@link ChopTree} and {@link UnbuildPillar}, because the ledger outlives the
 * tasks that built it.
 *
 * <p><b>Strictly top-down within a column.</b> A cell is broken only while no higher cell of its
 * column stands; taking a lower one eats a tower's bottom out from under its standing top and
 * manufactures the floating columns this exists never to leave. A column whose top cannot be
 * reached — or that is <b>holding real blocks up</b> (an interrupted climb leaves the trunk remnant
 * on those cells) — is conceded whole: every cell struck from the ledger, the tower left intact and
 * journalled. A conceded in-column pillar reads as a grounded trunk, so the next chop fells remnant
 * and pillar as one tree.
 *
 * <p>Reach ladder for a tower not being stood on: swing at its top → WALK back to it (once per
 * column) → RE-CLIMB on their own carried logs ({@link Scaffolder#up}), whose recovery steps ledger
 * like any others.
 *
 * <p>Never fails: {@link #tick} returns RUNNING while there is un-building to do and SUCCESS the
 * moment the ledger is empty — an empty ledger is a cheap no-op SUCCESS, which lets callers use it
 * as a gate.
 */
final class PillarDescent {
    /** Action-less ticks (top unreachable, walk spent, no climb possible) before the column
     *  is conceded whole. */
    static final int STALL_TICKS = 3;

    private boolean breaking;
    private boolean walkIssued;
    /** One walk back per column — a failed return is escalated, not retried. */
    private boolean walkTried;
    private int stalls;
    /** The column being un-built, identified by (x, z). */
    private int columnX;
    private int columnZ;
    private boolean columnSet;

    /** One decision per tick against the ledger; see the class doc for the contract. */
    TaskStatus tick(BrainContext ctx) {
        BlockBreaker breaker = ctx.actuators().breaker();
        Scaffolder scaffolder = ctx.actuators().scaffolder();
        if (breaking) {
            if (breaker.state() == BreakState.BREAKING) {
                return TaskStatus.RUNNING;
            }
            breaking = false; // outcome is read from the world next tick: gone -> reclaimed
            return TaskStatus.RUNNING;
        }
        if (scaffolder.state() == ScaffoldState.RISING) {
            return TaskStatus.RUNNING; // a recovery step is mid-air
        }
        if (walkIssued) {
            if (ctx.actuators().mover().state() == MoveState.MOVING) {
                return TaskStatus.RUNNING;
            }
            walkIssued = false;
        }
        List<Pos> placed = scaffolder.placed();
        if (placed.isEmpty()) {
            return TaskStatus.SUCCESS;
        }
        Pos top = highest(placed);
        if (!columnSet || top.x() != columnX || top.z() != columnZ) {
            columnSet = true;
            columnX = top.x();
            columnZ = top.z();
            walkTried = false;
            stalls = 0;
        }
        if (ctx.percepts().blocks().at(top.x(), top.y(), top.z()) == BlockKind.AIR) {
            scaffolder.reclaim(top); // broken by their own last swing, or someone beat them to it
            return TaskStatus.RUNNING;
        }
        // The pillar stands in the trunk's own column, so an interrupted climb leaves real logs
        // above those cells; pulling them out is the other floater factory (the log-air-log gaps
        // are the reclaimed cells). Conceded whole, it reads as a grounded trunk instead.
        BlockKind above = ctx.percepts().blocks().at(top.x(), top.y() + 1, top.z());
        if (above != BlockKind.AIR && above != BlockKind.LEAVES && above != BlockKind.WATER) {
            int struck = strikeColumn(scaffolder, placed);
            ctx.journal().record(Category.BRAIN, "descend", "pillar at (" + columnX + ", "
                    + columnZ + ") is holding blocks up — left whole (" + struck + " cell"
                    + (struck == 1 ? "" : "s") + ") for a proper fell");
            columnSet = false;
            return TaskStatus.RUNNING;
        }
        if (breaker.begin(top)) {
            breaking = true;
            stalls = 0;
            return TaskStatus.RUNNING;
        }
        // The top is out of reach. Never a lower cell instead — that is the floater factory.
        // Walk back to the tower first ...
        if (!walkTried) {
            walkTried = true;
            ctx.actuators().mover().moveTo(top.x(), top.y() + 1, top.z());
            walkIssued = true;
            return TaskStatus.RUNNING;
        }
        // ... then climb for it — but only when climbing converges: the top well overhead, its
        // column within a step. Without that guard a same-height cell the arm refused had them
        // place a recovery step, which became the highest cell and was broken back out from under
        // them — place, break, forever (185 cycles).
        Pos feet = ctx.percepts().position();
        boolean converges = top.y() > feet.y() + 1 && horizontalDistSq(feet, top) <= 2 * 2;
        String block = converges ? carriedPillarBlock(ctx) : null;
        if (block != null && scaffolder.up(block)) {
            stalls = 0;
            return TaskStatus.RUNNING;
        }
        if (++stalls >= STALL_TICKS) {
            int struck = strikeColumn(scaffolder, placed);
            ctx.journal().record(Category.BRAIN, "descend", "left a pillar standing whole at ("
                    + columnX + ", " + columnZ + ") — " + struck + " cell"
                    + (struck == 1 ? "" : "s") + " out of reach");
            columnSet = false; // next tick picks the next column, or SUCCESS
        }
        return TaskStatus.RUNNING;
    }

    /** Concede the focus column whole: strike its every cell from the ledger, break nothing. */
    private int strikeColumn(Scaffolder scaffolder, List<Pos> placed) {
        int struck = 0;
        for (Pos cell : List.copyOf(placed)) {
            if (cell.x() == columnX && cell.z() == columnZ) {
                scaffolder.reclaim(cell);
                struck++;
            }
        }
        return struck;
    }

    private static long horizontalDistSq(Pos a, Pos b) {
        long dx = a.x() - b.x();
        long dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    private static Pos highest(List<Pos> cells) {
        Pos top = cells.get(0);
        for (Pos cell : cells) {
            if (cell.y() > top.y()) {
                top = cell;
            }
        }
        return top;
    }

    /** A carried block the recovery climb may spend: their own logs, the lumberjack's stock. */
    private static String carriedPillarBlock(BrainContext ctx) {
        for (var entry : ctx.percepts().inventory().occupied()) {
            String id = entry.stack().id();
            if (id.endsWith("_log") || id.endsWith("_stem")) {
                return id;
            }
        }
        return null;
    }

    /** Release the actuators a descent may hold — the cancel half of the Navigator contract. */
    void cancel(BrainContext ctx) {
        ctx.actuators().breaker().abort();
        ctx.actuators().mover().stop();
        ctx.actuators().scaffolder().abort();
        breaking = false;
        walkIssued = false;
    }
}
