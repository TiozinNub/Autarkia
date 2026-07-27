package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.act.MoveState;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.brain.sense.Being;
import dev.luizloyola.autarkia.core.nav.Gait;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link FleeStep}: the escape vector runs away from the proximity-weighted threat centroid,
 * closer threats dominating via {@code 1/distance²}. Driven through the real
 * {@link TaskExecutor}.
 */
class FleeStepTest {

    private final FakeContext ctx = new FakeContext();
    private final TaskExecutor executor = new TaskExecutor();

    private static Being threatAt(int x, int z, double distance, boolean approaching) {
        return FakePercepts.monsterAt(new Pos(x, 64, z), distance, approaching);
    }

    @Test
    void runsAwayFromASingleThreatDueEast() {
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.beings = List.of(threatAt(10, 0, 10.0, false));
        executor.run(new FleeStep(new Random(1)), ctx);
        executor.tick(ctx); // expand FleeStep -> Escape.decompose -> GoTo issues moveTo

        assertTrue(ctx.mover.lastX < 0, "they run west, away from the eastern threat");
        assertEquals(64, ctx.mover.lastY, "y is unchanged");
        double length = Math.hypot(ctx.mover.lastX, ctx.mover.lastZ);
        assertTrue(Math.abs(length - FleeStep.FLEE_LEG) <= FleeStep.JITTER * Math.sqrt(2) + 1,
                "leg length ~= FLEE_LEG (" + FleeStep.FLEE_LEG + ") +/- jitter, was " + length);
    }

    @Test
    void escapeVectorIsDominatedByTheCloserThreat() {
        ctx.percepts.position = new Pos(0, 64, 0);
        // Close threat due east; far threat due north. 1/d^2 weighting should make the close
        // eastern threat dominate the centroid, so they run west and (slightly) south.
        ctx.percepts.beings = List.of(
                threatAt(4, 0, 4.0, false),
                threatAt(0, 12, 12.0, false));
        executor.run(new FleeStep(new Random(2)), ctx);
        executor.tick(ctx);

        assertTrue(ctx.mover.lastX < 0, "dominated by the close eastern threat -> runs west");
        assertTrue(ctx.mover.lastZ < 0, "the far northern threat still pulls the aim slightly south");
    }

    @Test
    void surroundedStillProducesAFullLengthLeg() {
        ctx.percepts.position = new Pos(0, 64, 0);
        // Two threats symmetric about them -> the weighted centroid lands on that position.
        ctx.percepts.beings = List.of(
                threatAt(8, 0, 8.0, false),
                threatAt(-8, 0, 8.0, false));
        executor.run(new FleeStep(new Random(3)), ctx);
        executor.tick(ctx);

        double length = Math.hypot(ctx.mover.lastX, ctx.mover.lastZ);
        assertTrue(length > FleeStep.FLEE_LEG - FleeStep.JITTER * Math.sqrt(2) - 1,
                "surrounded still produces a full-length leg (any direction), was " + length);
        assertEquals(64, ctx.mover.lastY);
    }

    @Test
    void decomposeEmitsExactlyOneUrgentGoToAndEndsWithNoIdlePause() {
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.beings = List.of(threatAt(10, 0, 10.0, false));
        executor.run(new FleeStep(new Random(4)), ctx);
        executor.tick(ctx); // issues the one GoTo

        assertEquals(1, ctx.mover.moveToCalls, "exactly one GoTo issued");
        assertEquals(Gait.SPRINT, ctx.mover.lastGait, "the leg sprints where it can — the flee gait");

        ctx.mover.setState(MoveState.ARRIVED);
        executor.tick(ctx); // GoTo SUCCEEDS -> the leg (no Idle) ends immediately, same tick
        assertFalse(executor.isBusy(), "SUCCESS just ends the leg -- unlike WanderStep, no Idle pause");
        assertEquals(1, ctx.mover.moveToCalls, "still exactly one GoTo -- nothing chained within one step");
    }

    @Test
    void decomposeReadsTheCurrentPositionAndThreatsNotTheGrantTimeOnes() {
        FleeStep step = new FleeStep(new Random(5));
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.beings = List.of(threatAt(10, 0, 10.0, false)); // east, at grant time
        executor.run(step, ctx); // installed while at spawn...

        ctx.percepts.position = new Pos(100, 70, 200); // ...but they walked before it expanded
        ctx.percepts.beings = List.of(threatAt(100, 210, 10.0, false)); // and the threat moved too (now north)

        executor.tick(ctx); // lazy expansion reads the CURRENT percepts
        assertEquals(70, ctx.mover.lastY, "offset from the CURRENT cell");
        assertTrue(ctx.mover.lastZ < 200, "runs away from the CURRENT threat position (south), not the stale one");
    }

    @Test
    void describeReadsAsFleeStep() {
        assertEquals("flee step", new FleeStep(new Random(0)).describe());
    }
}
