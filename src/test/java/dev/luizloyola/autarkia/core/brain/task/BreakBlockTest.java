package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.autarkia.core.brain.act.BreakState;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import org.junit.jupiter.api.Test;

/** The break primitive against the scripted arm: begin-once, observe, failure, clean cancel. */
class BreakBlockTest {

    private final FakeContext ctx = new FakeContext();

    @Test
    void beginsOnFirstTickThenRunsUntilTheArmFinishes() {
        BreakBlock task = new BreakBlock(10, 64, 8);

        assertEquals(TaskStatus.RUNNING, task.tick(ctx));
        assertEquals(new Pos(10, 64, 8), ctx.breaker.target);
        assertEquals(1, ctx.breaker.begins, "begin exactly once");

        assertEquals(TaskStatus.RUNNING, task.tick(ctx), "arm still breaking");
        ctx.breaker.state = BreakState.FINISHED;
        assertEquals(TaskStatus.SUCCESS, task.tick(ctx));
        assertEquals(1, ctx.breaker.begins, "observing never re-begins");
    }

    @Test
    void refusedBeginFailsImmediately() {
        ctx.breaker.refuseBegin = true;
        assertEquals(TaskStatus.FAILED, new BreakBlock(0, 64, 0).tick(ctx),
                "air/unreachable/unbreakable -> the parent method re-resolves, no retry loop");
    }

    @Test
    void armFailureAndExternalStopBothFailTheTask() {
        BreakBlock task = new BreakBlock(0, 64, 0);
        task.tick(ctx);
        ctx.breaker.state = BreakState.FAILED;
        assertEquals(TaskStatus.FAILED, task.tick(ctx), "block changed / reach lost");

        FakeContext fresh = new FakeContext();
        BreakBlock second = new BreakBlock(0, 64, 0);
        second.tick(fresh);
        fresh.breaker.state = BreakState.IDLE;
        assertEquals(TaskStatus.FAILED, second.tick(fresh), "stopped out from under the task");
    }

    @Test
    void cancelAbortsTheArmAndIsIdempotent() {
        BreakBlock task = new BreakBlock(0, 64, 0);
        task.tick(ctx);
        task.cancel(ctx);
        task.cancel(ctx);
        assertEquals(2, ctx.breaker.aborts, "cancel is unconditional and safe to repeat");
        assertEquals(BreakState.IDLE, ctx.breaker.state);
    }
}
