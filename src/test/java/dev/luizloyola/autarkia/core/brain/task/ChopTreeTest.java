package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.act.BreakState;
import dev.luizloyola.autarkia.core.brain.act.ScaffoldState;
import dev.luizloyola.autarkia.core.brain.knowledge.BlockKind;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiKind;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiMemory;
import dev.luizloyola.autarkia.core.brain.knowledge.Region;
import dev.luizloyola.autarkia.core.brain.sense.Drop;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.inv.ItemStack;
import dev.luizloyola.autarkia.core.log.Entry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The chop choreography on fake ports: trunk second-log-up, the climb in the trunk's own
 * column, branch layers top-down outermost-first with canopy walks, the recheck round, stump
 * last, stranded drops freed, flock collection, replanting. Ghost or felled whole → forget;
 * felled in part → stump and memory KEPT.
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
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_sapling", 1, 64));
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

    /** One sapling per base cell (decision: Luiz); the fishing quota follows the same number. */
    @Test
    void aGiantsStumpIsReplantedAsAFullTwoByTwo() {
        // A little 2×2 giant: four grounded columns, three logs tall, no branches.
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                for (int y = 64; y <= 66; y++) {
                    ctx.percepts.blocks.set(10 + dx, y, 10 + dz, BlockKind.LOG);
                }
            }
        }
        ctx.percepts.position = new Pos(8, 64, 8);
        ctx.knowledge.note(memory);
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_log", 1, 64));     // names the species
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_sapling", 4, 64)); // the full pattern

        TaskStatus status = drive(new ChopTree(memory, true), 400);

        assertEquals(TaskStatus.SUCCESS, status);
        List<Pos> sites = ctx.placer.placed.stream().map(FakePlacer.Placement::cell).toList();
        assertEquals(4, sites.size(), "a 2×2 stump takes a 2×2 of saplings");
        assertTrue(sites.containsAll(List.of(new Pos(10, 64, 10), new Pos(11, 64, 10),
                        new Pos(10, 64, 11), new Pos(11, 64, 11))),
                "one sapling per base cell, the stump's own shape: " + sites);
    }

    /** Collection leaves them on the stump, and a sapling has no collision shape, so planting
     * into their own feet cell succeeds — the tree then grows inside them (live 2026-07-27). */
    @Test
    void theyStepOffTheStumpBeforePlantingIt() {
        placeOakAndStandBy();
        ctx.percepts.position = anchor; // collection left them standing on the stump
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_log", 1, 64));
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_sapling", 1, 64));

        List<Pos> plantedFrom = new ArrayList<>();
        ChopTree task = new ChopTree(memory, true);
        TaskStatus status = TaskStatus.RUNNING;
        int walksHonoured = ctx.mover.moveToCalls;
        for (int i = 0; i < 400 && status == TaskStatus.RUNNING; i++) {
            int before = ctx.placer.placed.size();
            status = task.tick(ctx);
            if (ctx.placer.placed.size() > before) {
                plantedFrom.add(ctx.percepts.position);
            }
            // The world honours their replant walks — the fake mover only records them.
            if (task.describe().contains("replant") && ctx.mover.moveToCalls > walksHonoured) {
                walksHonoured = ctx.mover.moveToCalls;
                ctx.percepts.position = new Pos(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ);
            }
            if (ctx.breaker.state == BreakState.BREAKING) {
                Pos t = ctx.breaker.target;
                ctx.percepts.blocks.clear(t.x(), t.y(), t.z());
                ctx.breaker.state = BreakState.FINISHED;
            }
        }

        assertEquals(TaskStatus.SUCCESS, status);
        assertEquals(List.of(new FakePlacer.Placement("minecraft:oak_sapling", anchor)),
                ctx.placer.placed, "the sapling still goes on the stump site");
        assertEquals(1, plantedFrom.size());
        Pos stance = plantedFrom.get(0);
        assertTrue(stance.x() != anchor.x() || stance.z() != anchor.z(),
                "planted from beside the stump, not from inside the column it grows back in: "
                        + stance);
    }

    /** Nowhere to step off the stump: the replant is given up rather than entomb them. */
    @Test
    void walledInOnTheStumpTheReplantIsSkippedRatherThanPlantUnderfoot() {
        placeOakAndStandBy();
        ctx.percepts.position = anchor;
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_log", 1, 64));
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_sapling", 1, 64));
        // A one-cell pit: every cell they could step to is filled to head height.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                ctx.percepts.blocks.set(anchor.x() + dx, 64, anchor.z() + dz, BlockKind.OTHER);
                ctx.percepts.blocks.set(anchor.x() + dx, 65, anchor.z() + dz, BlockKind.OTHER);
            }
        }

        TaskStatus status = drive(new ChopTree(memory, true), 400);

        assertEquals(TaskStatus.SUCCESS, status, "a skipped courtesy is not a failed errand");
        assertTrue(ctx.placer.placed.isEmpty(), "nothing planted into the cell they stand in");
        assertTrue(ctx.journal().recent(50).stream().map(Entry::detail)
                        .anyMatch(line -> line.contains("no room to step off")),
                "and they say why");
    }

    /** The crown kills wider than the trunk — Planter suffocated one block from their sapling —
     * so the last act walks {@link ChopTree#REPLANT_CLEAR_OFF} blocks clear of the planting. */
    @Test
    void afterReplantingTheyWalkClearOfTheFutureCrown() {
        placeOakAndStandBy();
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_log", 1, 64)); // names the species
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_sapling", 1, 64));

        TaskStatus status = drive(new ChopTree(memory, true), 400);

        assertEquals(TaskStatus.SUCCESS, status);
        assertEquals(1, ctx.placer.placed.size(), "the sapling still lands");
        long dx = ctx.mover.lastX - anchor.x();
        long dz = ctx.mover.lastZ - anchor.z();
        assertTrue(dx * dx + dz * dz
                        >= (long) ChopTree.REPLANT_CLEAR_OFF * ChopTree.REPLANT_CLEAR_OFF,
                "their LAST walk of the errand leaves the sapling's future crown: went to ("
                        + ctx.mover.lastX + ", " + ctx.mover.lastZ + ")");
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
                BlockKind.LEAVES);
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
                "they cleared their own sightline, then felled the log behind it");
    }

    /** A trunk taller than the arm's ground reach sends its high logs to the CLIMB phase. */
    @Test
    void climbsTheColumnForTheHighLogsAndUnbuildsOnTheWayDown() {
        placeOakAndStandBy();
        // A taller trunk: two extra logs above the standard four.
        ctx.percepts.blocks.set(10, 68, 10, BlockKind.LOG);
        ctx.percepts.blocks.set(10, 69, 10, BlockKind.LOG);
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_log", 4, 64)); // the scaffolding
        Pos high = new Pos(10, 69, 10);
        ctx.breaker.refuse.add(high); // out of reach until they have climbed twice

        ChopTree task = new ChopTree(memory, false);
        TaskStatus status = TaskStatus.RUNNING;
        for (int i = 0; i < 400 && status == TaskStatus.RUNNING; i++) {
            status = task.tick(ctx);
            if (ctx.scaffolder.state == ScaffoldState.RISING) {
                // Play the body: land one block higher on a block from the pack — and LEDGER
                // the landed cell, the way PersonScaffolder does at the actual placement.
                Pos feet = ctx.percepts.position;
                ctx.percepts.blocks.set(feet.x(), feet.y(), feet.z(), BlockKind.LOG);
                ctx.scaffolder.placed.push(feet);
                ctx.percepts.position = new Pos(feet.x(), feet.y() + 1, feet.z());
                ctx.scaffolder.state = ScaffoldState.RISEN;
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
        assertTrue(ctx.mover.events.contains("moveTo(10, 65, 10)"),
                "the climb begins by mounting the first log — the trunk's own column");
        assertTrue(ctx.scaffolder.ups >= 2, "they climbed for the high log");
        assertEquals("minecraft:oak_log", ctx.scaffolder.lastItem, "pillared on their own logs");
        Pos firstStep = new Pos(8, 64, 8); // where they stood when the first step went down
        assertTrue(ctx.breaker.targets.contains(firstStep),
                "the pillar was un-built on the way down — no towers left behind");
        assertTrue(ctx.scaffolder.placed.isEmpty(), "the body's ledger ends the chop clean");
    }

    /**
     * The tall trunk again, with the body refusing to climb:
     * {@link dev.luizloyola.autarkia.core.brain.act.Scaffolder#up}'s retry is bounded, so a spot
     * that keeps killing steps stays refusing while they stand there. The climb concedes after
     * {@link ChopTree#STALL_TICKS} action-less ticks, the recheck gives one more round, and what
     * stands is a partial fell — stump kept, memory kept.
     */
    @Test
    void aRefusedClimbConcedesTheColumnInsteadOfAskingForever() {
        placeOakAndStandBy();
        ctx.percepts.blocks.set(10, 68, 10, BlockKind.LOG);
        ctx.percepts.blocks.set(10, 69, 10, BlockKind.LOG);
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_log", 4, 64));
        Pos high = new Pos(10, 69, 10);
        ctx.breaker.refuse.add(high); // reachable by pillar alone, and they will not pillar
        ctx.scaffolder.refuse = true;

        TaskStatus status = drive(new ChopTree(memory, false), 400);

        assertEquals(TaskStatus.SUCCESS, status,
                "a refusal ends the column, not the tick loop — everything reachable still fell");
        assertEquals(0, ctx.scaffolder.ups, "a refused climb is never begun");
        assertEquals(BlockKind.LOG, ctx.percepts.blocks.at(10, 69, 10),
                "the log they could not reach was left standing, not silently dropped");
        assertEquals(1, ctx.knowledge.size(), "and the tree stays remembered, to be finished");
    }

    /** Branch work is LAYERS: top layer first, outermost-first within a layer — never orphan. */
    @Test
    void branchesComeDownLayerByLayerOutermostFirst() {
        placeOakAndStandBy();
        // Three branches hanging off the canopy: two in the top layer, one a layer below.
        ctx.percepts.blocks.set(12, 68, 10, BlockKind.LOG);
        ctx.percepts.blocks.set(13, 68, 10, BlockKind.LOG);
        ctx.percepts.blocks.set(12, 67, 10, BlockKind.LOG);

        TaskStatus status = drive(new ChopTree(memory, false), 400);

        assertEquals(TaskStatus.SUCCESS, status);
        int far68 = ctx.breaker.targets.indexOf(new Pos(13, 68, 10));
        int near68 = ctx.breaker.targets.indexOf(new Pos(12, 68, 10));
        int at67 = ctx.breaker.targets.indexOf(new Pos(12, 67, 10));
        int stump = ctx.breaker.targets.indexOf(new Pos(10, 64, 10));
        assertTrue(far68 >= 0 && near68 >= 0 && at67 >= 0, "every branch was felled");
        assertTrue(far68 < near68, "within a layer: outermost first — the never-orphan order");
        assertTrue(near68 < at67, "layers come top-down");
        assertTrue(at67 < stump, "and the stump still falls dead last");
        assertEquals(0, ctx.knowledge.size(), "felled whole -> forgotten");
    }

    /** The canopy walk, bought by the pathfinder's stable-leaves-are-ground change: hemming
     * leaves are cleared along the way and the arm re-swings after every hop. */
    @Test
    void walksTheCanopyOutToAFarBranch() {
        placeOakAndStandBy();
        // A branch chain marching away at crown height; the far end is out of arm's reach
        // from anywhere near the trunk.
        ctx.percepts.blocks.set(12, 68, 10, BlockKind.LOG);
        ctx.percepts.blocks.set(13, 68, 10, BlockKind.LOG);
        ctx.percepts.blocks.set(14, 68, 10, BlockKind.LOG);
        Pos far = new Pos(14, 68, 10);
        ctx.breaker.refuse.add(far); // until they have walked out toward it

        ChopTree task = new ChopTree(memory, false);
        TaskStatus status = TaskStatus.RUNNING;
        boolean walkedOut = false;
        for (int i = 0; i < 500 && status == TaskStatus.RUNNING; i++) {
            status = task.tick(ctx);
            // The walk aims at a standable STANCE beside the branch, not the branch's own
            // cell — any goal that far out along the chain counts as walking the canopy.
            if (!walkedOut && ctx.mover.moveToCalls > 0 && ctx.mover.lastX >= 12) {
                walkedOut = true;
                ctx.breaker.refuse.remove(far); // the walk brought it into reach
            }
            if (ctx.breaker.state == BreakState.BREAKING) {
                Pos t = ctx.breaker.target;
                ctx.percepts.blocks.clear(t.x(), t.y(), t.z());
                ctx.breaker.state = BreakState.FINISHED;
            }
        }

        assertEquals(TaskStatus.SUCCESS, status);
        assertTrue(walkedOut,
                "refused from the center, they walked the canopy toward the branch");
        assertEquals(BlockKind.AIR, ctx.percepts.blocks.at(14, 68, 10),
                "and the far branch came down");
        assertEquals(0, ctx.knowledge.size(), "nothing left standing -> forgotten");
    }

    @Test
    void aFreshChopUnbuildsAnInheritedTowerBeforeWalking() {
        placeOakAndStandBy();
        // A leftover tower from a suspended climb: the BODY remembers it, no task does.
        Pos lower = new Pos(8, 64, 8);
        Pos upper = new Pos(8, 65, 8);
        ctx.percepts.blocks.set(lower.x(), lower.y(), lower.z(), BlockKind.LOG);
        ctx.percepts.blocks.set(upper.x(), upper.y(), upper.z(), BlockKind.LOG);
        ctx.scaffolder.placed.push(lower);
        ctx.scaffolder.placed.push(upper);
        ctx.percepts.position = new Pos(8, 66, 8); // standing on top of it

        TaskStatus status = drive(new ChopTree(memory, false), 400);

        assertEquals(TaskStatus.SUCCESS, status);
        assertEquals(upper, ctx.breaker.targets.get(0), "first swing: the tower, newest cell first");
        assertEquals(lower, ctx.breaker.targets.get(1), "second swing: the next cell down");
        assertTrue(ctx.scaffolder.placed.isEmpty(), "the inherited tower is fully reclaimed");
    }

    // --- the spruce (2026-07-26): a low canopy used to take the first log and win ---------

    /** Stands them south of the trunk, 3 out — a stance whose arm-ray to the crown misses the
     * own headroom cell, so the headroom rung is the only thing that can clear it. */
    private void standSouthOfTheTrunk() {
        ctx.percepts.blocks.placeOak(10, 10);
        ctx.percepts.blocks.set(10, 68, 10, BlockKind.LOG); // a taller trunk: the crown needs a climb
        ctx.percepts.blocks.set(10, 69, 10, BlockKind.LOG);
        ctx.percepts.position = new Pos(10, 64, 7);
        ctx.knowledge.note(memory);
    }

    /** A nerd-pole step needs the cell TWO above their feet empty; a low canopy puts a leaf
     * exactly there, off the arm's ray to the crown, where no sightline clear ever looks. */
    @Test
    void chopsTheLeafOverheadSoTheClimbCanStart() {
        standSouthOfTheTrunk();
        Pos overHead = new Pos(10, 66, 7); // exactly feet + 2, and not on the ray to the crown
        ctx.percepts.blocks.set(overHead.x(), overHead.y(), overHead.z(), BlockKind.LEAVES);
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_log", 4, 64));
        Pos crown = new Pos(10, 69, 10);
        ctx.breaker.refuse.add(crown); // out of reach until they have climbed

        ChopTree task = new ChopTree(memory, false);
        TaskStatus status = TaskStatus.RUNNING;
        for (int i = 0; i < 400 && status == TaskStatus.RUNNING; i++) {
            // Play the body's own headroom check (PersonScaffolder.up): while a block stands two
            // above their feet, there is no room to jump and the step is refused.
            Pos feet = ctx.percepts.position;
            ctx.scaffolder.refuse = ctx.percepts.blocks.at(feet.x(), feet.y() + 2, feet.z())
                    != BlockKind.AIR;
            status = task.tick(ctx);
            if (ctx.scaffolder.state == ScaffoldState.RISING) {
                Pos at = ctx.percepts.position;
                ctx.percepts.blocks.set(at.x(), at.y(), at.z(), BlockKind.LOG);
                ctx.scaffolder.placed.push(at);
                ctx.percepts.position = new Pos(at.x(), at.y() + 1, at.z());
                ctx.scaffolder.state = ScaffoldState.RISEN;
                ctx.breaker.refuse.remove(crown); // high enough now
            }
            if (ctx.breaker.state == BreakState.BREAKING) {
                Pos t = ctx.breaker.target;
                ctx.percepts.blocks.clear(t.x(), t.y(), t.z());
                ctx.breaker.state = BreakState.FINISHED;
            }
        }

        assertTrue(ctx.breaker.targets.contains(overHead),
                "they chopped the leaf standing where their pillar step needed air");
        assertTrue(ctx.scaffolder.ups >= 1, "and the climb the leaf was forbidding then began");
        assertEquals(BlockKind.AIR, ctx.percepts.blocks.at(10, 69, 10),
                "the crown they used to abandon came down");
    }

    /** The nearest thing on the arm's line is often one they cannot swing at (behind another
     * leaf, or past the arm), so the whole ray is walked — in the climb as on the ground. */
    @Test
    void walksTheWholeRayWhenTheNearestBlockerRefuses() {
        standSouthOfTheTrunk();
        Pos crown = new Pos(10, 69, 10);
        Pos nearBlocker = new Pos(10, 67, 9);  // first on the ray — and unswingable
        Pos farBlocker = new Pos(10, 68, 9);   // second on the ray — this one yields
        ctx.breaker.refuse.add(nearBlocker);
        ctx.breaker.refuse.add(crown);

        ChopTree task = new ChopTree(memory, false);
        TaskStatus status = TaskStatus.RUNNING;
        for (int i = 0; i < 400 && status == TaskStatus.RUNNING; i++) {
            status = task.tick(ctx);
            if (ctx.breaker.state == BreakState.BREAKING) {
                Pos t = ctx.breaker.target;
                ctx.percepts.blocks.clear(t.x(), t.y(), t.z());
                ctx.breaker.state = BreakState.FINISHED;
                if (t.equals(farBlocker)) {
                    ctx.breaker.refuse.remove(crown); // that was the one in the way
                }
            }
        }

        assertTrue(ctx.breaker.targets.contains(farBlocker),
                "they went past the leaf they could not hit and broke the one behind it");
        assertEquals(BlockKind.AIR, ctx.percepts.blocks.at(10, 69, 10),
                "which is what let the crown come down");
    }

    /** {@link TreeSurvey} individuates only a tree with a GROUNDED base, so a remnant without
     * its stump surveys as zero trees, ghosts on every later visit, and is forgotten forever. */
    @Test
    void woodLeftStandingKeepsItsStumpAndItsMemory() {
        standSouthOfTheTrunk();
        Pos crown = new Pos(10, 69, 10);
        ctx.breaker.refuse.add(crown);
        ctx.scaffolder.refuse = true;

        TaskStatus status = drive(new ChopTree(memory, true), 500);

        assertEquals(TaskStatus.SUCCESS, status, "they felled real wood — that is not a failure");
        assertEquals(BlockKind.LOG, ctx.percepts.blocks.at(10, 69, 10), "the crown is still up");
        assertEquals(BlockKind.LOG, ctx.percepts.blocks.at(10, 64, 10),
                "so the stump STAYS — felling it would strand the crown forever");
        assertEquals(1, ctx.knowledge.size(), "and the tree stays remembered, to be finished later");
        assertTrue(ctx.placer.placed.isEmpty(),
                "nothing replanted under a tree that is still standing");
    }

    /** They pillar on LOGS up the column they are clearing, so their steps land in the cells the
     * standing-wood count asks about — a 9-log spruce counted them twice, live. The body's
     * ledger is the authority. */
    @Test
    void theirOwnScaffoldingIsNotMistakenForStandingWood() {
        placeOakAndStandBy();
        Pos cleared = new Pos(10, 66, 10); // a trunk cell they fell, then pillar back into

        ChopTree task = new ChopTree(memory, false);
        TaskStatus status = TaskStatus.RUNNING;
        boolean pillared = false;
        for (int i = 0; i < 300 && status == TaskStatus.RUNNING; i++) {
            status = task.tick(ctx);
            if (ctx.breaker.state == BreakState.BREAKING) {
                Pos t = ctx.breaker.target;
                ctx.percepts.blocks.clear(t.x(), t.y(), t.z());
                ctx.breaker.state = BreakState.FINISHED;
                // Play the body: the moment that trunk cell is free, the next pillar step lands
                // in it — a LOG in the world again, and on the ledger as THEIRS.
                if (t.equals(cleared) && !pillared) {
                    pillared = true;
                    ctx.percepts.blocks.set(cleared.x(), cleared.y(), cleared.z(), BlockKind.LOG);
                    ctx.scaffolder.placed.push(cleared);
                }
            }
        }

        assertTrue(pillared, "the scenario actually happened: they pillared into a cleared cell");
        assertEquals(TaskStatus.SUCCESS, status);
        List<String> chop = ctx.journal().recent(50).stream()
                .filter(entry -> entry.event().equals("chop")).map(Entry::detail).toList();
        assertTrue(chop.stream().noneMatch(line -> line.contains("still standing")),
                "no re-round — there is no standing wood, only their own scaffolding");
        assertTrue(chop.contains("felled (4 logs)"),
                "and the tally counts the TREE's four logs, not the pillar block twice: " + chop);
        assertEquals(0, ctx.knowledge.size(), "fully felled -> forgotten, not kept for phantom wood");
    }

    /**
     * The permanent orphan (caught live, 2026-07-26): a half-chop leaves a VERTICAL AIR GAP —
     * stump, air, air, crown — and {@link dev.luizloyola.autarkia.core.brain.knowledge.RegionGrowth}
     * cannot grow across air, so the next visit surveys a ONE-log tree. The sweep looks up the
     * stump's column and fells the crown first.
     */
    @Test
    void aLogStrandedOverTheStumpIsFoundAndFelledInsteadOfAbandoned() {
        ctx.percepts.blocks.set(10, 64, 10, BlockKind.LOG); // the stump a half-chop left
        ctx.percepts.blocks.set(10, 68, 10, BlockKind.LOG); // its crown, four up, air between
        ctx.percepts.position = new Pos(8, 64, 8);
        ctx.knowledge.note(memory);

        TaskStatus status = drive(new ChopTree(memory, false), 400);

        assertEquals(TaskStatus.SUCCESS, status);
        assertEquals(BlockKind.AIR, ctx.percepts.blocks.at(10, 68, 10),
                "the stranded crown was swept up and felled, not left hanging");
        assertEquals(BlockKind.AIR, ctx.percepts.blocks.at(10, 64, 10),
                "and only then did the stump come down");
        assertEquals(0, ctx.knowledge.size(), "nothing left standing -> the anchor is forgotten");
    }

    /** A lone stump remembers a region one BLOCK TALL, so bounding the orphan sweep by {@code
     * memory.bounds()} made it run zero times. It climbs a fixed height instead. */
    @Test
    void theOrphanSweepOutreachesAFlatMemoryOfALoneStump() {
        Pos stump = new Pos(10, 64, 10);
        PoiMemory flat = new PoiMemory(PoiKind.TREE, stump,
                new Region(stump, stump), 1, false, 0); // one block tall — all they remember
        ctx.percepts.blocks.set(10, 64, 10, BlockKind.LOG);
        ctx.percepts.blocks.set(10, 68, 10, BlockKind.LOG); // stranded well above the bounds
        ctx.percepts.position = new Pos(8, 64, 8);
        ctx.knowledge.note(flat);

        drive(new ChopTree(flat, false), 400);

        assertEquals(BlockKind.AIR, ctx.percepts.blocks.at(10, 68, 10),
                "the sweep looked past a one-block memory and found the stranded log");
    }

    /** A dry round no longer banks a partial: while each full sweep fells something they
     * re-approach and run the whole machine again (decision: Luiz). */
    @Test
    void aDrySpellReapproachesTheSameTreeInsteadOfDeferring() {
        placeOakAndStandBy();
        Pos high = new Pos(10, 67, 10);
        ctx.breaker.refuse.add(high); // unyielding on the first approach...

        ChopTree task = new ChopTree(memory, false);
        TaskStatus status = TaskStatus.RUNNING;
        boolean secondSweep = false;
        for (int i = 0; i < 600 && status == TaskStatus.RUNNING; i++) {
            status = task.tick(ctx);
            if (!secondSweep && ctx.journal().recent(10).stream()
                    .anyMatch(e -> e.detail().contains("re-approaching"))) {
                secondSweep = true;
                ctx.breaker.refuse.remove(high); // ...but the fresh approach finds the angle
            }
            if (ctx.breaker.state == BreakState.BREAKING) {
                Pos t = ctx.breaker.target;
                ctx.percepts.blocks.clear(t.x(), t.y(), t.z());
                ctx.breaker.state = BreakState.FINISHED;
            }
        }

        assertEquals(TaskStatus.SUCCESS, status);
        assertTrue(secondSweep, "the dry spell re-approached instead of deferring");
        assertEquals(BlockKind.AIR, ctx.percepts.blocks.at(10, 67, 10),
                "the stubborn log fell within the SAME errand");
        assertEquals(0, ctx.knowledge.size(), "felled whole, one errand, one tree — forgotten");
    }

    /** A failed mount once left them pillaring OFF the trunk column, 30 blocks over an 8-log
     * tree (live 2026-07-27). Climbing fires only for a log ABOVE them whose column is within
     * {@link ChopTree#PILLAR_HORIZONTAL}. */
    @Test
    void theClimbNeverPillarsWhereHeightCannotHelp() {
        placeOakAndStandBy();
        ctx.percepts.blocks.set(10, 69, 10, BlockKind.LOG); // high: the climb's business
        ctx.percepts.position = new Pos(14, 64, 14); // and the mount walk will not move them
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_log", 8, 64)); 
        ctx.breaker.refuse.add(new Pos(10, 69, 10)); // out of reach, forever, from over here

        TaskStatus status = drive(new ChopTree(memory, false), 400);

        assertEquals(TaskStatus.SUCCESS, status, "the reachable wood still fell — a real partial");
        assertEquals(0, ctx.scaffolder.ups,
                "their column is 4+ out from the log's: no height helps, so no tower is built");
        assertEquals(BlockKind.LOG, ctx.percepts.blocks.at(10, 69, 10), "the log stands, unfelled");
        assertEquals(1, ctx.knowledge.size(), "and the tree stays remembered, to be finished");
    }

    /** The re-rounds are PROGRESS-GATED, not one-shot: a fixed single round cannot chain. Only
     * a zero-log round ends the errand. */
    @Test
    void reRoundsKeepGoingWhileTheyKeepFellingWood() {
        ctx.percepts.blocks.set(10, 64, 10, BlockKind.LOG); // the stump a half-chop left
        ctx.percepts.blocks.set(10, 66, 10, BlockKind.LOG); // stranded, reachable
        ctx.percepts.blocks.set(10, 68, 10, BlockKind.LOG); // stranded, blocked by the lower one
        ctx.percepts.position = new Pos(8, 64, 8);
        ctx.knowledge.note(memory);
        Pos high = new Pos(10, 68, 10);
        ctx.breaker.refuse.add(high); // until the log below it is gone

        ChopTree task = new ChopTree(memory, false);
        TaskStatus status = TaskStatus.RUNNING;
        for (int i = 0; i < 400 && status == TaskStatus.RUNNING; i++) {
            status = task.tick(ctx);
            if (ctx.breaker.state == BreakState.BREAKING) {
                Pos t = ctx.breaker.target;
                ctx.percepts.blocks.clear(t.x(), t.y(), t.z());
                ctx.breaker.state = BreakState.FINISHED;
                if (t.equals(new Pos(10, 66, 10))) {
                    ctx.breaker.refuse.remove(high); // its fall opened the higher one up
                }
            }
        }

        assertEquals(TaskStatus.SUCCESS, status);
        assertEquals(BlockKind.AIR, ctx.percepts.blocks.at(10, 68, 10),
                "round two took the log round one could not — progress keeps the rounds alive");
        assertEquals(BlockKind.AIR, ctx.percepts.blocks.at(10, 64, 10), "stump down — done");
        assertEquals(0, ctx.knowledge.size(), "felled WHOLE across rounds -> forgotten");
    }

    /** Out of reach, the stump must stay: it is the only thing keeping the remnant a surveyable
     * tree. */
    @Test
    void anUnreachableStrandedLogKeepsItsStumpAndItsMemory() {
        ctx.percepts.blocks.set(10, 64, 10, BlockKind.LOG);
        ctx.percepts.blocks.set(10, 68, 10, BlockKind.LOG);
        ctx.percepts.position = new Pos(8, 64, 8);
        ctx.knowledge.note(memory);
        ctx.breaker.refuse.add(new Pos(10, 68, 10)); // nothing brings it down today
        ctx.scaffolder.refuse = true;

        drive(new ChopTree(memory, false), 400);

        assertEquals(BlockKind.LOG, ctx.percepts.blocks.at(10, 64, 10),
                "the stump stays — felling it is what strands the crown forever");
        assertEquals(1, ctx.knowledge.size(), "and the anchor stays remembered, to be finished later");
    }

    /** The STUMP phase's skip path used to reach a clean descend-time {@code leftStanding} and
     * FORGET a tree whose stump still stood — invisible to perception forever. */
    @Test
    void aStumpTheArmRefusesKeepsItsMemory() {
        placeOakAndStandBy();
        ctx.breaker.refuse.add(new Pos(10, 64, 10)); // the stump refuses; all else fells fine

        TaskStatus status = drive(new ChopTree(memory, false), 400);

        assertEquals(TaskStatus.SUCCESS, status, "wood was won — an unfellable stump is no failure");
        assertEquals(BlockKind.LOG, ctx.percepts.blocks.at(10, 64, 10), "the stump still stands");
        assertEquals(1, ctx.knowledge.size(),
                "so the memory is KEPT — forgetting standing wood is how litter is born");
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
