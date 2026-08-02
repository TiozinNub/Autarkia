package dev.luizloyola.autarkia.core.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the split's connectivity rules — ownership grows by attachment, so a tree
 * can only be assigned cells it actually reaches, and leaves are what prove a tree at all.
 */
class TreeShapeTest {

    private final FakeProbe probe = new FakeProbe();
    private final Map<Pos, BlockKind> mass = new LinkedHashMap<>();

    private void log(int x, int y, int z) {
        probe.set(x, y, z, BlockKind.LOG);
        mass.put(new Pos(x, y, z), BlockKind.LOG);
    }

    private void leaf(int x, int y, int z) {
        probe.set(x, y, z, BlockKind.LEAVES);
        mass.put(new Pos(x, y, z), BlockKind.LEAVES);
    }

    @Test
    void aTallCanopyStaysWithTheTallTrunkHoweverCloseTheNeighbourIs() {
        // The overhang is horizontally NEARER the short trunk, so pure centroid assignment handed
        // it thirty cells of air. Connectivity may not.
        for (int y = 64; y <= 74; y++) {
            log(0, y, 0); // the tall trunk
        }
        leaf(0, 75, 0);
        leaf(1, 75, 0);
        leaf(2, 75, 0); // the overhang
        log(3, 64, 0); // the short bushy neighbour
        log(3, 65, 0);
        log(3, 66, 0);
        leaf(2, 66, 0);
        leaf(4, 66, 0);
        leaf(3, 66, 1);
        leaf(3, 66, -1);
        leaf(3, 67, 0);
        leaf(1, 66, 0); // the face-contact that fuses the two into one mass

        List<TreeShape.Trunk> trees = TreeShape.split(mass, probe);

        assertEquals(2, trees.size());
        TreeShape.Trunk tall = trees.get(0); // base (0,64,0) sorts first
        TreeShape.Trunk shortBushy = trees.get(1);
        assertEquals(new Pos(0, 64, 0), tall.base().get(0));
        assertTrue(tall.leaves().contains(new Pos(2, 75, 0)),
                "the overhang belongs to the trunk it hangs from");
        assertFalse(shortBushy.leaves().contains(new Pos(2, 75, 0)),
                "however near, the short tree never touches it");
        for (Pos leaf : shortBushy.leaves()) {
            assertTrue(leaf.y() <= 67, "everything the short tree owns, it reaches");
        }
    }

    @Test
    void aFallenLogIsAWoodpileNotATree() {
        // Lying flat, every log of the run is "grounded" — an N-wide base and nothing else.
        log(0, 64, 0);
        log(1, 64, 0);
        log(2, 64, 0);

        assertTrue(TreeShape.split(mass, probe).isEmpty(),
                "no crown, no tree — leaves are what prove one");
        assertTrue(SplitReport.of(mass, probe).treeless());
    }

    @Test
    void aFallenLogAgainstATrunkIsBranchesNeverBase() {
        // Touching a real tree, the fallen run is the tree's wood — but it must never read
        // as stump cells: the base is where saplings go back in.
        probe.placeOak(0, 0);
        TreeMassesTest.oakInto(mass, 0, 0);
        for (Pos cell : List.copyOf(mass.keySet())) {
            probe.set(cell.x(), cell.y(), cell.z(), mass.get(cell));
        }
        log(1, 64, 0);
        log(2, 64, 0);

        List<TreeShape.Trunk> trees = TreeShape.split(mass, probe);

        assertEquals(1, trees.size());
        assertEquals(List.of(new Pos(0, 64, 0)), trees.get(0).base(),
                "the stump layer is exactly the foot of the column");
        assertTrue(trees.get(0).branches().contains(new Pos(1, 64, 0)));
        assertTrue(trees.get(0).branches().contains(new Pos(2, 64, 0)));
    }

    @Test
    void aFallenLogUnderALowCanopyIsStillNotBase() {
        // The leak the first fallen-log rule left open: a leaf hanging directly over the run
        // made its cell read as "the foot of something vertical". Base means wood overhead.
        probe.placeOak(0, 0);
        TreeMassesTest.oakInto(mass, 0, 0);
        for (Pos cell : List.copyOf(mass.keySet())) {
            probe.set(cell.x(), cell.y(), cell.z(), mass.get(cell));
        }
        log(1, 64, 0);
        log(2, 64, 0);
        leaf(2, 65, 0); // the low canopy drooping over the fallen run

        List<TreeShape.Trunk> trees = TreeShape.split(mass, probe);

        assertEquals(1, trees.size());
        assertEquals(List.of(new Pos(0, 64, 0)), trees.get(0).base(),
                "a leaf overhead does not make a fallen log a stump");
        assertTrue(trees.get(0).branches().contains(new Pos(2, 64, 0)));
    }

    @Test
    void aJungleBushIsATreeOfItsOwn() {
        // The bush shape from the jungle floor: one log crowned directly in leaves, no column at
        // all. It stands lateral-alone. That is what tells it from one cell of a fallen run.
        log(0, 64, 0);
        leaf(0, 65, 0);
        leaf(1, 65, 0);
        leaf(-1, 65, 0);
        leaf(1, 64, 0);

        List<TreeShape.Trunk> trees = TreeShape.split(mass, probe);

        assertEquals(1, trees.size());
        assertEquals(1, trees.get(0).logCount());
        assertEquals(4, trees.get(0).leaves().size(), "the whole blob is its crown");
    }

    @Test
    void aFallenRunUnderALowCanopyIsStillNoBush() {
        // Two grounded column-less logs side by side disqualify each other: the bush rule
        // never resurrects the fallen run, leaves overhead or not.
        log(0, 64, 0);
        log(1, 64, 0);
        leaf(0, 65, 0);
        leaf(1, 65, 0);

        assertTrue(TreeShape.split(mass, probe).isEmpty(),
                "a run is a run, however green its blanket");
    }

    @Test
    void attachmentIsSixWayForLeavesAndTwentySixWayForWood() {
        assertTrue(TreeShape.attached(BlockKind.LOG, BlockKind.LOG, 1, 1, 1),
                "a branch steps diagonally");
        assertTrue(TreeShape.attached(BlockKind.LEAVES, BlockKind.LOG, 0, -1, 0),
                "a leaf hangs by its faces");
        assertFalse(TreeShape.attached(BlockKind.LEAVES, BlockKind.LEAVES, 1, 1, 0),
                "corner-touching canopies never bleed");
        assertFalse(TreeShape.attached(BlockKind.LOG, BlockKind.LEAVES, 1, 0, 1),
                "wood cannot reach a leaf it merely brushes either");
    }
}
