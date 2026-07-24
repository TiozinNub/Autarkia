package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.Arbiter;
import dev.luizloyola.autarkia.core.brain.BrainContext;
import dev.luizloyola.autarkia.core.brain.board.WorkItem;
import dev.luizloyola.autarkia.core.brain.board.WorkSource;
import dev.luizloyola.autarkia.core.brain.instinct.Instinct;
import dev.luizloyola.autarkia.core.brain.sense.Drop;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.inv.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The arbiter's second demand species: claim on winning the bid, run to true completion,
 * suspend (claim kept) under an urgent drive, resume with a fresh root, report both endings to
 * the board — and lose ties to drives, because the body outranks the day job.
 */
class ArbiterCommitmentTest {

    /** A drive with a dial: scripted pressure, scripted root. */
    private static final class FakeInstinct implements Instinct {
        double pressure;
        StubTask root = new StubTask("drive task");
        int grants;

        @Override
        public double pressure(BrainContext ctx) {
            return pressure;
        }

        @Override
        public Task root(BrainContext ctx) {
            grants++;
            return root;
        }

        @Override
        public String describe() {
            return "fake";
        }
    }

    /** A primitive that reports whatever the test says next. */
    private static final class StubTask implements PrimitiveTask {
        final String name;
        TaskStatus next = TaskStatus.RUNNING;

        StubTask(String name) {
            this.name = name;
        }

        @Override
        public TaskStatus tick(BrainContext ctx) {
            return next;
        }

        @Override
        public void cancel(BrainContext ctx) {
        }

        @Override
        public String describe() {
            return name;
        }
    }

    /** A board double that records the lifecycle calls. */
    private static final class FakeWork implements WorkSource {
        WorkItem offer;
        final List<WorkItem> claims = new ArrayList<>();
        final List<WorkItem> completes = new ArrayList<>();
        final List<WorkItem> fails = new ArrayList<>();

        @Override
        public Optional<WorkItem> bestAvailable() {
            return Optional.ofNullable(offer);
        }

        @Override
        public void claim(WorkItem item) {
            claims.add(item);
            offer = null; // claimed = off the offer, like the real board
        }

        @Override
        public void complete(WorkItem item) {
            completes.add(item);
        }

        @Override
        public void fail(WorkItem item) {
            fails.add(item);
        }
    }

    private final FakeContext ctx = new FakeContext();
    private final FakeInstinct drive = new FakeInstinct();
    private final FakeWork work = new FakeWork();
    private final Arbiter arbiter = new Arbiter(List.of(drive), work);
    private final WorkItem item = new WorkItem("stock-1", ItemSpec.LOGS, 1, 0.35);

    @Test
    void claimsRunsAndReportsCompletion() {
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_log", 1, 64)); // already satisfied
        work.offer = item;

        arbiter.tick(ctx); // claim + run + satisfied-at-entry + boundary, all in one tick

        assertEquals(List.of(item), work.claims);
        assertEquals(List.of(item), work.completes);
        assertTrue(work.fails.isEmpty());
        assertTrue(arbiter.describe().lines().noneMatch(l -> l.startsWith("work:")),
                "the commitment is retired");
    }

    @Test
    void drivesWinTiesAndOutbidWork() {
        drive.pressure = 0.35; // the item's priority
        work.offer = item;

        arbiter.tick(ctx);

        assertTrue(work.claims.isEmpty(), "tie -> the body outranks the day job");
        assertEquals(1, drive.grants);
    }

    @Test
    void anUrgentDriveSuspendsTheCommitmentAndItResumesAfter() {
        // A commitment that stays busy: one matching drop in sight keeps the gatherer walking.
        work.offer = item;
        ctx.percepts.drops = List.of(new Drop(new Pos(10, 64, 0), "minecraft:oak_log"));
        arbiter.tick(ctx);
        assertEquals(1, work.claims.size());
        assertTrue(arbiter.describe().contains("work: acquire logs x1 (active)"));

        // Preempt mid-task: the claim survives, the tree is dropped.
        drive.pressure = 0.7;
        arbiter.tick(ctx);
        assertTrue(arbiter.describe().contains("(suspended)"), arbiter.describe());
        assertTrue(work.fails.isEmpty(), "suspension is not failure");

        // The drive finishes; the commitment out-bids the now-quiet drive and resumes fresh.
        drive.pressure = 0.0;
        drive.root.next = TaskStatus.SUCCESS;
        arbiter.tick(ctx); // drive root succeeds; boundary clears the slot
        arbiter.tick(ctx); // idle re-arbitration: the commitment resumes
        assertTrue(arbiter.describe().contains("work: acquire logs x1 (active)"), arbiter.describe());
        assertEquals(1, work.claims.size(), "resumed, never re-claimed");

        // The pack fills and the drops clear: the goal closes cleanly.
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_log", 1, 64));
        ctx.percepts.drops = List.of();
        arbiter.tick(ctx);
        assertEquals(List.of(item), work.completes);
    }

    @Test
    void anOutOfReachGoalFailsToTheBoard() {
        work.offer = item; // empty world: no drops, no remembered trees, empty pack

        arbiter.tick(ctx); // claim + ObtainItem finds nothing applicable + boundary

        assertEquals(List.of(item), work.claims);
        assertEquals(List.of(item), work.fails);
        assertTrue(work.completes.isEmpty());
    }
}
