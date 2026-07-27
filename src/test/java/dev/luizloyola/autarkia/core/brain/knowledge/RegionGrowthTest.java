package dev.luizloyola.autarkia.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.autarkia.core.brain.sense.Pos;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    private static GrownRegion.Part only(GrownRegion region) {
        assertEquals(1, region.parts().size(), "expected exactly one thing in this mass");
        return region.parts().get(0);
    }

    @Test
    void anOakGrowsIntoAnAcceptedTree() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(10, 10);
        Pos seed = new Pos(10, 68, 10);
        GrownRegion region = grow(new RegionGrowth(TreeRule.INSTANCE, seed, BlockKind.LEAVES),
                probe, 10_000);

        assertTrue(region.accepted());
        assertEquals(PoiKind.TREE, region.kind());
        GrownRegion.Part tree = only(region);
        assertEquals(new Pos(10, 64, 10), tree.anchor(), "the trunk base — where the axe goes");
        assertEquals(4, tree.units(), "4 logs");
        assertFalse(region.partial());
        assertEquals(21, region.blocks().size(), "4 logs + 17 leaves");
        assertEquals(21, tree.blocks().size(), "and the lone tree owns every one of them");
        assertTrue(tree.bounds().contains(new Pos(9, 67, 9)));
        assertTrue(tree.bounds().contains(new Pos(11, 68, 11)));
    }

    /**
     * Individuation lives in the RULE, not only in the chopper: worldgen fuses canopies, and a
     * grove held as one memory forgets every tree when one is felled. One scan, one mass —
     * several trees, each with its own anchor.
     */
    @Test
    void aFusedPairIsRememberedAsTwoTrees() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(10, 10);
        probe.placeOak(12, 10); // canopies overlap along x = 11: one connected mass
        GrownRegion region = grow(
                new RegionGrowth(TreeRule.INSTANCE, new Pos(10, 68, 10), BlockKind.LEAVES),
                probe, 10_000);

        assertEquals(2, region.parts().size(), "two trunks, two trees, two memories");
        List<Pos> anchors = region.parts().stream().map(GrownRegion.Part::anchor).toList();
        assertTrue(anchors.contains(new Pos(10, 64, 10)), "anchors: " + anchors);
        assertTrue(anchors.contains(new Pos(12, 64, 10)), "anchors: " + anchors);
        for (GrownRegion.Part tree : region.parts()) {
            assertEquals(4, tree.units(), "each keeps its own four logs, not the grove's eight");
        }
        Set<Pos> owned = new HashSet<>();
        int counted = 0;
        for (GrownRegion.Part tree : region.parts()) {
            owned.addAll(tree.blocks().keySet());
            counted += tree.blocks().size();
        }
        assertEquals(counted, owned.size(), "shares are disjoint — no cell claimed twice");
        assertEquals(region.blocks().size(), owned.size(),
                "and between them they account for the whole mass, shared canopy included");
    }

    /**
     * A stump left standing inside a live grove is connected and grounded, and still not a tree:
     * it has no crown. Ruled: do not detect stumps; do not let the chop leave them.
     */
    @Test
    void aCrownlessStumpInsideAGroveIsNotATreeOfItsOwn() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(10, 10);
        probe.set(11, 65, 10, BlockKind.LOG); // a low branch, corner-hung off the trunk
        probe.set(12, 64, 10, BlockKind.LOG); // and a bare stump it reaches down to
        GrownRegion region = grow(
                new RegionGrowth(TreeRule.INSTANCE, new Pos(10, 68, 10), BlockKind.LEAVES),
                probe, 10_000);

        GrownRegion.Part tree = only(region);
        assertEquals(new Pos(10, 64, 10), tree.anchor());
        assertEquals(5, tree.units(), "the oak and its branch — the stump is nobody's");
        assertFalse(tree.blocks().containsKey(new Pos(12, 64, 10)),
                "the stump belongs to no tree, so the sensor claims it negatively and moves on");
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
        assertEquals(only(unsliced).anchor(), only(sliced.result()).anchor());
        assertEquals(only(unsliced).units(), only(sliced.result()).units());
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
        assertEquals(3, region.blocks().size(), "still claimed, so they won't re-investigate");
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
        assertEquals(25, only(region).units(), "the whole 5×5 surface sheet");
        assertEquals(seed, only(region).anchor(), "nearest cell to where they noticed it");
        assertFalse(region.partial());
    }

    /**
     * Worldgen hangs branches off a trunk at a CORNER, and a face-only walk loses them silently:
     * on a saved fancy oak it reached 24 of 28 logs, and the 4 it missed were the 4 the chopper
     * left standing forever.
     */
    @Test
    void aBranchTouchingOnlyAtACornerIsStillPartOfTheTree() {
        FakeProbe probe = new FakeProbe();
        int y = FakeProbe.GROUND_Y + 1;
        for (int i = 0; i < 4; i++) {
            probe.set(0, y + i, 0, BlockKind.LOG); // the trunk
        }
        probe.set(0, y + 4, 0, BlockKind.LEAVES); // a sunlit leaf, so the mass reads as a tree
        // A branch whose only contact with the trunk is the corner (1, y+2, 1) -> (0, y+1, 0):
        // it shares no face with anything already in the mass.
        probe.set(1, y + 2, 1, BlockKind.LOG);
        probe.set(2, y + 2, 2, BlockKind.LOG);

        GrownRegion region = grow(
                new RegionGrowth(TreeRule.INSTANCE, new Pos(0, y, 0), BlockKind.LOG), probe, 10_000);

        assertTrue(region.accepted());
        assertEquals(6, only(region).units(),
                "all six logs, corner-hung branch included — a face-only walk finds only four");
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
        assertTrue(only(region).units() <= RegionGrowth.maxSpread() + 1,
                "only the reach within the cap: " + only(region).units());
    }
}
