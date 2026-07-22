package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.act.ActuatorAccess;
import dev.luizloyola.autarkia.core.brain.act.MoveState;
import dev.luizloyola.autarkia.core.brain.act.Mover;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the {@link TaskExecutor}'s one-slot lifecycle: idle no-op, run-then-tick
 * driving, terminal clear + remembered outcome, preemption ordering (the incumbent's actuators
 * are released before the newcomer touches them), the record-nothing cancel, and all three
 * {@code describe()} shapes.
 */
class TaskExecutorTest {

    private final FakeMover mover = new FakeMover();
    private final ActuatorAccess actuators = new ActuatorAccess() {
        @Override
        public Mover mover() {
            return mover;
        }
    };
    private final TaskExecutor executor = new TaskExecutor();

    @Test
    void idleTickIsANoOp() {
        executor.tick(actuators);
        assertFalse(executor.isBusy());
        assertEquals(0, mover.moveToCalls);
        assertEquals(0, mover.stopCalls);
        assertEquals("idle", executor.describe(), "nothing ever ran -> the plain idle shape");
    }

    @Test
    void runInstallsWithoutTickingUntilTheNextTick() {
        executor.run(new GoTo(12, -60, 8), actuators);
        assertTrue(executor.isBusy());
        assertEquals(0, mover.moveToCalls, "run installs; only tick gives the first decision");
        assertEquals("running: goto (12, -60, 8)", executor.describe());
        executor.tick(actuators);
        assertEquals(1, mover.moveToCalls, "the next tick drives the task");
    }

    @Test
    void terminalSuccessClearsTheSlotAndShowsInDescribe() {
        executor.run(new GoTo(12, -60, 8), actuators);
        executor.tick(actuators); // issues the move
        mover.setState(MoveState.ARRIVED);
        executor.tick(actuators); // observes ARRIVED -> SUCCESS
        assertFalse(executor.isBusy());
        assertEquals("idle (last: goto (12, -60, 8) -> SUCCESS)", executor.describe());
    }

    @Test
    void terminalFailureClearsTheSlotAndShowsInDescribe() {
        executor.run(new GoTo(1, 2, 3), actuators);
        executor.tick(actuators);
        mover.setState(MoveState.FAILED);
        executor.tick(actuators);
        assertFalse(executor.isBusy());
        assertEquals("idle (last: goto (1, 2, 3) -> FAILED)", executor.describe());
    }

    @Test
    void finishedTaskIsNeverTickedAgain() {
        executor.run(new GoTo(1, 2, 3), actuators);
        executor.tick(actuators);
        mover.setState(MoveState.ARRIVED);
        executor.tick(actuators);
        executor.tick(actuators); // idle again — must not touch the finished task or the mover
        assertEquals(1, mover.moveToCalls);
    }

    /**
     * Preemption ordering: the incumbent's cancel (its {@code stop}) must land before the
     * newcomer's first actuator order.
     */
    @Test
    void runWhileBusyCancelsTheIncumbentBeforeTheNewcomerActs() {
        executor.run(new GoTo(1, 2, 3), actuators);
        executor.tick(actuators); // incumbent issues its move
        executor.run(new GoTo(4, 5, 6), actuators);
        executor.tick(actuators); // newcomer issues its move
        assertEquals(List.of("moveTo(1, 2, 3)", "stop", "moveTo(4, 5, 6)"), mover.events);
        assertTrue(executor.isBusy());
        assertEquals("running: goto (4, 5, 6)", executor.describe());
    }

    @Test
    void cancelClearsWithoutRecordingATerminalStatus() {
        executor.run(new GoTo(1, 2, 3), actuators);
        executor.tick(actuators);
        executor.cancel(actuators);
        assertFalse(executor.isBusy());
        assertEquals(1, mover.stopCalls, "cancel releases the task's actuators");
        assertEquals("idle", executor.describe(),
                "a cancelled task neither succeeded nor failed — nothing to remember");
        executor.cancel(actuators); // cancelling while idle is a no-op
        assertEquals(1, mover.stopCalls);
    }

    /** Cancel must not overwrite the last real outcome either. */
    @Test
    void cancelKeepsTheEarlierTerminalOutcomeInTheReadout() {
        executor.run(new GoTo(1, 2, 3), actuators);
        executor.tick(actuators);
        mover.setState(MoveState.ARRIVED);
        executor.tick(actuators); // -> SUCCESS remembered
        executor.run(new GoTo(4, 5, 6), actuators);
        executor.cancel(actuators);
        assertEquals("idle (last: goto (1, 2, 3) -> SUCCESS)", executor.describe());
    }
}
