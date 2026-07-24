package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.Arbiter;
import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.ToleranceCurve;
import dev.luizloyola.autarkia.core.brain.act.ConsumeState;
import dev.luizloyola.autarkia.core.brain.act.MoveState;
import dev.luizloyola.autarkia.core.brain.instinct.EatInstinct;
import dev.luizloyola.autarkia.core.brain.instinct.FleeInstinct;
import dev.luizloyola.autarkia.core.brain.instinct.Instinct;
import dev.luizloyola.autarkia.core.brain.instinct.WanderInstinct;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.brain.sense.Threat;
import dev.luizloyola.autarkia.core.inv.ItemStack;
import dev.luizloyola.autarkia.core.person.FoodValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link Arbiter}'s arbitration semantics with scripted instincts (settable pressure, a
 * fresh root per grant): idle-grant of the top bidder, {@link Arbiter#STICKINESS} holding the
 * incumbent against a marginal challenger but not a decisive one, the {@link Arbiter#PREEMPT} floor
 * (mild challengers wait for the task boundary, strong ones cancel mid-task), fresh-root re-grant
 * after SUCCESS, {@link Instinct#failCooldown()} after a FAILED root, the manual-task and cost
 * tolerance paths, and two scenes from the real instincts: a threat preempting mid-chew, and Flee
 * chaining re-aimed legs.
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

    /**
     * An instinct with settable pressure and a root FACTORY — every grant records a fresh root.
     * {@link #failCooldownOverride} pins an emergency drive's shortened cooldown per test (see
     * {@link FleeInstinct#FAIL_COOLDOWN}).
     */
    private static final class FakeInstinct implements Instinct {
        final String name;
        double pressure;
        private final Supplier<Task> factory;
        final List<Task> grantedRoots = new ArrayList<>();
        int failCooldownOverride = Instinct.DEFAULT_FAIL_COOLDOWN;

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
        public int failCooldown() {
            return failCooldownOverride;
        }

        @Override
        public String describe() {
            return name;
        }
    }

    /**
     * A real {@link FleeInstinct} with grant recording spliced on: instance freshness
     * ({@link #grantedRoots}) cannot be observed from outside the arbiter/executor otherwise.
     */
    private static final class SpyingFlee implements Instinct {
        private final FleeInstinct real;
        final List<Task> grantedRoots = new ArrayList<>();

        SpyingFlee(RandomGenerator random) {
            this.real = new FleeInstinct(random);
        }

        @Override
        public double pressure(BrainContext ctx) {
            return real.pressure(ctx);
        }

        @Override
        public Task root(BrainContext ctx) {
            Task t = real.root(ctx);
            grantedRoots.add(t);
            return t;
        }

        @Override
        public int failCooldown() {
            return real.failCooldown();
        }

        @Override
        public String describe() {
            return real.describe();
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

    @Test
    void zeroPressureIsNotABid() {
        FakeInstinct flee = new FakeInstinct("flee", 0.0, forever("scatter"));
        FakeInstinct wander = new FakeInstinct("wander", 0.0, forever("roam"));
        Arbiter arbiter = new Arbiter(List.of(flee, wander));
        for (int i = 0; i < 20; i++) {
            arbiter.tick(ctx);
        }
        assertTrue(flee.grantedRoots.isEmpty(), "an all-zero field grants nobody by list order");
        assertTrue(wander.grantedRoots.isEmpty());
        assertFalse(arbiter.executor().isBusy(), "wanting nothing means idling, not busywork");
    }

    @Test
    void aCoolingWanderLeavesHerStandingNotScatterFleeing() {
        // Live-caught: wander fails, cools down, and zero-pressure flee won the all-zero tie by
        // list order — a sprint at nothing, clean out of the loaded world.
        FakeInstinct flee = new FakeInstinct("flee", 0.0, forever("scatter"));
        FakeInstinct wander = new FakeInstinct("wander", 0.15,
                () -> new Step("roam", 1, TaskStatus.FAILED));
        Arbiter arbiter = new Arbiter(List.of(flee, wander));
        for (int i = 0; i < 40; i++) {
            arbiter.tick(ctx); // grant, fail, and then the whole cooldown stretch
        }
        assertTrue(flee.grantedRoots.isEmpty(),
                "flee at 0.00 never inherits the wheel — she stands out the cooldown");
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
    void failedRootPutsTheInstinctOnCooldownForExactlyItsOwnFailCooldownTicks() {
        FakeInstinct a = new FakeInstinct("a", 1.0, failsImmediately("aRoot"));
        FakeInstinct b = new FakeInstinct("b", 0.5, forever("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(a, b));

        arbiter.tick(ctx); // t1: A granted, fails -> cooldown 100 (the DEFAULT), active cleared
        assertEquals(1, a.grantedRoots.size());

        // t2..t101 (exactly DEFAULT_FAIL_COOLDOWN ticks): A sits out; B takes over and keeps running.
        for (int t = 2; t <= 1 + Instinct.DEFAULT_FAIL_COOLDOWN; t++) {
            arbiter.tick(ctx);
            assertEquals(1, a.grantedRoots.size(), "A still cooling at tick " + t);
        }
        assertEquals(1, b.grantedRoots.size(), "the runner-up took over while A cooled");
        Step running = step(b.grantedRoots.get(0));
        assertEquals(0, running.cancels, "B ran undisturbed through A's cooldown");

        arbiter.tick(ctx); // t102: A eligible again -> its 1.0 preempts B
        assertEquals(2, a.grantedRoots.size(), "A re-bids the tick AFTER exactly DEFAULT_FAIL_COOLDOWN ticks");
        assertEquals(1, running.cancels, "and preempts the runner-up");
    }

    /**
     * The emergency-drive shape (e.g. {@link FleeInstinct#FAIL_COOLDOWN}): the arbiter reads
     * {@code active.failCooldown()}, never a fixed constant of its own.
     */
    @Test
    void anInstinctOverridingFailCooldownSitsOutOnlyItsOwnShorterCooldown() {
        FakeInstinct a = new FakeInstinct("a", 1.0, failsImmediately("aRoot"));
        a.failCooldownOverride = 10;
        FakeInstinct b = new FakeInstinct("b", 0.5, forever("bRoot"));
        Arbiter arbiter = new Arbiter(List.of(a, b));

        arbiter.tick(ctx); // t1: A granted, fails -> cooldown 10 (its own override), active cleared
        assertEquals(1, a.grantedRoots.size());

        // t2..t11 (exactly its own 10-tick cooldown): A sits out; B takes over.
        for (int t = 2; t <= 1 + a.failCooldownOverride; t++) {
            arbiter.tick(ctx);
            assertEquals(1, a.grantedRoots.size(), "A still cooling at tick " + t);
        }
        assertEquals(1, b.grantedRoots.size(), "the runner-up took over while A cooled");

        arbiter.tick(ctx); // t12: A eligible again -> back bidding after exactly its own failCooldown
        assertEquals(2, a.grantedRoots.size(),
                "A re-bids after exactly its own failCooldown (10), far short of the 100 default");
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

    // --- Flee: real-instinct scenes -----------------------------------------------------------

    /**
     * The scene the emergency drive exists for: mid-bite, a threat closes in hard enough to blow
     * past both {@link Arbiter#STICKINESS} and {@link Arbiter#PREEMPT}, so Flee cuts the chew off
     * (the incumbent {@code ConsumeItem}'s cancel aborts the consumer) and takes the legs. Once the
     * threat clears, the running leg still finishes (Eat's pressure here sits under PREEMPT), and
     * the runner-up resumes only at the next boundary.
     */
    @Test
    void aCloseThreatPreemptsAMidChewEatThenClearsAndTheRunnerUpResumesAtTheNextBoundary() {
        Arbiter arbiter = new Arbiter(List.of(
                new EatInstinct(), new WanderInstinct(new Random(13)), new FleeInstinct(new Random(17))));

        // Peckish (below PREEMPT) with bread in hand -> Eat outbids idle Wander and starts a bite.
        ctx.percepts.food("minecraft:bread", new FoodValue(5, 6.0F, false));
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:bread", 10, 64));
        ctx.percepts.needs.setFoodLevel(12); // hunger 1 - 12/20 = 0.4 -- PECKISH, under PREEMPT (0.6)

        arbiter.tick(ctx); // t1: Eat (0.4) beats Wander (0.15) and no-threat Flee (0.0); begins a bite
        assertEquals(1, ctx.consumer.beginCalls);
        assertTrue(arbiter.describe().contains("eat") && arbiter.describe().contains("(active)"), arbiter.describe());
        ctx.consumer.setState(ConsumeState.CONSUMING); // mid-chew, scripted like the body would report it

        // A threat close enough to push Flee to 0.9 -- well past PREEMPT and past Eat's 0.4.
        ctx.percepts.threats = List.of(new Threat(new Pos(5, 64, 0), 5.2, false)); // (16-5.2)/12 = 0.9

        arbiter.tick(ctx); // t2: Flee preempts mid-chew
        assertEquals(1, ctx.consumer.abortCalls, "the chew was cancelled -- ConsumeItem.cancel aborts it");
        assertTrue(arbiter.describe().contains("flee") && arbiter.describe().contains("(active)"), arbiter.describe());
        assertEquals(1, ctx.mover.moveToCalls, "FleeStep's GoTo takes the legs");
        assertEquals(dev.luizloyola.autarkia.core.nav.Gait.SPRINT, ctx.mover.lastGait,
                "the flee leg sprints");

        ctx.percepts.threats = List.of();
        ctx.mover.setState(MoveState.ARRIVED);
        arbiter.tick(ctx); // t3: GoTo SUCCEEDS -> the leg (FleeStep, no Idle) ends -> boundary
        assertFalse(arbiter.executor().isBusy(),
                "the leg finished this tick, but nothing is re-granted until the NEXT boundary");

        arbiter.tick(ctx); // t4: idle -> Eat (0.4) again tops Wander (0.15), with Flee now at 0.0
        assertEquals(2, ctx.consumer.beginCalls, "eat resumes at the next boundary");
        assertTrue(arbiter.describe().contains("eat") && arbiter.describe().contains("(active)"), arbiter.describe());
    }

    /**
     * With nothing to out-bid it, {@link FleeInstinct} wins every re-arbitration, and each leg's
     * re-grant builds a FRESH {@code FleeStep} (never a cached tree) aimed at the threat's position
     * NOW — so a threat crossing sides between legs flips the next leg's target.
     */
    @Test
    void fleeChainsFreshReaimedLegsAsTheThreatMovesWhilePressureStaysHigh() {
        SpyingFlee flee = new SpyingFlee(new Random(11));
        Arbiter arbiter = new Arbiter(List.of(flee));
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.threats = List.of(new Threat(new Pos(5, 64, 0), 5.0, false)); // east -> she runs west

        arbiter.tick(ctx); // t1: grant leg #1; its GoTo issues, aimed west
        assertEquals(1, flee.grantedRoots.size());
        assertTrue(ctx.mover.lastX < 0, "leg 1 runs west, away from the eastern threat");

        ctx.mover.setState(MoveState.ARRIVED); 
        ctx.percepts.threats = List.of(new Threat(new Pos(-5, 64, 0), 5.0, false)); // now west of her
        arbiter.tick(ctx); // t2: GoTo #1 SUCCEEDS -> boundary; re-grant is still next tick, not this one
        assertEquals(1, flee.grantedRoots.size(), "re-grant happens on the NEXT tick, not the boundary tick itself");

        arbiter.tick(ctx); // t3: idle -> a FRESH FleeStep, re-aimed at the CURRENT (now western) threat
        assertEquals(2, flee.grantedRoots.size());
        assertNotSame(flee.grantedRoots.get(0), flee.grantedRoots.get(1), "a fresh root each grant");
        assertTrue(ctx.mover.lastX > 0, "leg 2 re-aims east, away from the now-western threat");
    }
}
