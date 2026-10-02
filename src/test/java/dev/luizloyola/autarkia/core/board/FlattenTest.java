package dev.luizloyola.autarkia.core.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.act.BreakState;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.FakePlacer;
import dev.luizloyola.anima.core.brain.task.KittedErrand;
import dev.luizloyola.anima.core.brain.task.TaskExecutor;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.terrain.NaturalGround;
import dev.luizloyola.autarkia.core.earthwork.FlattenPlan;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntBinaryOperator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FlattenTest {

    /** The area is [0, 7]²; FakeProbe's ground is at 63. */
    private static final int HI = 7;
    private static final int RING = 8;
    private static final int G = FakeProbe.GROUND_Y;

    @BeforeAll
    static void dirtIsDirt() {
        Stock.dirtBy(id -> id.equals("minecraft:dirt"));
        Stock.layableBy(id -> id.equals("minecraft:dirt") || id.equals("minecraft:cobblestone"));
    }

    private static Flatten flatten(IntBinaryOperator height) {
        NaturalGround scan = new NaturalGround(-RING, -RING, HI + 1 + 2 * RING, HI + 1 + 2 * RING);
        for (int x = -RING; x <= HI + RING; x++) {
            for (int z = -RING; z <= HI + RING; z++) {
                scan.set(x, z, height.applyAsInt(x, z), 0);
            }
        }
        FlattenPlan plan = FlattenPlan.of(scan, 0, 0, HI, HI,
                new FlattenPlan.Rules(0, OptionalInt.of(G), 0, RING));
        return Flatten.of(plan, 0, 0, HI, HI, 0, 0.5);
    }

    /** The world as the scan saw it: ground at {@code height}, air above. */
    private static FakeContext world(IntBinaryOperator height) {
        FakeContext ctx = new FakeContext();
        for (int x = -RING; x <= HI + RING; x++) {
            for (int z = -RING; z <= HI + RING; z++) {
                int h = height.applyAsInt(x, z);
                for (int y = G + 1; y <= h; y++) {
                    ctx.percepts.blocks.set(x, y, z, BlockKind.OTHER);
                }
                for (int y = h + 1; y <= G; y++) {
                    ctx.percepts.blocks.set(x, y, z, BlockKind.AIR);
                }
            }
        }
        return ctx;
    }

    private static Set<String> offers(Flatten project) {
        return project.open().stream().map(WorkItem::describe).collect(Collectors.toSet());
    }

    private static WorkItem offer(Flatten project, String described) {
        return project.open().stream().filter(i -> i.describe().equals(described)).findFirst()
                .orElseThrow(() -> new AssertionError(described + " not in " + offers(project)));
    }

    /** A mound two high on the patch at the origin. */
    private static int mound(int x, int z) {
        return x >= 1 && x <= 2 && z >= 1 && z <= 2 ? G + 2 : G;
    }

    @Test
    void aMoundIsCutFromTheTopLayerDown() {
        Flatten project = flatten(FlattenTest::mound);
        assertEquals(Set.of("cut 4 at (0, 0) y 65"), offers(project));

        FakeContext ctx = world(FlattenTest::mound);
        for (int x = 1; x <= 2; x++) {
            for (int z = 1; z <= 2; z++) {
                ctx.percepts.blocks.set(x, G + 2, z, BlockKind.AIR);
            }
        }
        project.completed(offer(project, "cut 4 at (0, 0) y 65"), ctx);
        assertEquals(Set.of("cut 4 at (0, 0) y 64"), offers(project));
        assertFalse(project.finished());

        for (int x = 1; x <= 2; x++) {
            for (int z = 1; z <= 2; z++) {
                ctx.percepts.blocks.set(x, G + 1, z, BlockKind.AIR);
            }
        }
        project.completed(offer(project, "cut 4 at (0, 0) y 64"), ctx);
        assertTrue(project.finished());
        assertTrue(project.open().isEmpty());
    }

    /** One tall column and one short: the short one waits until the tall one is down to it. */
    @Test
    void noPatchIsCutMoreThanALayerBelowTheRest() {
        IntBinaryOperator height = (x, z) -> x == 1 && z == 1 ? G + 4 : x == 5 && z == 5 ? G + 1 : G;
        Flatten project = flatten(height);
        assertEquals(Set.of("cut 1 at (0, 0) y 67"), offers(project));
    }

    @Test
    void aHoleIsFilledFromTheBottomUpWithDirtOnTop() {
        IntBinaryOperator hole = (x, z) -> x == 5 && z == 5 ? G - 2 : G;
        Flatten project = flatten(hole);
        WorkItem bottom = offer(project, "fill 1 at (4, 4) y 62");
        assertEquals(1, bottom.kit().calls().size(), "buried: any spoil");

        FakeContext ctx = world(hole);
        ctx.percepts.blocks.set(5, G - 1, 5, BlockKind.OTHER);
        project.completed(bottom, ctx);
        WorkItem top = offer(project, "fill 1 at (4, 4) y 63");
        assertTrue(top.kit().calls().stream().anyMatch(c -> c.spec() == Stock.DIRT), "the top is dirt");
    }

    @Test
    void aFillerWithNothingToFillWithMakesTheFillsWait() {
        IntBinaryOperator hole = (x, z) -> x == 5 && z == 5 ? G - 1 : G;
        Flatten project = flatten(hole);
        FakeContext ctx = world(hole);
        ctx.percepts.time = 100;
        project.completed(offer(project, "fill 1 at (4, 4) y 63"), ctx);
        assertTrue(project.open().isEmpty());
        assertTrue(project.describe().contains("waiting on dirt"));

        project.tick(100 + Flatten.MATERIAL_WAIT);
        assertEquals(Set.of("fill 1 at (4, 4) y 63"), offers(project));
    }

    @Test
    void aFillerWithSpoilIsNotWaitingWhenTheTripFails() {
        IntBinaryOperator hole = (x, z) -> x == 5 && z == 5 ? G - 1 : G;
        Flatten project = flatten(hole);
        FakeContext ctx = world(hole);
        ctx.percepts.inventory.add(ItemStack.of("minecraft:dirt", 4, 64));
        project.failed(offer(project, "fill 1 at (4, 4) y 63"), ctx);
        assertFalse(project.describe().contains("waiting on dirt"));
    }

    @Test
    void aPatchThatNeverMovesIsGivenUpOn() {
        Flatten project = flatten(FlattenTest::mound);
        FakeContext ctx = world(FlattenTest::mound);
        for (int i = 0; i < Flatten.REFUSE_AFTER; i++) {
            ctx.percepts.time = i * 100_000L;
            project.tick(ctx.percepts.time);
            project.failed(offer(project, "cut 4 at (0, 0) y 65"), ctx);
        }
        assertTrue(project.finished(), "nothing left that anybody can do");
        assertTrue(project.describe().contains("4 columns given up on"));
    }

    @Test
    void theSpoilAFillWantsStaysInThePack() {
        Flatten project = flatten((x, z) -> x == 5 && z == 5 ? G - 3 : G);
        assertEquals(3, project.reserved().get(0).count());
        assertTrue(flatten((x, z) -> G + (x == 1 && z == 1 ? 1 : 0)).reserved().isEmpty());
    }

    @Test
    void aRestartChangesNothing() {
        Flatten project = flatten(FlattenTest::mound);
        FakeContext ctx = world(FlattenTest::mound);
        ctx.percepts.blocks.set(1, G + 2, 1, BlockKind.AIR);
        ctx.percepts.time = 50;
        project.completed(offer(project, "cut 4 at (0, 0) y 65"), ctx);

        Flatten back = Flatten.restore(project.snapshot(), 60);
        assertEquals(project.snapshot(), back.snapshot());
        assertEquals(offers(project), offers(back));
        WorkKey key = project.keyOf(project.open().get(0)).orElseThrow();
        assertTrue(back.itemFor(key).isPresent());
    }

    // ── a fill errand played out ─────────────────────────────────────────────────────────────

    /** A hole one deep at (5, 5) and its fill errand. */
    private record Fill(Flatten project, WorkItem item, FakeContext ctx) {
    }

    private static Fill aHole() {
        IntBinaryOperator hole = (x, z) -> x == 5 && z == 5 ? G - 1 : G;
        Flatten project = flatten(hole);
        FakeContext ctx = world(hole);
        ctx.percepts.position = new Pos(5, G + 1, 3);
        ctx.mover.setState(MoveState.ARRIVED);
        return new Fill(project, offer(project, "fill 1 at (4, 4) y 63"), ctx);
    }

    /**
     * Plays the world's part until the errand ends or {@code untilDigging} sees a dig begin: legs that
     * arrive, an arm whose block drops straight into the pack, hands that place.
     */
    private static void play(TaskExecutor executor, FakeContext ctx, boolean untilDigging) {
        int moves = ctx.mover.moveToCalls;
        int placed = ctx.placer.placed.size();
        for (int tick = 0; tick < 2000 && executor.isBusy(); tick++) {
            executor.tick(ctx);
            if (ctx.mover.moveToCalls != moves) {
                moves = ctx.mover.moveToCalls;
                ctx.percepts.position = new Pos(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ);
            }
            if (ctx.breaker.state == BreakState.BREAKING) {
                if (untilDigging) {
                    return;
                }
                Pos dug = ctx.breaker.target;
                ctx.percepts.blocks.set(dug.x(), dug.y(), dug.z(), BlockKind.AIR);
                ctx.percepts.blocks.setId(dug.x(), dug.y(), dug.z(), "");
                ctx.percepts.inventory.add(ItemStack.of("minecraft:dirt", 1, 64));
                ctx.breaker.state = BreakState.FINISHED;
            }
            if (ctx.placer.placed.size() != placed) {
                FakePlacer.Placement placement = ctx.placer.placed.get(placed++);
                Pos cell = placement.cell();
                ctx.percepts.blocks.set(cell.x(), cell.y(), cell.z(), BlockKind.OTHER);
                ctx.percepts.inventory.remove(placement.itemId(), 1);
            }
        }
    }

    private static TaskExecutor started(Fill fill) {
        TaskExecutor executor = new TaskExecutor();
        executor.run(KittedErrand.around(fill.item()), fill.ctx());
        return executor;
    }

    @Test
    void aFillWithNoDirtAnywhereFailsRatherThanCompletes() {
        Fill fill = aHole();
        TaskExecutor executor = started(fill);
        play(executor, fill.ctx(), false);

        assertEquals(TaskStatus.FAILED, executor.lastStatus().orElseThrow(),
                "nothing placed is not a fill done: " + executor.failureReason().orElse(""));
        assertTrue(fill.ctx().placer.placed.isEmpty());
        fill.project().failed(fill.item(), fill.ctx());
        assertTrue(fill.project().describe().contains("waiting on dirt"));
    }

    @Test
    void aRefusedPlanCannotBePosted() {
        NaturalGround scan = new NaturalGround(0, 0, 1, 1);
        FlattenPlan plan = FlattenPlan.of(scan, 0, 0, 0, 0,
                new FlattenPlan.Rules(0, OptionalInt.empty(), 0, 0));
        assertTrue(plan.refused());
        try {
            Flatten.of(plan, 0, 0, 0, 0, 0, 0.5);
            throw new AssertionError("posted a refused plan");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("refused"));
        }
    }
}
