package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.Arbiter;
import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.WorkToleranceCurve;
import dev.luizloyola.autarkia.core.brain.board.WorkItem;
import dev.luizloyola.autarkia.core.brain.board.WorkSource;
import dev.luizloyola.autarkia.core.brain.instinct.Instinct;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The commitment clockwork against scripted drives and a scripted board: claim over idle,
 * peckish holds, hungry suspends (claim kept), resume re-roots, terminal reporting, and the
 * decoupled work tolerance.
 */
class ArbiterWorkTest {

    private final FakeContext ctx = new FakeContext();

    /** A drive with a settable pressure whose root runs a fixed number of ticks. */
    private static final class StubDrive implements Instinct {
        final String name;
        double pressure;
        int rootTicks;
        int rootsBuilt;

        StubDrive(String name, double pressure, int rootTicks) {
            this.name = name;
            this.pressure = pressure;
            this.rootTicks = rootTicks;
        }

        @Override
        public double pressure(BrainContext c) {
            return pressure;
        }

        @Override
        public Task root(BrainContext c) {
            rootsBuilt++;
            return new StepsTask(rootTicks, TaskStatus.SUCCESS);
        }

        @Override
        public String describe() {
            return name;
        }
    }

    private static final class StepsTask implements PrimitiveTask {
        int steps;
        final TaskStatus end;

        StepsTask(int steps, TaskStatus end) {
            this.steps = steps;
            this.end = end;
        }

        @Override
        public TaskStatus tick(BrainContext c) {
            return --steps <= 0 ? end : TaskStatus.RUNNING;
        }

        @Override
        public void cancel(BrainContext c) {
        }

        @Override
        public String describe() {
            return "steps";
        }
    }

    /** A one-item board that records the arbiter's reports. */
    private static final class StubBoard implements WorkSource {
        WorkItem offered;
        int claims;
        int completions;
        int failures;

        @Override
        public Optional<WorkItem> bestAvailable(BrainContext c) {
            return Optional.ofNullable(offered);
        }

        @Override
        public void claimed(WorkItem item, BrainContext c) {
            claims++;
        }

        @Override
        public void completed(WorkItem item, BrainContext c) {
            completions++;
            offered = null;
        }

        @Override
        public void failed(WorkItem item, BrainContext c) {
            failures++;
            offered = null;
        }
    }

    private static final class StubItem implements WorkItem {
        final double priority;
        int rootTicks;
        TaskStatus end = TaskStatus.SUCCESS;
        int rootsBuilt;

        StubItem(double priority, int rootTicks) {
            this.priority = priority;
            this.rootTicks = rootTicks;
        }

        @Override
        public double priority() {
            return priority;
        }

        @Override
        public Task root() {
            rootsBuilt++;
            return new StepsTask(rootTicks, end);
        }

        @Override
        public String describe() {
            return "acquire logs x16";
        }
    }

    private final StubDrive wander = new StubDrive("wander", 0.15, 3);
    private final StubDrive eat = new StubDrive("eat", 0.05, 5);
    private final StubBoard board = new StubBoard();
    private final Arbiter arbiter = new Arbiter(List.of(eat, wander), board);

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            arbiter.tick(ctx);
        }
    }

    @Test
    void workBeatsIdleWanderAndIsClaimedOnce() {
        board.offered = new StubItem(0.35, 50);
        ticks(3);

        assertEquals(1, board.claims);
        assertTrue(arbiter.describe().contains("work: acquire logs x16 (active)"));
        assertEquals(0, wander.rootsBuilt, "wander lost the bid");
    }

    @Test
    void peckishHoldsTheErrandHungrySuspendsIt() {
        StubItem item = new StubItem(0.35, 200);
        board.offered = item;
        ticks(2);
        eat.pressure = 0.30; // peckish: below PREEMPT and below the running errand's stickiness
        ticks(5);
        assertTrue(arbiter.describe().contains("(active)"), "she finishes the errand while peckish");

        eat.pressure = 0.65; // hungry: past the preempt bar
        ticks(1);
        assertTrue(arbiter.describe().contains("(suspended)"), "the errand yielded mid-swing");
        assertEquals(1, board.claims, "suspension is not unclaiming");
        assertEquals(0, board.failures, "and not failure either");
    }

    @Test
    void resumeAfterTheEmergencyBuildsAFreshRoot() {
        StubItem item = new StubItem(0.35, 200);
        board.offered = item;
        ticks(2);
        eat.pressure = 0.65;
        ticks(1); // suspended; eat root (5 ticks) starts next tick
        eat.pressure = 0.05; // sated the moment it starts eating, but the root runs its course
        ticks(8); // eat root finishes -> boundary -> the suspended claim outbids wander

        assertEquals(2, item.rootsBuilt, "resume = a FRESH root, re-decomposed");
        assertEquals(1, board.claims, "claimed exactly once across the interruption");
        assertTrue(arbiter.describe().contains("(active)"));
    }

    @Test
    void terminalOutcomesReportToTheBoard() {
        StubItem done = new StubItem(0.35, 2);
        board.offered = done;
        ticks(5);
        assertEquals(1, board.completions);
        assertFalse(arbiter.describe().contains("work:"), "the claim cleared");

        StubItem broken = new StubItem(0.35, 2);
        broken.end = TaskStatus.FAILED;
        board.offered = broken;
        ticks(5);
        assertEquals(1, board.failures);
    }

    @Test
    void tiesGoToTheDrives() {
        wander.pressure = 0.35; // the item's bid
        board.offered = new StubItem(0.35, 50);
        ticks(3);

        assertEquals(0, board.claims, "the body outranks the day job on a tie");
        assertTrue(wander.rootsBuilt > 0);
    }

    @Test
    void workToleranceIsPolicyNotDesperation() {
        board.offered = new StubItem(0.35, 50);
        ticks(2);
        assertEquals(WorkToleranceCurve.tolerance(0.35), arbiter.costTolerance(),
                "the errand budgets by priority, not by any need's pressure");
    }
}
