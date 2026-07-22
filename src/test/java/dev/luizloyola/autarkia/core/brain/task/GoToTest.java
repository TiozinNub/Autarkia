package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.autarkia.core.brain.act.ActuatorAccess;
import dev.luizloyola.autarkia.core.brain.act.MoveState;
import dev.luizloyola.autarkia.core.brain.act.Mover;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link GoTo} against a {@link FakeMover}: the issue-then-RUNNING first-tick
 * semantic (see the GoTo class doc), the mover-state-to-task-status mapping on later ticks, and
 * the idempotent cancel contract.
 */
class GoToTest {

    private final FakeMover mover = new FakeMover();
    private final ActuatorAccess actuators = new ActuatorAccess() {
        @Override
        public Mover mover() {
            return mover;
        }
    };

    /**
     * Issue, don't read: the fake is pre-set to ARRIVED and moveTo does not overwrite it, yet the
     * first tick still reports RUNNING — state is consulted only from the second tick on.
     */
    @Test
    void firstTickIssuesTheMoveAndReportsRunningWithoutReadingState() {
        mover.setState(MoveState.ARRIVED);
        GoTo task = new GoTo(12, -60, 8);
        assertEquals(TaskStatus.RUNNING, task.tick(actuators), "first tick issues, never reads");
        assertEquals(1, mover.moveToCalls);
        assertEquals(12, mover.lastX);
        assertEquals(-60, mover.lastY);
        assertEquals(8, mover.lastZ);
        assertEquals(TaskStatus.SUCCESS, task.tick(actuators), "second tick reads the state");
    }

    @Test
    void moveToIsIssuedExactlyOnceAcrossManyTicks() {
        GoTo task = new GoTo(0, 64, 0);
        task.tick(actuators);
        mover.setState(MoveState.MOVING);
        for (int i = 0; i < 5; i++) {
            assertEquals(TaskStatus.RUNNING, task.tick(actuators));
        }
        assertEquals(1, mover.moveToCalls, "the order is issued once; later ticks only observe");
    }

    @Test
    void succeedsWhenTheMoverArrives() {
        GoTo task = new GoTo(3, 70, -4);
        task.tick(actuators);
        mover.setState(MoveState.MOVING);
        assertEquals(TaskStatus.RUNNING, task.tick(actuators));
        mover.setState(MoveState.ARRIVED);
        assertEquals(TaskStatus.SUCCESS, task.tick(actuators));
    }

    @Test
    void failsWhenTheMoverFails() {
        GoTo task = new GoTo(3, 70, -4);
        task.tick(actuators);
        mover.setState(MoveState.FAILED);
        assertEquals(TaskStatus.FAILED, task.tick(actuators));
    }

    /**
     * IDLE after the issuing tick means the mover was stopped out from under us — someone else
     * took the legs. The task cannot claim success for a walk it did not finish.
     */
    @Test
    void failsWhenTheMoverIsUnexpectedlyIdle() {
        GoTo task = new GoTo(3, 70, -4);
        task.tick(actuators);
        mover.setState(MoveState.IDLE);
        assertEquals(TaskStatus.FAILED, task.tick(actuators));
    }

    @Test
    void cancelStopsTheMoverAndDoubleCancelIsSafe() {
        GoTo task = new GoTo(1, 2, 3);
        task.tick(actuators);
        task.cancel(actuators);
        assertEquals(1, mover.stopCalls);
        assertEquals(MoveState.IDLE, mover.state());
        task.cancel(actuators); // idempotent: absorbed by Mover.stop's no-move no-op
        assertEquals(2, mover.stopCalls);
    }

    @Test
    void cancelBeforeTheFirstTickIsSafe() {
        GoTo task = new GoTo(1, 2, 3);
        task.cancel(actuators);
        assertEquals(1, mover.stopCalls);
        assertEquals(0, mover.moveToCalls, "cancelling an unstarted task issues no move");
    }

    @Test
    void describeReadsAsGotoWithTheGoalCell() {
        assertEquals("goto (12, -60, 8)", new GoTo(12, -60, 8).describe());
    }
}
