package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.act.MoveState;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The {@link TaskExecutor}'s slot lifecycle with a PRIMITIVE root — step-2 semantics the step-3
 * tree machinery must preserve unchanged. Tree execution is covered in
 * {@link TaskExecutorTreeTest}.
 */
class TaskExecutorTest {

    private final FakeContext ctx = new FakeContext();
    private final FakeMover mover = ctx.mover;
    private final TaskExecutor executor = new TaskExecutor();

    @Test
    void idleTickIsANoOp() {
        executor.tick(ctx);
        assertFalse(executor.isBusy());
        assertEquals(0, mover.moveToCalls);
        assertEquals(0, mover.stopCalls);
        assertEquals("idle", executor.describe(), "nothing ever ran -> the plain idle shape");
    }

    @Test
    void runInstallsWithoutTickingUntilTheNextTick() {
        executor.run(new GoTo(12, -60, 8), ctx);
        assertTrue(executor.isBusy());
        assertEquals(0, mover.moveToCalls, "run installs; only tick gives the first decision");
        assertEquals("running: goto (12, -60, 8)", executor.describe());
        executor.tick(ctx);
        assertEquals(1, mover.moveToCalls, "the next tick drives the task");
    }

    @Test
    void terminalSuccessClearsTheSlotAndShowsInDescribe() {
        executor.run(new GoTo(12, -60, 8), ctx);
        executor.tick(ctx); // issues the move
        mover.setState(MoveState.ARRIVED);
        executor.tick(ctx); // observes ARRIVED -> SUCCESS
        assertFalse(executor.isBusy());
        assertEquals("idle (last: goto (12, -60, 8) -> SUCCESS)", executor.describe());
    }

    @Test
    void terminalFailureClearsTheSlotAndShowsInDescribe() {
        executor.run(new GoTo(1, 2, 3), ctx);
        executor.tick(ctx);
        mover.setState(MoveState.FAILED);
        executor.tick(ctx);
        assertFalse(executor.isBusy());
        assertEquals("idle (last: goto (1, 2, 3) -> FAILED)", executor.describe());
    }

    @Test
    void finishedTaskIsNeverTickedAgain() {
        executor.run(new GoTo(1, 2, 3), ctx);
        executor.tick(ctx);
        mover.setState(MoveState.ARRIVED);
        executor.tick(ctx);
        executor.tick(ctx); // idle again — must not touch the finished task or the mover
        assertEquals(1, mover.moveToCalls);
    }

    /**
     * Preemption ordering: the incumbent's cancel (its {@code stop}) must land before the
     * newcomer's first actuator order.
     */
    @Test
    void runWhileBusyCancelsTheIncumbentBeforeTheNewcomerActs() {
        executor.run(new GoTo(1, 2, 3), ctx);
        executor.tick(ctx); // incumbent issues its move
        executor.run(new GoTo(4, 5, 6), ctx);
        executor.tick(ctx); // newcomer issues its move
        assertEquals(List.of("moveTo(1, 2, 3)", "stop", "moveTo(4, 5, 6)"), mover.events);
        assertTrue(executor.isBusy());
        assertEquals("running: goto (4, 5, 6)", executor.describe());
    }

    @Test
    void cancelClearsWithoutRecordingATerminalStatus() {
        executor.run(new GoTo(1, 2, 3), ctx);
        executor.tick(ctx);
        executor.cancel(ctx);
        assertFalse(executor.isBusy());
        assertEquals(1, mover.stopCalls, "cancel releases the task's actuators");
        assertEquals("idle", executor.describe(),
                "a cancelled task neither succeeded nor failed — nothing to remember");
        executor.cancel(ctx); // cancelling while idle is a no-op
        assertEquals(1, mover.stopCalls);
    }

    /** Cancel must not overwrite the last real outcome either. */
    @Test
    void cancelKeepsTheEarlierTerminalOutcomeInTheReadout() {
        executor.run(new GoTo(1, 2, 3), ctx);
        executor.tick(ctx);
        mover.setState(MoveState.ARRIVED);
        executor.tick(ctx); // -> SUCCESS remembered
        executor.run(new GoTo(4, 5, 6), ctx);
        executor.cancel(ctx);
        assertEquals("idle (last: goto (1, 2, 3) -> SUCCESS)", executor.describe());
    }
}
