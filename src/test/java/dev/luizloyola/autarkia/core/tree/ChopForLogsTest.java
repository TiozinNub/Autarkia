package dev.luizloyola.autarkia.core.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.knowledge.GrownRegion;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.knowledge.RegionGrowth;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.Method;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.Producers;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.autarkia.core.board.Stock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link ChopForLogs} is where logs come from: applicable exactly when a free remembered tree of
 * the wanted species exists, priced by distance so ground wood wins, decomposing to the dance-card
 * executor.
 */
class ChopForLogsTest {

    /** A remembered tree of a given species — {@code species} is what {@link PoiMemory#detail()}
     *  carries, real ids only: no production code sets an arbitrary label there any more. */
    private static PoiMemory tree(int x, int y, int z, String species, long seen) {
        Pos anchor = new Pos(x, y, z);
        return new PoiMemory(Pois.TREE, species, null, anchor,
                new Region(anchor, new Pos(x + 2, y + 6, z + 2)), 7, false, seen);
    }

    @Test
    void applicableExactlyWhenAFreeTreeIsRemembered() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.inventory.add(dev.luizloyola.anima.core.inv.ItemStack.of("minecraft:oak_log", 8, 64));
        ChopForLogs chop = new ChopForLogs(Stock.LOGS);
        assertFalse(chop.applicable(ctx), "no memory, no method");

        ctx.knowledge().note(tree(10, 64, 0, "minecraft:oak_log", 0), 8);

        assertTrue(chop.applicable(ctx));
        List<Task> plan = chop.decompose(ctx);
        assertEquals(1, plan.size());
        assertTrue(plan.get(0) instanceof ChopPlannedTree);
    }

    @Test
    void theNearestFreeTreePricesTheMethod() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.inventory.add(dev.luizloyola.anima.core.inv.ItemStack.of("minecraft:oak_log", 8, 64));
        ctx.knowledge().note(tree(30, 64, 0, "minecraft:oak_log", 0), 8);
        ctx.knowledge().note(tree(6, 64, 0, "minecraft:oak_log", 0), 8);
        ChopForLogs chop = new ChopForLogs(Stock.LOGS);

        assertEquals(6.0, chop.estimateCost(ctx), 0.01,
                "priced by the nearest tree, like scavenging is priced by the nearest drop");
    }

    @Test
    void aPlainTreeIsOfferedToAnEmptyPack() {
        // Wood buys the pillar, so a Person with nothing must still accept a plain tree — those
        // climb their own trunk and cost nothing to begin. Thirteen logs inside a box thirteen
        // tall can only be one straight column. The species is real oak; what varies here is the
        // shape, carried by units and bounds, not by the detail string.
        FakeContext ctx = new FakeContext();
        Pos anchor = new Pos(10, 64, 0);
        ctx.knowledge().note(new PoiMemory(Pois.TREE, "minecraft:oak_log", null, anchor,
                new Region(anchor, new Pos(12, 64 + 13, 2)), 13, false, 0), 8);
        ChopForLogs chop = new ChopForLogs(Stock.LOGS);

        assertTrue(chop.applicable(ctx),
                "a plain trunk pays for its own ladder, however tall it stands");
        assertTrue(chop.decompose(ctx).get(0) instanceof ChopPlannedTree);
    }

    @Test
    void aTreeThePackCannotFundIsNotOffered() {
        // The pillar is prepaid, and cost is part of validity (Luiz): a giant is off the menu
        // until smaller work fills the pack — never a walk-there-and-bail discovery. Real oak
        // again; "giant" was only ever the shape, which units and bounds already say.
        FakeContext ctx = new FakeContext();
        Pos anchor = new Pos(10, 64, 0);
        ctx.knowledge().note(new PoiMemory(Pois.TREE, "minecraft:oak_log", null, anchor,
                new Region(anchor, new Pos(12, 64 + 14, 2)), 30, false, 0), 8);
        ChopForLogs chop = new ChopForLogs(Stock.LOGS);

        assertFalse(chop.applicable(ctx), "fourteen tall on an empty pack: unaffordable");

        ctx.percepts.inventory.add(dev.luizloyola.anima.core.inv.ItemStack.of("minecraft:oak_log", 12, 64));
        assertTrue(chop.applicable(ctx), "funded, the same giant is back on the menu");
    }

    @Test
    void anAvoidedTreeIsNobodysProducer() {
        // Funded so the avoid clause is what refuses this, not an unrelated empty pack.
        FakeContext ctx = new FakeContext();
        ctx.percepts.inventory.add(dev.luizloyola.anima.core.inv.ItemStack.of("minecraft:oak_log", 8, 64));
        ctx.knowledge().note(tree(10, 64, 0, "minecraft:oak_log", 0), 0);
        ctx.knowledge().avoid(Pois.TREE, new Pos(10, 64, 0), 1000);

        assertFalse(new ChopForLogs(Stock.LOGS).applicable(ctx),
                "a tree she gave up on stays given up until the avoidance lapses");
    }

    @Test
    void aRequestForOakWalksPastABirch() {
        // Funded so the species clause is the ONLY reason this refuses — an empty pack would
        // refuse this same tree on affordability alone and hide the thing under test.
        FakeContext ctx = new FakeContext();
        ctx.percepts.inventory.add(dev.luizloyola.anima.core.inv.ItemStack.of("minecraft:birch_log", 8, 64));
        ctx.knowledge().note(tree(4, 64, 0, "minecraft:birch_log", 0), 64);
        ChopForLogs chop = new ChopForLogs(ItemSpec.anyOf(Set.of("minecraft:oak_log")));

        assertFalse(chop.applicable(ctx),
                "a birch is not oak, and felling it would never satisfy the goal");
    }

    @Test
    void aRequestForAnyLogTakesWhateverIsThere() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.inventory.add(dev.luizloyola.anima.core.inv.ItemStack.of("minecraft:birch_log", 8, 64));
        ctx.knowledge().note(tree(4, 64, 0, "minecraft:birch_log", 0), 64);

        assertTrue(new ChopForLogs(Stock.LOGS).applicable(ctx),
                "breadth is a property of the spec the job carries — no flag anywhere");
    }

    @Test
    void aTreeNobodyNamedSatisfiesNothing() {
        // Funded like its two siblings above, so the species clause is the ONLY reason this
        // refuses — an unfunded pack would refuse on affordability alone and the species clause
        // could vanish without this test ever noticing.
        FakeContext ctx = new FakeContext();
        ctx.percepts.inventory.add(dev.luizloyola.anima.core.inv.ItemStack.of("minecraft:birch_log", 8, 64));
        ctx.knowledge().note(tree(4, 64, 0, "", 0), 64);

        assertFalse(new ChopForLogs(Stock.LOGS).applicable(ctx),
                "an unknown species is not a licence to guess");
    }

    /**
     * Every other test in this file hand-builds its {@link PoiMemory} through {@link #tree} and
     * never touches a probe — so none of them would notice a break anywhere in scan ->
     * {@code Evaluation.detail} -> {@code PoiMemory.detail} -> {@link #nearestFreeTree}. This one
     * grows a real birch through {@link TreeRule} and {@link RegionGrowth}, notes it the way
     * perception actually would, and only then asks the chop for it.
     */
    @Test
    void aBirchGrownThroughTheRealMachineryStillWalksPastAnOakRequest() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(10, 10);
        for (int y = 64; y <= 67; y++) {
            probe.setId(10, y, 10, "minecraft:birch_log");
        }
        Pos seed = new Pos(10, 68, 10);
        RegionGrowth growth =
                new RegionGrowth(TreeRule.INSTANCE, seed, BlockKind.LEAVES, TestSpecies.PROFILE);
        int guard = 0;
        while (!growth.isDone()) {
            growth.step(probe, 10_000);
            assertTrue(++guard < 10_000, "growth never finished");
        }
        GrownRegion region = growth.result();
        assertEquals(1, region.parts().size(), "one trunk, one tree");
        GrownRegion.Part part = region.parts().get(0);

        FakeContext ctx = new FakeContext();
        // Funded so affordability is never what decides this — the same reason the two tests
        // above fund 8 logs against an otherwise-empty pack.
        ctx.percepts.inventory.add(
                dev.luizloyola.anima.core.inv.ItemStack.of("minecraft:birch_log", 8, 64));
        ctx.knowledge().note(region.toMemory(part, seed, 0), 8);

        assertTrue(new ChopForLogs(Stock.LOGS).applicable(ctx),
                "any log accepts the birch the scan actually found");
        assertFalse(new ChopForLogs(ItemSpec.anyOf(Set.of("minecraft:oak_log"))).applicable(ctx),
                "asked for oak, a real birch must refuse forever, not get felled for it");
    }

    @Test
    void aBirchLogFundsAPillarUnderAnOak() {
        // The affordability budget is species-BLIND on purpose: a birch log is a fine rung in a
        // pillar under an oak. Funded with birch only, asked for oak only — this pins that
        // `carried` stays keyed to Stock.LOGS and never narrows to `wanted`; narrowing it once
        // left a Person with an empty pack unable to accept any tree at all.
        FakeContext ctx = new FakeContext();
        ctx.percepts.inventory.add(dev.luizloyola.anima.core.inv.ItemStack.of("minecraft:birch_log", 8, 64));
        ctx.knowledge().note(tree(4, 64, 0, "minecraft:oak_log", 0), 64);

        assertTrue(new ChopForLogs(ItemSpec.anyOf(Set.of("minecraft:oak_log"))).applicable(ctx),
                "a birch log is a perfectly good rung in a pillar under an oak");
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

    @Test
    void aProducerIsReachedByWhatItMakes() {
        // The gate `board post gather` refuses on. Producers is keyed by ItemSpec IDENTITY and the
        // command builds a fresh anyOf spec for the item named, so asking whether THAT spec is a
        // registered key refuses every gather ever posted — including this one, for logs, which
        // ChopForLogs plainly knows how to make.
        Producers.register(Stock.LOGS, ChopForLogs::new);

        assertTrue(Producers.knowsAnyOf(Set.of("minecraft:oak_log")),
                "somebody here fells trees, whatever spec object the caller is holding");
        assertFalse(Producers.knows(ItemSpec.anyOf(Set.of("minecraft:oak_log"))),
                "and by identity they do not — the trap this gate was drafted with");
        assertFalse(Producers.knowsAnyOf(Set.of("minecraft:diamond")),
                "a settlement that cannot make a thing must refuse the job, not look busy on it");
    }
}
