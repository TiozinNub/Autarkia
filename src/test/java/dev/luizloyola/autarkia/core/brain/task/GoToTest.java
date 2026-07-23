package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.act.MoveState;
import dev.luizloyola.autarkia.core.nav.Gait;
import org.junit.jupiter.api.Test;

/**
 * {@link GoTo} against a {@link FakeMover}: the issue-then-RUNNING first-tick semantic (see the
 * GoTo class doc), the state-to-status mapping, and the idempotent cancel contract.
 */
class GoToTest {

    private final FakeContext ctx = new FakeContext();
    private final FakeMover mover = ctx.mover;

    /**
     * Issue, don't read: the fake is pre-set to ARRIVED and moveTo does not overwrite it, yet the
     * first tick still reports RUNNING — state is consulted only from the second tick on.
     */
    @Test
    void firstTickIssuesTheMoveAndReportsRunningWithoutReadingState() {
        mover.setState(MoveState.ARRIVED);
        GoTo task = new GoTo(12, -60, 8);
        assertEquals(TaskStatus.RUNNING, task.tick(ctx), "first tick issues, never reads");
        assertEquals(1, mover.moveToCalls);
        assertEquals(12, mover.lastX);
        assertEquals(-60, mover.lastY);
        assertEquals(8, mover.lastZ);
        assertEquals(TaskStatus.SUCCESS, task.tick(ctx), "second tick reads the state");
    }

    @Test
    void moveToIsIssuedExactlyOnceAcrossManyTicks() {
        GoTo task = new GoTo(0, 64, 0);
        task.tick(ctx);
        mover.setState(MoveState.MOVING);
        for (int i = 0; i < 5; i++) {
            assertEquals(TaskStatus.RUNNING, task.tick(ctx));
        }
        assertEquals(1, mover.moveToCalls, "the order is issued once; later ticks only observe");
    }

    @Test
    void succeedsWhenTheMoverArrives() {
        GoTo task = new GoTo(3, 70, -4);
        task.tick(ctx);
        mover.setState(MoveState.MOVING);
        assertEquals(TaskStatus.RUNNING, task.tick(ctx));
        mover.setState(MoveState.ARRIVED);
        assertEquals(TaskStatus.SUCCESS, task.tick(ctx));
    }

    @Test
    void failsWhenTheMoverFails() {
        GoTo task = new GoTo(3, 70, -4);
        task.tick(ctx);
        mover.setState(MoveState.FAILED);
        assertEquals(TaskStatus.FAILED, task.tick(ctx));
    }

    /**
     * IDLE after the issuing tick means the mover was stopped out from under us — someone else
     * took the legs. The task cannot claim success for a walk it did not finish.
     */
    @Test
    void failsWhenTheMoverIsUnexpectedlyIdle() {
        GoTo task = new GoTo(3, 70, -4);
        task.tick(ctx);
        mover.setState(MoveState.IDLE);
        assertEquals(TaskStatus.FAILED, task.tick(ctx));
    }

    @Test
    void cancelStopsTheMoverAndDoubleCancelIsSafe() {
        GoTo task = new GoTo(1, 2, 3);
        task.tick(ctx);
        task.cancel(ctx);
        assertEquals(1, mover.stopCalls);
        assertEquals(MoveState.IDLE, mover.state());
        task.cancel(ctx); // idempotent: absorbed by Mover.stop's no-move no-op
        assertEquals(2, mover.stopCalls);
    }

    @Test
    void cancelBeforeTheFirstTickIsSafe() {
        GoTo task = new GoTo(1, 2, 3);
        task.cancel(ctx);
        assertEquals(1, mover.stopCalls);
        assertEquals(0, mover.moveToCalls, "cancelling an unstarted task issues no move");
    }

    @Test
    void describeReadsAsGotoWithTheGoalCell() {
        assertEquals("goto (12, -60, 8)", new GoTo(12, -60, 8).describe());
    }

    // --- urgency -----------------------------------------------------------------------------

    @Test
    void theGaitConstructorThreadsThePaceThroughToTheMover() {
        GoTo task = new GoTo(1, 2, 3, Gait.SPRINT);
        task.tick(ctx);
        assertEquals(Gait.SPRINT, mover.lastGait, "the 4-arg ctor's gait reaches the mover");
    }

    @Test
    void thePlainConstructorThreadsWalkThroughToTheMover() {
        GoTo task = new GoTo(1, 2, 3);
        task.tick(ctx);
        assertEquals(Gait.WALK, mover.lastGait, "the 3-arg ctor is an ordinary walk");
    }

    @Test
    void describeAppendsNonWalkGaits() {
        assertEquals("goto (12, -60, 8) (sprint)", new GoTo(12, -60, 8, Gait.SPRINT).describe());
        assertEquals("goto (12, -60, 8) (stroll)", new GoTo(12, -60, 8, Gait.STROLL).describe());
        assertEquals("goto (12, -60, 8)", new GoTo(12, -60, 8, Gait.WALK).describe());
    }
}
