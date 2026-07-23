package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.act.MoveState;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.nav.Gait;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link WanderStep} — walk chance ({@link WanderStep#WALK_CHANCE}), {@link Gait#STROLL}
 * inside the radius with a never-zero offset, the pause range, the lazy position read at decompose
 * time, determinism — through the real {@link TaskExecutor}: targets are read off the
 * {@link FakeMover}, pauses counted as busy {@link Idle} ticks.
 *
 * <p>The draw order (walk-roll, then pause, then target) is class contract here — reordering the
 * draws changes which seeds walk.
 */
class WanderStepTest {

    private static final int RADIUS = 8;

    private record Beat(boolean walked, Pos target, Gait gait, int pause) {}

    /**
     * Runs one full WanderStep off the shared stream in a fresh context/executor; the counted busy
     * Idle ticks are the uniform pause measure for both beat shapes.
     */
    private static Beat runBeat(RandomGenerator random, Pos start) {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = start;
        TaskExecutor executor = new TaskExecutor();
        executor.run(new WanderStep(random, RADIUS), ctx);
        executor.tick(ctx); // expand; ticks the first primitive (GoTo issue, or Idle's first tick)
        boolean walked = ctx.mover.moveToCalls > 0;
        Pos target = new Pos(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ);
        int pause = walked ? 0 : 1; // an idle-only beat's expansion tick already ran Idle once
        if (walked) {
            ctx.mover.setState(MoveState.ARRIVED);
            executor.tick(ctx); // GoTo observes ARRIVED -> SUCCESS; Idle is pending
        }
        while (executor.isBusy()) {
            executor.tick(ctx);
            if (executor.isBusy()) {
                pause++; // busy-after ticks are RUNNING Idle ticks; the SUCCESS tick goes idle
            }
        }
        return new Beat(walked, target, ctx.mover.lastGait, pause);
    }

    @Test
    void mostBeatsIdleAndWalkingBeatsMatchTheTunedChance() {
        RandomGenerator random = new Random(1234);
        int walks = 0;
        for (int i = 0; i < 200; i++) {
            if (runBeat(random, new Pos(0, 64, 0)).walked()) {
                walks++;
            }
        }
        // Deterministic per seed, so this cannot flake; the loose band states the INTENT (a
        // WALK_CHANCE retune outside ~[0.22, 0.38] should trip it and force a conscious look).
        assertTrue(walks >= 45 && walks <= 75,
                "expected roughly WALK_CHANCE=" + WanderStep.WALK_CHANCE + " of 200, got " + walks);
        assertTrue(walks > 0, "walking beats must occur");
    }

    @Test
    void walkingBeatsStrollWithinTheRadiusNeverZeroOffsetFlatY() {
        RandomGenerator random = new Random(1234);
        int seen = 0;
        for (int i = 0; i < 200 && seen < 30; i++) {
            Beat beat = runBeat(random, new Pos(0, 64, 0));
            if (!beat.walked()) {
                continue;
            }
            seen++;
            int dx = beat.target().x();
            int dz = beat.target().z();
            assertTrue(Math.abs(dx) <= RADIUS && Math.abs(dz) <= RADIUS,
                    "offset (" + dx + ", " + dz + ") must be within +/-" + RADIUS);
            assertFalse(dx == 0 && dz == 0, "a walking beat always goes somewhere");
            assertEquals(64, beat.target().y(), "y is unchanged — she walks the ground");
            assertEquals(Gait.STROLL, beat.gait(), "wandering ambles; it does not march");
        }
        assertTrue(seen >= 30, "stream must produce enough walking beats to pin");
    }

    @Test
    void pausesRunTheTunedRangeForBothShapes() {
        RandomGenerator random = new Random(99);
        boolean sawIdleOnly = false;
        boolean sawWalking = false;
        for (int i = 0; i < 40; i++) {
            Beat beat = runBeat(random, new Pos(0, 64, 0));
            sawIdleOnly |= !beat.walked();
            sawWalking |= beat.walked();
            assertTrue(beat.pause() >= WanderStep.IDLE_MIN
                            && beat.pause() < WanderStep.IDLE_MIN + WanderStep.IDLE_RANGE,
                    "pause was " + beat.pause());
        }
        assertTrue(sawIdleOnly && sawWalking, "both beat shapes must occur in 40 draws");
    }

    @Test
    void targetIsOffsetFromTheCurrentPositionNotTheConstructionOne() {
        RandomGenerator random = new Random(7);
        for (int i = 0; i < 200; i++) {
            Beat beat = runBeat(random, new Pos(100, 70, 200));
            if (!beat.walked()) {
                continue;
            }
            assertEquals(70, beat.target().y(), "offset from the CURRENT cell");
            assertTrue(Math.abs(beat.target().x() - 100) <= RADIUS
                            && Math.abs(beat.target().z() - 200) <= RADIUS,
                    "offset from (100,70,200)");
            return;
        }
        throw new AssertionError("stream produced no walking beat to pin");
    }

    /** Same seed, same stream: identical shapes, targets, and pauses — reproducible per Person. */
    @Test
    void deterministicPerSeed() {
        RandomGenerator a = new Random(42);
        RandomGenerator b = new Random(42);
        for (int i = 0; i < 10; i++) {
            Beat beatA = runBeat(a, new Pos(0, 64, 0));
            Beat beatB = runBeat(b, new Pos(0, 64, 0));
            assertEquals(beatA, beatB, "same seed -> same beat #" + i);
        }
    }

    @Test
    void describeReadsAsTheDesignNamesIt() {
        assertEquals("wander step", new WanderStep(new Random(0), RADIUS).describe());
    }
}
