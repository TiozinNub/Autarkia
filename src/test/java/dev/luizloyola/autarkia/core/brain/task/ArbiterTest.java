package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.Arbiter;
import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.ToleranceCurve;
import dev.luizloyola.autarkia.core.brain.instinct.Instinct;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link Arbiter}'s arbitration semantics with scripted instincts (settable pressure, a
 * fresh root per grant): idle-grant of the top bidder, {@link Arbiter#STICKINESS} holding the
 * incumbent against a marginal challenger but not a decisive one, the {@link Arbiter#PREEMPT} floor
 * (mild challengers wait for the task boundary, strong ones cancel mid-task), fresh-root re-grant
 * after SUCCESS, {@link Arbiter#FAIL_COOLDOWN} after a FAILED root, and the manual-task and cost
 * tolerance paths.
 */
class ArbiterTest {

    private final FakeContext ctx = new FakeContext();

    // --- scripted pieces -------------------------------------------------------------------------

    /** A primitive that reports RUNNING for {@code runFor} ticks then {@code end}; counts ticks and cancels. */
    private static final class Step implements PrimitiveTask {
        final String name;
        private int runFor;
        private final TaskStatus end;
        int ticks;
        int cancels;

        Step(String name, int runFor, TaskStatus end) {
            this.name = name;
            this.runFor = runFor;
            this.end = end;
        }

        @Override
        public TaskStatus tick(BrainContext ctx) {
            ticks++;
            return runFor-- > 0 ? TaskStatus.RUNNING : end;
        }

        @Override
        public void cancel(BrainContext ctx) {
            cancels++;
        }

        @Override
        public String describe() {
            return name;
        }
    }

    /** An instinct with a settable pressure and a root FACTORY — every grant records a fresh root. */
    private static final class FakeInstinct implements Instinct {
        final String name;
        double pressure;
        private final Supplier<Task> factory;
        final List<Task> grantedRoots = new ArrayList<>();

        FakeInstinct(String name, double pressure, Supplier<Task> factory) {
            this.name = name;
            this.pressure = pressure;
            this.factory = factory;
        }

        @Override
        public double pressure(BrainContext ctx) {
            return pressure;
        }

        @Override
        public Task root(BrainContext ctx) {
            Task t = factory.get();
            grantedRoots.add(t);
            return t;
        }

        @Override
        public String describe() {
            return name;
        }
    }

    private static Supplier<Task> forever(String name) {
        return () -> new Step(name, Integer.MAX_VALUE, TaskStatus.SUCCESS);
    }

    private static Supplier<Task> failsImmediately(String name) {
        return () -> new Step(name, 0, TaskStatus.FAILED);
    }

    private static Supplier<Task> succeedsImmediately(String name) {
        return () -> new Step(name, 0, TaskStatus.SUCCESS);
    }

    private static Step step(Task root) {
        return (Step) root;
    }

    // --- idle grant ------------------------------------------------------------------------------

    @Test
    void idleGrantsTheTopBidder() {
        FakeInstinct a = new FakeInstinct("a", 0.5, forever("aRoot"));
        FakeInstinct b = new FakeInstinct("b", 0.3, forever("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(a, b));
        arbiter.tick(ctx);
        assertEquals(1, a.grantedRoots.size(), "the higher bidder is granted");
        assertEquals(1, step(a.grantedRoots.get(0)).ticks, "and immediately driven");
        assertEquals(0, b.grantedRoots.size(), "the loser is not");
    }

    @Test
    void tiesGoToTheEarlierInstinct() {
        FakeInstinct a = new FakeInstinct("a", 0.4, forever("aRoot"));
        FakeInstinct b = new FakeInstinct("b", 0.4, forever("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(a, b));
        arbiter.tick(ctx);
        assertEquals(1, a.grantedRoots.size(), "equal bids -> the earlier list entry wins");
        assertEquals(0, b.grantedRoots.size());
    }

    // --- stickiness ------------------------------------------------------------------------------

    @Test
    void stickinessHoldsAgainstAMarginalChallengerAndYieldsToADecisiveOne() {
        FakeInstinct a = new FakeInstinct("a", 0.5, forever("aRoot"));
        FakeInstinct b = new FakeInstinct("b", 0.0, forever("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(a, b));
        arbiter.tick(ctx); // A granted (0.5 > 0)
        assertEquals(1, a.grantedRoots.size());

        b.pressure = 0.55; // above A's raw 0.5, below A's effective 0.6
        arbiter.tick(ctx);
        assertEquals(0, b.grantedRoots.size(), "0.55 < 0.5 + STICKINESS 0.1 -> the incumbent holds");
        assertEquals(0, step(a.grantedRoots.get(0)).cancels);

        b.pressure = 0.61; // now above A's effective 0.6 and at/over PREEMPT 0.6
        arbiter.tick(ctx);
        assertEquals(1, b.grantedRoots.size(), "0.61 beats the sticky incumbent -> yields");
        assertEquals(1, step(a.grantedRoots.get(0)).cancels, "the incumbent's task was cancelled");
    }

    // --- preempt floor ---------------------------------------------------------------------------

    @Test
    void subPreemptChallengerWaitsWhileBusyThenWinsAtTheBoundary() {
        // A gets granted while its pressure is high, then drops below the challenger — but the
        // challenger is under PREEMPT, so it cannot cut in until A's task finishes on its own.
        FakeInstinct a = new FakeInstinct("a", 0.9, () -> new Step("aRoot", 2, TaskStatus.SUCCESS));
        FakeInstinct b = new FakeInstinct("b", 0.0, forever("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(a, b));

        arbiter.tick(ctx); // t1: grant A (runFor 2 -> RUNNING); A ticks once
        a.pressure = 0.2;
        b.pressure = 0.45; // higher than A's 0.2, but < PREEMPT 0.6

        arbiter.tick(ctx); // t2: A's 2nd (last) RUNNING; B waits — sub-PREEMPT can't cut in
        assertEquals(0, b.grantedRoots.size(), "a sub-PREEMPT challenger never cuts in mid-task");

        arbiter.tick(ctx); // t3: A returns SUCCESS -> boundary; active clears (B still not granted)
        assertEquals(0, b.grantedRoots.size(), "not granted on the boundary tick itself");
        assertEquals(0, step(a.grantedRoots.get(0)).cancels, "A finished on its own terms — never cancelled");

        arbiter.tick(ctx); // t4: idle -> B (0.45) is now the top bidder -> granted
        assertEquals(1, b.grantedRoots.size(), "the challenger wins at the next boundary");
        assertEquals(1, step(b.grantedRoots.get(0)).ticks);
    }

    @Test
    void preemptChallengerCancelsTheRunningTaskMidFlight() {
        // A holds the legs (a real GoTo); a challenger at/over PREEMPT cancels it immediately.
        FakeInstinct a = new FakeInstinct("a", 0.9, () -> new GoTo(1, 2, 3));
        FakeInstinct b = new FakeInstinct("b", 0.0, forever("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(a, b));

        arbiter.tick(ctx); // t1: grant A; the GoTo issues its move
        assertEquals(1, ctx.mover.moveToCalls);

        a.pressure = 0.3;
        b.pressure = 0.7; // > A's effective 0.4 and >= PREEMPT 0.6
        arbiter.tick(ctx); // t2: B preempts -> GoTo cancelled (mover stopped) before B acts
        assertEquals(1, ctx.mover.stopCalls, "the preempted GoTo released the legs");
        assertEquals(1, b.grantedRoots.size());
        assertEquals(List.of("moveTo(1, 2, 3)", "stop"), ctx.mover.events, "released, then the newcomer takes over");
    }

    // --- boundary re-grant -----------------------------------------------------------------------

    @Test
    void successReGrantsTheSameInstinctWithAFreshRoot() {
        FakeInstinct a = new FakeInstinct("a", 0.5, succeedsImmediately("aRoot"));
        FakeInstinct b = new FakeInstinct("b", 0.0, forever("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(a, b));

        arbiter.tick(ctx); // t1: grant A; A succeeds immediately -> boundary
        arbiter.tick(ctx); // t2: idle -> A is still top -> re-granted with a new root
        assertEquals(2, a.grantedRoots.size(), "the same instinct is re-granted (the behavior loop)");
        assertNotSame(a.grantedRoots.get(0), a.grantedRoots.get(1), "each grant builds a fresh root");
        assertEquals(0, b.grantedRoots.size(), "the runner-up never ran");
    }

    // --- fail cooldown ---------------------------------------------------------------------------

    @Test
    void failedRootPutsTheInstinctOnCooldownForExactlyFailCooldownTicks() {
        FakeInstinct a = new FakeInstinct("a", 1.0, failsImmediately("aRoot"));
        FakeInstinct b = new FakeInstinct("b", 0.5, forever("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(a, b));

        arbiter.tick(ctx); // t1: A granted, fails -> cooldown 100, active cleared
        assertEquals(1, a.grantedRoots.size());

        // t2..t101 (exactly FAIL_COOLDOWN ticks): A sits out; B takes over and keeps running.
        for (int t = 2; t <= 1 + Arbiter.FAIL_COOLDOWN; t++) {
            arbiter.tick(ctx);
            assertEquals(1, a.grantedRoots.size(), "A still cooling at tick " + t);
        }
        assertEquals(1, b.grantedRoots.size(), "the runner-up took over while A cooled");
        Step running = step(b.grantedRoots.get(0));
        assertEquals(0, running.cancels, "B ran undisturbed through A's cooldown");

        arbiter.tick(ctx); // t102: A eligible again -> its 1.0 preempts B
        assertEquals(2, a.grantedRoots.size(), "A re-bids the tick AFTER exactly FAIL_COOLDOWN ticks");
        assertEquals(1, running.cancels, "and preempts the runner-up");
    }

    // --- manual task under an all-cooling / empty arbiter ----------------------------------------

    @Test
    void aManualTaskRunsUnderAnArbiterWithNoInstincts() {
        Arbiter arbiter = new Arbiter(List.of());
        Step manual = new Step("manual", Integer.MAX_VALUE, TaskStatus.SUCCESS);
        arbiter.executor().run(manual, ctx); // the driver's manual mode installs directly
        arbiter.tick(ctx);
        assertEquals(1, manual.ticks, "the executor still ticks even with nothing to arbitrate");
        assertTrue(Double.isInfinite(arbiter.costTolerance()), "nothing active -> unbounded tolerance");
    }

    // --- cost tolerance ---------------------------------------------------------------------------

    @Test
    void costToleranceTracksTheActiveInstinctsPressure() {
        FakeInstinct eat = new FakeInstinct("eat", 0.7, forever("sat")); // HUNGRY band
        FakeInstinct wander = new FakeInstinct("wander", 0.15, forever("roam"));
        Arbiter arbiter = new Arbiter(List.of(eat, wander));
        assertTrue(Double.isInfinite(arbiter.costTolerance()), "before any tick, nothing active");

        arbiter.tick(ctx); // eat (0.7) wins
        assertEquals(ToleranceCurve.tolerance(0.7), arbiter.costTolerance());
        assertEquals(60.0, arbiter.costTolerance(), "hunger 0.7 -> the HUNGRY tolerance");
    }

    // --- describe smoke --------------------------------------------------------------------------

    @Test
    void describeListsEachInstinctThenTheExecutor() {
        FakeInstinct eat = new FakeInstinct("eat", 0.7, forever("sat"));
        FakeInstinct wander = new FakeInstinct("wander", 0.15, forever("roam"));
        Arbiter arbiter = new Arbiter(List.of(eat, wander));
        arbiter.tick(ctx); // eat granted; its Step "sat" runs
        assertEquals("eat 0.70 (active)\nwander 0.15\nrunning: sat", arbiter.describe());
    }

    @Test
    void describeMarksACoolingInstinct() {
        FakeInstinct a = new FakeInstinct("a", 1.0, failsImmediately("aRoot"));
        FakeInstinct b = new FakeInstinct("b", 0.5, forever("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(a, b));
        arbiter.tick(ctx); // A fails -> cooldown 100; B not yet granted (idle at end of this tick)
        assertTrue(arbiter.describe().startsWith("a 1.00 (cooldown 100t)\nb 0.50"),
                "the cooling instinct is tagged with its remaining ticks:\n" + arbiter.describe());
    }
}
