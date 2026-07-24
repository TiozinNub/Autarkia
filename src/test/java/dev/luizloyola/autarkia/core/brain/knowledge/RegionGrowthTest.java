package dev.luizloyola.autarkia.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import org.junit.jupiter.api.Test;

/** The incremental grower: whole structures, budget resumption, caps, acceptance criteria. */
class RegionGrowthTest {

    private static GrownRegion grow(RegionGrowth growth, FakeProbe probe, int perStep) {
        int guard = 0;
        while (!growth.isDone()) {
            int used = growth.step(probe, perStep);
            assertTrue(used <= perStep, "step overspent its budget: " + used);
            assertTrue(++guard < 10_000, "growth never finished");
        }
        return growth.result();
    }

    @Test
    void anOakGrowsIntoAnAcceptedGrove() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(10, 10);
        Pos seed = new Pos(10, 68, 10);
        GrownRegion region = grow(new RegionGrowth(TreeRule.INSTANCE, seed, BlockKind.LEAVES),
                probe, 10_000);

        assertTrue(region.accepted());
        assertEquals(PoiKind.TREE, region.kind());
        assertEquals(new Pos(10, 64, 10), region.anchor(), "the trunk base — where the axe goes");
        assertEquals(4, region.units(), "4 logs");
        assertFalse(region.partial());
        assertEquals(21, region.blocks().size(), "4 logs + 17 leaves");
        assertTrue(region.bounds().contains(new Pos(9, 67, 9)));
        assertTrue(region.bounds().contains(new Pos(11, 68, 11)));
    }

    @Test
    void aTinyBudgetResumesAcrossStepsToTheSameResult() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(10, 10);
        Pos seed = new Pos(10, 68, 10);

        RegionGrowth sliced = new RegionGrowth(TreeRule.INSTANCE, seed, BlockKind.LEAVES);
        int steps = 0;
        while (!sliced.isDone()) {
            sliced.step(probe, 7);
            steps++;
        }
        assertTrue(steps > 5, "an oak cannot finish in one 7-read slice");

        FakeProbe fresh = new FakeProbe();
        fresh.placeOak(10, 10);
        GrownRegion unsliced = grow(new RegionGrowth(TreeRule.INSTANCE, seed, BlockKind.LEAVES),
                fresh, 10_000);
        assertEquals(unsliced.anchor(), sliced.result().anchor());
        assertEquals(unsliced.units(), sliced.result().units());
        assertEquals(unsliced.blocks().size(), sliced.result().blocks().size());
    }

    @Test
    void floatingWoodIsNotATree() {
        FakeProbe probe = new FakeProbe();
        probe.set(5, 66, 5, BlockKind.LOG);   // hovering: air beneath
        probe.set(5, 67, 5, BlockKind.LOG);
        probe.set(5, 68, 5, BlockKind.LEAVES); // sunlit leaf and all — still not a tree
        GrownRegion region = grow(
                new RegionGrowth(TreeRule.INSTANCE, new Pos(5, 68, 5), BlockKind.LEAVES),
                probe, 10_000);

        assertFalse(region.accepted(),
                "no grounded log in the blob: a chopped-out remnant is scenery, never a memory");
    }

    @Test
    void aWoodpileIsNotATree() {
        FakeProbe probe = new FakeProbe();
        for (int y = 64; y <= 66; y++) {
            probe.set(5, y, 5, BlockKind.LOG);
        }
        GrownRegion region = grow(
                new RegionGrowth(TreeRule.INSTANCE, new Pos(5, 66, 5), BlockKind.LOG),
                probe, 10_000);

        assertFalse(region.accepted(), "logs with no sunlit leaf: a woodpile");
        assertEquals(3, region.blocks().size(), "still claimed, so she won't re-investigate");
    }

    @Test
    void anUnloadedBorderMarksTheGrovePartial() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(10, 10);
        probe.markUnloaded(12, 10);
        GrownRegion region = grow(
                new RegionGrowth(TreeRule.INSTANCE, new Pos(10, 68, 10), BlockKind.LEAVES),
                probe, 10_000);

        assertTrue(region.accepted());
        assertTrue(region.partial(), "the canopy touched an unloaded chunk: there may be more");
    }

    @Test
    void aPondGrowsIntoWater() {
        FakeProbe probe = new FakeProbe();
        for (int x = 10; x <= 14; x++) {
            for (int z = 10; z <= 14; z++) {
                probe.set(x, FakeProbe.GROUND_Y, z, BlockKind.WATER);
            }
        }
        Pos seed = new Pos(12, FakeProbe.GROUND_Y, 12);
        GrownRegion region = grow(new RegionGrowth(WaterRule.INSTANCE, seed, BlockKind.WATER),
                probe, 10_000);

        assertTrue(region.accepted());
        assertEquals(PoiKind.WATER, region.kind());
        assertEquals(25, region.units(), "the whole 5×5 surface sheet");
        assertEquals(seed, region.anchor(), "nearest cell to where she noticed it");
        assertFalse(region.partial());
    }

    @Test
    void theSpreadCapTurnsALongRiverIntoAPartialReach() {
        FakeProbe probe = new FakeProbe();
        for (int x = 0; x < 60; x++) {
            probe.set(x, FakeProbe.GROUND_Y, 0, BlockKind.WATER);
        }
        GrownRegion region = grow(
                new RegionGrowth(WaterRule.INSTANCE, new Pos(0, FakeProbe.GROUND_Y, 0), BlockKind.WATER),
                probe, 10_000);

        assertTrue(region.accepted());
        assertTrue(region.partial(), "the river continues beyond the spread cap");
        assertTrue(region.units() <= RegionGrowth.MAX_SPREAD + 1,
                "only the reach within the cap: " + region.units());
    }
}
