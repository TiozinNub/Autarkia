package dev.luizloyola.autarkia.core.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.Producers;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.autarkia.core.board.Stock;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ChopForLogs} is where logs come from: applicable exactly when a free remembered tree
 * exists, priced by distance so ground wood wins, decomposing to the dance-card executor.
 */
class ChopForLogsTest {

    private static PoiMemory tree(int x, int y, int z, long seen) {
        Pos anchor = new Pos(x, y, z);
        return new PoiMemory(Pois.TREE, "oak", null, anchor,
                new Region(anchor, new Pos(x + 2, y + 6, z + 2)), 7, false, seen);
    }

    @Test
    void applicableExactlyWhenAFreeTreeIsRemembered() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.inventory.add(dev.luizloyola.anima.core.inv.ItemStack.of("minecraft:oak_log", 8, 64));
        ChopForLogs chop = new ChopForLogs();
        assertFalse(chop.applicable(ctx), "no memory, no method");

        ctx.knowledge().note(tree(10, 64, 0, 0), 8);

        assertTrue(chop.applicable(ctx));
        List<Task> plan = chop.decompose(ctx);
        assertEquals(1, plan.size());
        assertTrue(plan.get(0) instanceof ChopPlannedTree);
    }

    @Test
    void theNearestFreeTreePricesTheMethod() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.inventory.add(dev.luizloyola.anima.core.inv.ItemStack.of("minecraft:oak_log", 8, 64));
        ctx.knowledge().note(tree(30, 64, 0, 0), 8);
        ctx.knowledge().note(tree(6, 64, 0, 0), 8);
        ChopForLogs chop = new ChopForLogs();

        assertEquals(6.0, chop.estimateCost(ctx), 0.01,
                "priced by the nearest tree, like scavenging is priced by the nearest drop");
    }

    @Test
    void aPlainTreeIsOfferedToAnEmptyPack() {
        // Wood buys the pillar, so a Person with nothing must still accept a plain tree — those
        // climb their own trunk and cost nothing to begin. Thirteen logs inside a box thirteen
        // tall can only be one straight column.
        FakeContext ctx = new FakeContext();
        Pos anchor = new Pos(10, 64, 0);
        ctx.knowledge().note(new PoiMemory(Pois.TREE, "plain", null, anchor,
                new Region(anchor, new Pos(12, 64 + 13, 2)), 13, false, 0), 8);
        ChopForLogs chop = new ChopForLogs();

        assertTrue(chop.applicable(ctx),
                "a plain trunk pays for its own ladder, however tall it stands");
        assertTrue(chop.decompose(ctx).get(0) instanceof ChopPlannedTree);
    }

    @Test
    void aTreeThePackCannotFundIsNotOffered() {
        // The pillar is prepaid, and cost is part of validity (Luiz): a giant is off the menu
        // until smaller work fills the pack — never a walk-there-and-bail discovery.
        FakeContext ctx = new FakeContext();
        Pos anchor = new Pos(10, 64, 0);
        ctx.knowledge().note(new PoiMemory(Pois.TREE, "giant", null, anchor,
                new Region(anchor, new Pos(12, 64 + 14, 2)), 30, false, 0), 8);
        ChopForLogs chop = new ChopForLogs();

        assertFalse(chop.applicable(ctx), "fourteen tall on an empty pack: unaffordable");

        ctx.percepts.inventory.add(dev.luizloyola.anima.core.inv.ItemStack.of("minecraft:oak_log", 12, 64));
        assertTrue(chop.applicable(ctx), "funded, the same giant is back on the menu");
    }

    @Test
    void anAvoidedTreeIsNobodysProducer() {
        FakeContext ctx = new FakeContext();
        ctx.knowledge().note(tree(10, 64, 0, 0), 0);
        ctx.knowledge().avoid(Pois.TREE, new Pos(10, 64, 0), 1000);

        assertFalse(new ChopForLogs().applicable(ctx),
                "a tree she gave up on stays given up until the avoidance lapses");
    }

    @Test
    void obtainLogsOffersTheChopOnceRegistered() {
        // Registration puts the fell on ObtainItem's menu, after the always-present scavenge.
        Producers.register(Stock.LOGS, ChopForLogs::new);
        ObtainItem obtain = new ObtainItem(Stock.LOGS, 16);

        assertTrue(obtain.methods().stream()
                        .anyMatch(m -> m instanceof ChopForLogs),
                "obtain logs now knows where logs come from");
        List<Method> methods = obtain.methods();
        assertFalse(methods.get(0) instanceof ChopForLogs,
                "scavenging keeps its place at the head of the menu");
    }
}
