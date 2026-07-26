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
    void clearsTheObstructingLeafThenFellsTheLog() {
        placeOakAndStandBy();
        Pos blocked = new Pos(10, 65, 10);
        Pos leafInTheWay = new Pos(9, 65, 9); // squarely on the arm's line from (8,64,8)
        ctx.percepts.blocks.set(leafInTheWay.x(), leafInTheWay.y(), leafInTheWay.z(),
                dev.luizloyola.autarkia.core.brain.knowledge.BlockKind.LEAVES);
        ctx.breaker.refuse.add(blocked); // the arm refuses while the leaf stands

        ChopTree task = new ChopTree(memory, false);
        TaskStatus status = TaskStatus.RUNNING;
        for (int i = 0; i < 300 && status == TaskStatus.RUNNING; i++) {
            status = task.tick(ctx);
            if (ctx.breaker.state == BreakState.BREAKING) {
                Pos t = ctx.breaker.target;
                ctx.percepts.blocks.clear(t.x(), t.y(), t.z());
                ctx.breaker.state = BreakState.FINISHED;
                if (t.equals(leafInTheWay)) {
                    ctx.breaker.refuse.remove(blocked); // sightline restored
                }
            }
        }

        assertEquals(TaskStatus.SUCCESS, status);
        int leafAt = ctx.breaker.targets.indexOf(leafInTheWay);
        int logAt = ctx.breaker.targets.indexOf(blocked);
        assertTrue(leafAt >= 0 && leafAt < logAt,
                "she cleared her own sightline, then felled the log behind it");
    }

    @Test
    void pillarsUpForTheHighLogsAndUnbuildsOnTheWayDown() {
        placeOakAndStandBy();
        // A taller trunk: two extra logs above the standard four.
        ctx.percepts.blocks.set(10, 68, 10, dev.luizloyola.autarkia.core.brain.knowledge.BlockKind.LOG);
        ctx.percepts.blocks.set(10, 69, 10, dev.luizloyola.autarkia.core.brain.knowledge.BlockKind.LOG);
        ctx.percepts.inventory.add(dev.luizloyola.autarkia.core.inv.ItemStack.of(
                "minecraft:oak_log", 4, 64)); // her scaffolding material
        Pos high = new Pos(10, 69, 10);
        ctx.breaker.refuse.add(high); // out of reach until she has climbed twice

        ChopTree task = new ChopTree(memory, false);
        TaskStatus status = TaskStatus.RUNNING;
        for (int i = 0; i < 400 && status == TaskStatus.RUNNING; i++) {
            status = task.tick(ctx);
            if (ctx.scaffolder.state == dev.luizloyola.autarkia.core.brain.act.ScaffoldState.RISING) {
                // Play the body: land one block higher on a block from the pack — and LEDGER
                // the landed cell, the way PersonScaffolder does at the actual placement.
                Pos feet = ctx.percepts.position;
                ctx.percepts.blocks.set(feet.x(), feet.y(), feet.z(),
                        dev.luizloyola.autarkia.core.brain.knowledge.BlockKind.LOG);
                ctx.scaffolder.placed.push(feet);
                ctx.percepts.position = new Pos(feet.x(), feet.y() + 1, feet.z());
                ctx.scaffolder.state = dev.luizloyola.autarkia.core.brain.act.ScaffoldState.RISEN;
                if (ctx.scaffolder.ups >= 2) {
                    ctx.breaker.refuse.remove(high); // high enough now
                }
            }
            if (ctx.breaker.state == BreakState.BREAKING) {
                Pos t = ctx.breaker.target;
                ctx.percepts.blocks.clear(t.x(), t.y(), t.z());
                ctx.breaker.state = BreakState.FINISHED;
            }
        }

        assertEquals(TaskStatus.SUCCESS, status);
        assertTrue(ctx.scaffolder.ups >= 2, "she climbed for the high log");
        assertEquals("minecraft:oak_log", ctx.scaffolder.lastItem, "pillared on her own logs");
        Pos firstStep = new Pos(8, 64, 8); // where she stood when the first step went down
        assertTrue(ctx.breaker.targets.contains(firstStep),
                "the pillar was un-built on the way down — no towers left behind");
        assertTrue(ctx.scaffolder.placed.isEmpty(), "the body's ledger ends the chop clean");
    }

    /**
     * The same tall trunk, but the body refuses to climb. {@link
     * dev.luizloyola.autarkia.core.brain.act.Scaffolder#up}'s retry is bounded: a spot that keeps
     * killing steps starts refusing and stays refusing while she stands there, so a caller that
     * re-asks every tick spins forever — the 16-tick jump loop. The refusal has to END the target.
     */
    @Test
    void aRefusedClimbLetsTheTargetGoInsteadOfAskingForever() {
        placeOakAndStandBy();
        ctx.percepts.blocks.set(10, 68, 10, dev.luizloyola.autarkia.core.brain.knowledge.BlockKind.LOG);
        ctx.percepts.blocks.set(10, 69, 10, dev.luizloyola.autarkia.core.brain.knowledge.BlockKind.LOG);
        ctx.percepts.inventory.add(dev.luizloyola.autarkia.core.inv.ItemStack.of(
                "minecraft:oak_log", 4, 64)); // material is carried — only the body's refusal decides
        Pos high = new Pos(10, 69, 10);
        ctx.breaker.refuse.add(high); // reachable by pillar alone, and she will not pillar
        ctx.scaffolder.refuse = true;

        TaskStatus status = drive(new ChopTree(memory, false), 400);

        assertEquals(TaskStatus.SUCCESS, status,
                "a refusal ends the target, not the tick loop — everything reachable still fell");
        assertEquals(0, ctx.scaffolder.ups, "a refused climb is never begun");
        assertEquals(dev.luizloyola.autarkia.core.brain.knowledge.BlockKind.LOG,
                ctx.percepts.blocks.at(10, 69, 10), "the log she could not reach was let go");
    }

    @Test
    void neverPillarsTowardASidewaysBranch() {
        placeOakAndStandBy();
        // A horizontal branch off the crown: no pillar height ever brings the far cells into
        // arm's reach from under the trunk.
        for (int x = 12; x <= 15; x++) {
            ctx.percepts.blocks.set(x, 68, 10, dev.luizloyola.autarkia.core.brain.knowledge.BlockKind.LOG);
            ctx.breaker.refuse.add(new Pos(x, 68, 10)); // "out of reach", forever
        }
        ctx.percepts.inventory.add(dev.luizloyola.autarkia.core.inv.ItemStack.of(
                "minecraft:oak_log", 8, 64)); // scaffolding material is available — the guard decides

        TaskStatus status = drive(new ChopTree(memory, false), 400);

        assertEquals(TaskStatus.SUCCESS, status, "the trunk still fell; the branch was let go");
        assertEquals(0, ctx.scaffolder.ups,
                "no tower under a sideways branch — climbing there can never converge");
    }

    @Test
    void aFreshChopUnbuildsAnInheritedTowerBeforeWalking() {
        placeOakAndStandBy();
        // A leftover tower from a suspended climb: the BODY remembers it, no task does.
        Pos lower = new Pos(8, 64, 8);
        Pos upper = new Pos(8, 65, 8);
        ctx.percepts.blocks.set(lower.x(), lower.y(), lower.z(),
                dev.luizloyola.autarkia.core.brain.knowledge.BlockKind.LOG);
        ctx.percepts.blocks.set(upper.x(), upper.y(), upper.z(),
                dev.luizloyola.autarkia.core.brain.knowledge.BlockKind.LOG);
        ctx.scaffolder.placed.push(lower);
        ctx.scaffolder.placed.push(upper);
        ctx.percepts.position = new Pos(8, 66, 8); // standing on top of it

        TaskStatus status = drive(new ChopTree(memory, false), 400);

        assertEquals(TaskStatus.SUCCESS, status);
        assertEquals(upper, ctx.breaker.targets.get(0), "first swing: the tower, newest cell first");
        assertEquals(lower, ctx.breaker.targets.get(1), "second swing: the next cell down");
        assertTrue(ctx.scaffolder.placed.isEmpty(), "the inherited tower is fully reclaimed");
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
