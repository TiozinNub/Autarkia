package dev.luizloyola.autarkia.core.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the split's reconciliation — that every input log ends up either assigned
 * or explicitly accounted for, since the unaccounted ones are where the individuation bugs hid.
 */
class SplitReportTest {

    private final FakeProbe probe = new FakeProbe();
    private final Map<Pos, BlockKind> mass = new LinkedHashMap<>();

    @Test
    void aFusedPairComesBackAsTwoWholeTrees() {
        probe.placeOak(0, 0);
        probe.placeOak(3, 0);
        TreeMassesTest.oakInto(mass, 0, 0);
        TreeMassesTest.oakInto(mass, 3, 0);

        SplitReport report = SplitReport.of(mass, probe);

        assertFalse(report.treeless());
        assertEquals(2, report.trees().size());
        assertTrue(report.strayLogs().isEmpty(), "every log found its tree");
        for (TreeShape.Trunk tree : report.trees()) {
            assertEquals(4, tree.logCount(), "each oak keeps its own 4 logs");
            assertEquals(17, tree.leaves().size(), "each oak keeps its own 17 leaves");
        }
    }

    @Test
    void floatingWoodIsUngroundedNotForeign() {
        for (int y = 70; y <= 72; y++) {
            probe.set(0, y, 0, BlockKind.LOG);
            mass.put(new Pos(0, y, 0), BlockKind.LOG);
        }

        SplitReport report = SplitReport.of(mass, probe);

        assertTrue(report.treeless(), "no grounded base: the mass splits into nothing");
        assertTrue(report.trees().isEmpty());
        assertTrue(report.strayLogs().isEmpty(),
                "with no trees there is no owner to be foreign to — the mass IS the finding");
    }

    @Test
    void aTrunkTheScanCutInHalfIsForeignNotABranch() {
        probe.placeOak(0, 0);
        TreeMassesTest.oakInto(mass, 0, 0);
        // A neighbour's trunk stands fully in the WORLD (probe), but the scan only caught its
        // top half — the exact shape of the half-scanned-neighbour bug (the lone-stump factory).
        for (int y = 64; y <= 67; y++) {
            probe.set(5, y, 0, BlockKind.LOG);
        }
        mass.put(new Pos(5, 66, 0), BlockKind.LOG);
        mass.put(new Pos(5, 67, 0), BlockKind.LOG);

        SplitReport report = SplitReport.of(mass, probe);

        assertEquals(1, report.trees().size(), "only the fully scanned oak is a tree here");
        assertEquals(4, report.trees().get(0).logCount(),
                "the neighbour's logs were never adopted as branches");
        assertEquals(2, report.strayLogs().size(), "both cut-off logs are accounted for");
        assertTrue(report.strayLogs().contains(new Pos(5, 66, 0)));
        assertTrue(report.strayLogs().contains(new Pos(5, 67, 0)));
    }

    @Test
    void leavesReachableOnlyThroughAForeignTrunkAreStray() {
        probe.placeOak(0, 0);
        TreeMassesTest.oakInto(mass, 0, 0);
        // Foreign wood is opaque to the ownership wave, so the cut neighbour's leaves are
        // unreachable — they belong to whatever owns that unscanned trunk, not to the oak that
        // happens to be closest.
        for (int y = 64; y <= 67; y++) {
            probe.set(5, y, 0, BlockKind.LOG);
        }
        mass.put(new Pos(5, 66, 0), BlockKind.LOG);
        mass.put(new Pos(5, 67, 0), BlockKind.LOG);
        mass.put(new Pos(5, 68, 0), BlockKind.LEAVES);
        mass.put(new Pos(4, 68, 0), BlockKind.LEAVES);

        SplitReport report = SplitReport.of(mass, probe);

        assertEquals(1, report.trees().size());
        assertEquals(17, report.trees().get(0).leaves().size(),
                "the oak keeps exactly its own crown");
        assertEquals(2, report.strayLeaves().size(), "the foreign canopy is accounted for");
        assertTrue(report.strayLeaves().contains(new Pos(5, 68, 0)));
        assertTrue(report.strayLeaves().contains(new Pos(4, 68, 0)));
    }
}
