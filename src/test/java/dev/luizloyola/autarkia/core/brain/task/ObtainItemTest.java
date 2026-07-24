package dev.luizloyola.autarkia.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.act.BreakState;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiKind;
import dev.luizloyola.autarkia.core.brain.knowledge.PoiMemory;
import dev.luizloyola.autarkia.core.brain.knowledge.Region;
import dev.luizloyola.autarkia.core.brain.sense.Drop;
import dev.luizloyola.autarkia.core.brain.sense.Pos;
import dev.luizloyola.autarkia.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.inv.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ObtainItem end to end on the fake world: the scavenge round, the chop rounds crossing two trees
 * to make quota (the alternation the cost comparison promises), and the dead end reported as one.
 */
class ObtainItemTest {

    private final FakeContext ctx = new FakeContext();
    private final TaskExecutor executor = new TaskExecutor();

    private PoiMemory oakAt(int x, int z) {
        Pos anchor = new Pos(x, 64, z);
        ctx.percepts.blocks.placeOak(x, z);
        PoiMemory memory = new PoiMemory(PoiKind.TREE, anchor,
                new Region(new Pos(x - 1, 64, z - 1), new Pos(x + 1, 68, z + 1)), 4, false, 0);
        ctx.knowledge.note(memory);
        return memory;
    }

    /**
     * Drives the executor with world physics: a broken LOG spawns a matching drop at its cell;
     * any collect/gather walk vacuums every current drop into the inventory.
     */
    private Optional<TaskStatus> drive(Task root, int maxTicks) {
        executor.run(root, ctx);
        List<Drop> drops = new ArrayList<>(ctx.percepts.drops);
        for (int i = 0; i < maxTicks && executor.isBusy(); i++) {
            int movesBefore = ctx.mover.moveToCalls;
            executor.tick(ctx);
            if (ctx.breaker.state == BreakState.BREAKING) {
                Pos t = ctx.breaker.target;
                if (ctx.percepts.blocks.at(t.x(), t.y(), t.z())
                        == dev.luizloyola.autarkia.core.brain.knowledge.BlockKind.LOG) {
                    drops.add(new Drop(t, "minecraft:oak_log"));
                    ctx.percepts.drops = List.copyOf(drops);
                }
                ctx.percepts.blocks.clear(t.x(), t.y(), t.z());
                ctx.breaker.state = BreakState.FINISHED;
            }
            if (ctx.mover.moveToCalls > movesBefore && !drops.isEmpty()) {
                for (Drop drop : drops) {
                    ctx.percepts.inventory.add(ItemStack.of(drop.itemId(), 1, 64));
                }
                drops.clear();
                ctx.percepts.drops = List.of();
            }
        }
        return executor.lastStatus();
    }

    @Test
    void scavengingAloneSatisfiesWhenLootIsLyingAround() {
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.drops = List.of(
                new Drop(new Pos(5, 64, 0), "minecraft:oak_log"),
                new Drop(new Pos(6, 64, 0), "minecraft:oak_log"),
                new Drop(new Pos(6, 64, 1), "minecraft:birch_log"));

        Optional<TaskStatus> status = drive(new ObtainItem(ItemSpec.LOGS, 3), 50);

        assertEquals(Optional.of(TaskStatus.SUCCESS), status);
        assertEquals(3, ctx.percepts.inventory.count(ItemSpec.LOGS.matcher()),
                "birch counts too — the spec is a class, not an id");
        assertTrue(ctx.breaker.targets.isEmpty(), "nothing was chopped for loot already on the ground");
    }

    @Test
    void choppingCrossesTreesUntilTheQuotaIsMet() {
        ctx.percepts.position = new Pos(8, 64, 8);
        oakAt(10, 10);
        oakAt(16, 10);

        Optional<TaskStatus> status = drive(new ObtainItem(ItemSpec.LOGS, 8), 600);

        assertEquals(Optional.of(TaskStatus.SUCCESS), status);
        assertEquals(8, ctx.percepts.inventory.count(ItemSpec.LOGS.matcher()),
                "two trees' worth: 4 + 4, rounds until satisfied");
        assertEquals(0, ctx.knowledge.size(), "both groves chopped and forgotten");
    }

    @Test
    void anEmptyWorldIsAnHonestDeadEnd() {
        ctx.percepts.position = new Pos(0, 64, 0);

        Optional<TaskStatus> status = drive(new ObtainItem(ItemSpec.LOGS, 4), 50);

        assertEquals(Optional.of(TaskStatus.FAILED), status,
                "no drops in sight, no remembered trees: nothing applicable, the goal is out of reach");
    }
}
