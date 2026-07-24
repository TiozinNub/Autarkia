package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.act.BreakState;
import dev.luizloyola.autarkia.core.brain.instinct.DescendInstinct;
import dev.luizloyola.autarkia.core.brain.knowledge.BlockKind;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import org.junit.jupiter.api.Test;

/**
 * The strand-recovery pair for towers whose building task died: {@link UnbuildPillar} clears the
 * ledger newest first, conceding unreachable cells rather than looping, and
 * {@link DescendInstinct} bids exactly while a ledger stands.
 */
class UnbuildPillarTest {

    private final FakeContext ctx = new FakeContext();

    private void tower(Pos... cells) {
        for (Pos cell : cells) {
            ctx.percepts.blocks.set(cell.x(), cell.y(), cell.z(), BlockKind.LOG);
            ctx.scaffolder.placed.push(cell);
        }
    }

    private TaskStatus drive(UnbuildPillar task, int maxTicks) {
        TaskStatus status = TaskStatus.RUNNING;
        for (int i = 0; i < maxTicks && status == TaskStatus.RUNNING; i++) {
            status = task.tick(ctx);
            if (ctx.breaker.state == BreakState.BREAKING) {
                Pos t = ctx.breaker.target;
                ctx.percepts.blocks.clear(t.x(), t.y(), t.z());
                ctx.breaker.state = BreakState.FINISHED;
            }
        }
        return status;
    }

    @Test
    void unbuildsTheLedgerNewestFirst() {
        Pos first = new Pos(5, 64, 5);
        Pos second = new Pos(5, 65, 5);
        tower(first, second);
        ctx.percepts.position = new Pos(5, 66, 5); // standing on top, like a stranded climber

        TaskStatus status = drive(new UnbuildPillar(), 50);

        assertEquals(TaskStatus.SUCCESS, status);
        assertEquals(java.util.List.of(second, first), ctx.breaker.targets,
                "broken underfoot, newest cell first — the player's way down a nerd-pole");
        assertTrue(ctx.scaffolder.placed.isEmpty());
    }

    @Test
    void anUnreachableCellIsLeftStandingNotLoopedOn() {
        Pos reachable = new Pos(5, 64, 5);
        Pos marooned = new Pos(9, 70, 9); // a stray segment she can't get back to
        tower(reachable, marooned);
        ctx.breaker.refuse.add(marooned); // out of arm's reach, and the walk won't help (IDLE mover)

        TaskStatus status = drive(new UnbuildPillar(), 50);

        assertEquals(TaskStatus.SUCCESS, status, "one orphan block beats looping forever");
        assertTrue(ctx.scaffolder.placed.isEmpty(), "struck from the ledger either way");
        assertEquals(java.util.List.of(reachable), ctx.breaker.targets,
                "only the reachable cell was actually broken");
    }

    @Test
    void anEmptyLedgerIsAnInstantSuccess() {
        assertEquals(TaskStatus.SUCCESS, new UnbuildPillar().tick(ctx));
    }

    @Test
    void theDescendInstinctBidsExactlyWhileALedgerStands() {
        DescendInstinct instinct = new DescendInstinct();
        assertEquals(0.0, instinct.pressure(ctx), "no tower, no want");
        tower(new Pos(5, 64, 5));
        assertEquals(DescendInstinct.PRESSURE, instinct.pressure(ctx));
        assertTrue(instinct.pressure(ctx) < 0.6,
                "below the arbiter's PREEMPT bar: a chop legitimately mid-climb is never cut");
        assertTrue(instinct.root(ctx) instanceof UnbuildPillar);
    }
}
