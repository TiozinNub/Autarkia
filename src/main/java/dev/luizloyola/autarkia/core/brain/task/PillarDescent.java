package dev.luizloyola.autarkia.core.brain.task;

import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.act.BlockBreaker;
import dev.luizloyola.autarkia.core.brain.act.BreakState;
import dev.luizloyola.autarkia.core.brain.act.MoveState;
import dev.luizloyola.autarkia.core.brain.act.Scaffolder;
import dev.luizloyola.autarkia.core.brain.knowledge.BlockKind;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.log.Category;
import java.util.List;

/**
 * The one way down: un-build the body's standing ledger ({@link Scaffolder#placed()}), newest
 * cell first, standing on each and breaking it underfoot with the {@link BlockBreaker}; the
 * walk-over pickup reclaims the drops. Shared by {@link ChopTree} and {@link UnbuildPillar},
 * because the ledger outlives tasks.
 *
 * <p>Never fails: a cell that can't be broken or walked back to is struck from the ledger and
 * left standing (journalled) — one orphaned block beats looping forever. {@link #tick} is
 * RUNNING while work remains and SUCCESS the moment the ledger is empty with no break in flight,
 * so an empty-ledger call is a cheap no-op SUCCESS that callers use as a gate.
 */
final class PillarDescent {
    private boolean breaking;
    private boolean walkIssued;

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
        List<Pos> placed = scaffolder.placed();
        if (placed.isEmpty()) {
            return TaskStatus.SUCCESS;
        }
        Pos step = placed.get(0); // newest first — the cell under (or nearest) her feet
        if (ctx.percepts().blocks().at(step.x(), step.y(), step.z()) == BlockKind.AIR) {
            scaffolder.reclaim(step); // broken by her own last swing, or someone beat her to it
            walkIssued = false;
            return TaskStatus.RUNNING;
        }
        if (breaker.begin(step)) {
            breaking = true;
            return TaskStatus.RUNNING;
        }
        if (!walkIssued) {
            ctx.actuators().mover().moveTo(step.x(), step.y() + 1, step.z());
            walkIssued = true;
            return TaskStatus.RUNNING;
        }
        if (ctx.actuators().mover().state() == MoveState.MOVING) {
            return TaskStatus.RUNNING;
        }
        walkIssued = false;
        scaffolder.reclaim(step); // can't get back to it — leave one block rather than loop forever
        ctx.journal().record(Category.BRAIN, "descend", "left a pillar block behind (unreachable)");
        return TaskStatus.RUNNING;
    }

    /** Release the actuators a descent may hold — the cancel half of the Navigator contract. */
    void cancel(BrainContext ctx) {
        ctx.actuators().breaker().abort();
        ctx.actuators().mover().stop();
        breaking = false;
        walkIssued = false;
    }
}
