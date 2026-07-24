package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.act.BreakState;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiKind;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiMemory;
import dev.luizloyola.autarkia.core.brain.knowledge.Region;
import dev.luizloyola.autarkia.core.brain.sense.Drop;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The whole chop choreography on fake ports: trunk second-log-up, stump last, stranded drops
 * freed by breaking the leaf beneath, flock collection, and the three memory endings (ghost →
 * forget, felled → forget, unreachable → keep).
 */
class ChopTreeTest {

    private final FakeContext ctx = new FakeContext();
    private final Pos anchor = new Pos(10, 64, 10);
    private final PoiMemory memory = new PoiMemory(PoiKind.TREE, anchor,
            new Region(new Pos(9, 64, 9), new Pos(11, 68, 11)), 4, false, 0);

    private void placeOakAndStandBy() {
        ctx.percepts.blocks.placeOak(10, 10);
        ctx.percepts.position = new Pos(8, 64, 8); // inside APPROACH_NEAR: straight to the scan
        ctx.knowledge.note(memory);
    }

    /** Ticks the task; whenever the arm reports BREAKING, the "world" breaks the block. */
    private TaskStatus drive(ChopTree task, int maxTicks) {
        TaskStatus status = TaskStatus.RUNNING;
        for (int i = 0; i < maxTicks && status == TaskStatus.RUNNING; i++) {
            status = task.tick(ctx);
            if (ctx.breaker.state == BreakState.BREAKING) {
                Pos t = ctx.breaker.target;
                ctx.percepts.blocks.clear(t.x(), t.y(), t.z());
                ctx.breaker.state = BreakState.FINISHED;
            }
        }
        return status;
    }

    @Test
    void theChoreographyFellsTrunkSecondLogUpStumpLast() {
        placeOakAndStandBy();
        TaskStatus status = drive(new ChopTree(memory, true), 200);

        assertEquals(TaskStatus.SUCCESS, status);
        assertEquals(List.of(
                        new Pos(10, 65, 10), new Pos(10, 66, 10), new Pos(10, 67, 10),
                        new Pos(10, 64, 10)),
                ctx.breaker.targets,
                "trunk from the SECOND log up, stump dead last");
        assertEquals(0, ctx.knowledge.size(), "felled -> the grove memory is forgotten");
    }

    @Test
    void aGhostGroveIsForgottenAndFailed() {
        ctx.percepts.position = new Pos(8, 64, 8);
        ctx.knowledge.note(memory); // believed, but the world is bare grass

        TaskStatus status = drive(new ChopTree(memory, true), 50);

        assertEquals(TaskStatus.FAILED, status);
        assertEquals(0, ctx.knowledge.size(), "wrong memory -> failed task -> memory update");
    }

    @Test
    void aStrandedDropGetsItsLeafBrokenBeforeTheStump() {
        placeOakAndStandBy();
        // A log item resting on the canopy cap: below it is the leaf at (10, 68, 10).
        ctx.percepts.drops = List.of(new Drop(new Pos(10, 69, 10), "minecraft:oak_log"));

        drive(new ChopTree(memory, true), 300);

        int leafAt = ctx.breaker.targets.indexOf(new Pos(10, 68, 10));
        int stumpAt = ctx.breaker.targets.indexOf(new Pos(10, 64, 10));
        assertTrue(leafAt >= 0, "the leaf under the stranded drop was broken");
        assertTrue(leafAt < stumpAt, "freed BEFORE the stump fell — Luiz's choreography");
    }

    @Test
    void collectionWalksToTheFlockCentroid() {
        placeOakAndStandBy();
        ctx.percepts.drops = List.of(
                new Drop(new Pos(10, 64, 9), "minecraft:oak_log"),
                new Drop(new Pos(11, 64, 10), "minecraft:oak_log"));

        ChopTree task = new ChopTree(memory, true);
        TaskStatus status = TaskStatus.RUNNING;
        boolean walked = false;
        for (int i = 0; i < 300 && status == TaskStatus.RUNNING; i++) {
            status = task.tick(ctx);
            if (ctx.breaker.state == BreakState.BREAKING) {
                Pos t = ctx.breaker.target;
                ctx.percepts.blocks.clear(t.x(), t.y(), t.z());
                ctx.breaker.state = BreakState.FINISHED;
            }
            if (!walked && !ctx.mover.events.isEmpty()
                    && ctx.mover.events.get(ctx.mover.events.size() - 1).startsWith("moveTo(1")) {
                walked = true;
                ctx.percepts.drops = List.of(); // walk-over vacuumed them
            }
        }
        assertEquals(TaskStatus.SUCCESS, status);
        assertTrue(walked, "a collect walk toward the flock was issued");
    }

    @Test
    void replantPlantsOneSaplingPerStumpLogAfterCollecting() {
        placeOakAndStandBy();
        ctx.percepts.inventory.add(dev.luizloyola.autarkia.core.inv.ItemStack.of(
                "minecraft:oak_sapling", 1, 64));
        ctx.percepts.drops = List.of(new Drop(new Pos(10, 64, 9), "minecraft:oak_log"));

        ChopTree task = new ChopTree(memory, true);
        TaskStatus status = TaskStatus.RUNNING;
        for (int i = 0; i < 300 && status == TaskStatus.RUNNING; i++) {
            status = task.tick(ctx);
            if (ctx.breaker.state == BreakState.BREAKING) {
                Pos t = ctx.breaker.target;
                ctx.percepts.blocks.clear(t.x(), t.y(), t.z());
                ctx.breaker.state = BreakState.FINISHED;
            }
            if (!ctx.mover.events.isEmpty() && !ctx.percepts.drops.isEmpty()
                    && ctx.mover.events.get(ctx.mover.events.size() - 1).contains("moveTo(10, 64")) {
                ctx.percepts.drops = List.of(); // vacuumed on the collect walk
            }
        }

        assertEquals(TaskStatus.SUCCESS, status);
        assertEquals(List.of(new FakePlacer.Placement("minecraft:oak_sapling", anchor)),
                ctx.placer.placed, "one sapling, on the stump site, planted LAST");
    }

    @Test
    void replantSkipsGracefullyWithoutASapling() {
        placeOakAndStandBy();
        ctx.percepts.drops = List.of(new Drop(new Pos(10, 64, 9), "minecraft:oak_log"));

        ChopTree task = new ChopTree(memory, true);
        TaskStatus status = TaskStatus.RUNNING;
        for (int i = 0; i < 300 && status == TaskStatus.RUNNING; i++) {
            status = task.tick(ctx);
            if (ctx.breaker.state == BreakState.BREAKING) {
                Pos t = ctx.breaker.target;
                ctx.percepts.blocks.clear(t.x(), t.y(), t.z());
                ctx.breaker.state = BreakState.FINISHED;
            }
            if (!ctx.mover.events.isEmpty() && !ctx.percepts.drops.isEmpty()
                    && ctx.mover.events.get(ctx.mover.events.size() - 1).contains("moveTo(10, 64")) {
                ctx.percepts.drops = List.of();
            }
        }

        assertEquals(TaskStatus.SUCCESS, status, "no sapling is a courtesy skipped, never a failure");
        assertTrue(ctx.placer.placed.isEmpty());
    }

    @Test
    void anUnreachableTreeFailsButStaysRemembered() {
        placeOakAndStandBy();
        ctx.breaker.refuseBegin = true; // every swing out of reach, forever

        TaskStatus status = drive(new ChopTree(memory, true), 300);

        assertEquals(TaskStatus.FAILED, status);
        assertEquals(1, ctx.knowledge.size(),
                "the tree is real — memory kept; retry pacing is the caller's job");
    }
}
