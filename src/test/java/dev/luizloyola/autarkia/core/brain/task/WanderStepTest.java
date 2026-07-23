package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.act.MoveState;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link WanderStep}: the roll stays inside the radius, never picks a zero offset, keeps the
 * pause in {@code [40, 119]}, reads the CURRENT position at decompose time (lazy — not at
 * construction), and is fully determined by the seed. Driven through the real {@link TaskExecutor}
 * so the compound expands exactly as it will in the field; the target is read off the
 * {@link FakeMover}'s recorded {@code moveTo} and the pause is counted as the {@link Idle} run
 * length.
 */
class WanderStepTest {

    private static final int RADIUS = 8;

    private final FakeContext ctx = new FakeContext();
    private final TaskExecutor executor = new TaskExecutor();

    /** Expands the step one tick (issuing the GoTo) and returns the mover's recorded target. */
    private Pos runToTarget(WanderStep step) {
        executor.run(step, ctx);
        executor.tick(ctx); // expand WanderStep -> Roam.decompose -> GoTo issues moveTo
        return new Pos(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ);
    }

    /** Drives the already-issued GoTo to SUCCESS, then counts the Idle's RUNNING ticks (the pause). */
    private int drainPause() {
        ctx.mover.setState(MoveState.ARRIVED);
        executor.tick(ctx); // GoTo observes ARRIVED -> SUCCESS -> sequence advances to Idle
        int pause = 0;
        while (executor.isBusy()) {
            executor.tick(ctx);
            if (executor.isBusy()) {
                pause++; // this tick was a RUNNING Idle tick; the final SUCCESS tick goes idle
            }
        }
        return pause;
    }

    @Test
    void targetStaysWithinRadiusAndNeverZeroOffsetAndFlatY() {
        RandomGenerator random = new Random(1234);
        for (int i = 0; i < 200; i++) { // many rolls off one stream — exercises the whole box
            FakeContext local = new FakeContext();
            local.percepts.position = new Pos(0, 64, 0);
            TaskExecutor exec = new TaskExecutor();
            exec.run(new WanderStep(random, RADIUS), local);
            exec.tick(local);
            int dx = local.mover.lastX;
            int dz = local.mover.lastZ;
            assertTrue(Math.abs(dx) <= RADIUS && Math.abs(dz) <= RADIUS,
                    "offset (" + dx + ", " + dz + ") must be within +/-" + RADIUS);
            assertFalse(dx == 0 && dz == 0, "a wander step always goes somewhere");
            assertEquals(64, local.mover.lastY, "y is unchanged — she walks the ground");
        }
    }

    @Test
    void pauseIsInFortyToOneNineteen() {
        RandomGenerator random = new Random(99);
        for (int i = 0; i < 50; i++) {
            FakeContext local = new FakeContext();
            TaskExecutor exec = new TaskExecutor();
            exec.run(new WanderStep(random, RADIUS), local);
            exec.tick(local); // GoTo issues
            local.mover.setState(MoveState.ARRIVED);
            exec.tick(local); // GoTo SUCCESS -> Idle pending
            int pause = 0;
            while (exec.isBusy()) {
                exec.tick(local);
                if (exec.isBusy()) {
                    pause++;
                }
            }
            assertTrue(pause >= 40 && pause <= 119, "pause was " + pause);
        }
    }

    @Test
    void targetIsOffsetFromTheCurrentPositionNotTheConstructionOne() {
        WanderStep step = new WanderStep(new Random(7), RADIUS);
        ctx.percepts.position = new Pos(0, 64, 0);
        executor.run(step, ctx); // installed while at spawn...
        ctx.percepts.position = new Pos(100, 70, 200); // ...but she walked before it expanded
        executor.tick(ctx);
        int dx = ctx.mover.lastX - 100;
        int dz = ctx.mover.lastZ - 200;
        assertEquals(70, ctx.mover.lastY, "offset from the CURRENT cell");
        assertTrue(Math.abs(dx) <= RADIUS && Math.abs(dz) <= RADIUS,
                "offset from (100,70,200), not (0,64,0)");
    }

    /** Same seed, same stream: identical target and identical pause — reproducible per Person. */
    @Test
    void deterministicPerSeed() {
        Pos targetA;
        int pauseA;
        {
            targetA = runToTarget(new WanderStep(new Random(42), RADIUS));
            pauseA = drainPause();
        }
        FakeContext ctx2 = new FakeContext();
        TaskExecutor exec2 = new TaskExecutor();
        exec2.run(new WanderStep(new Random(42), RADIUS), ctx2);
        exec2.tick(ctx2);
        Pos targetB = new Pos(ctx2.mover.lastX, ctx2.mover.lastY, ctx2.mover.lastZ);
        ctx2.mover.setState(MoveState.ARRIVED);
        exec2.tick(ctx2);
        int pauseB = 0;
        while (exec2.isBusy()) {
            exec2.tick(ctx2);
            if (exec2.isBusy()) {
                pauseB++;
            }
        }
        assertEquals(targetA, targetB, "same seed -> same target");
        assertEquals(pauseA, pauseB, "same seed -> same pause");
    }

    @Test
    void describeReadsAsTheDesignNamesIt() {
        assertEquals("wander step", new WanderStep(new Random(0), RADIUS).describe());
    }
}
