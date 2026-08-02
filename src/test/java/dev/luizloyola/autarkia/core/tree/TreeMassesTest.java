package dev.luizloyola.autarkia.core.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Headless tests for the survey-side mass reconnection — the step before {@link TreeShape}. */
class TreeMassesTest {

    /** The {@code FakeProbe.placeOak} shape, built into a plain map: 4 logs, 17 leaves. */
    static void oakInto(Map<Pos, BlockKind> cells, int x, int z) {
        for (int y = 64; y <= 67; y++) {
            cells.put(new Pos(x, y, z), BlockKind.LOG);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    cells.put(new Pos(x + dx, 67, z + dz), BlockKind.LEAVES);
                }
                cells.put(new Pos(x + dx, 68, z + dz), BlockKind.LEAVES);
            }
        }
    }

    @Test
    void twoDistantOaksAreTwoMasses() {
        Map<Pos, BlockKind> cells = new LinkedHashMap<>();
        oakInto(cells, 0, 0);
        oakInto(cells, 10, 0);
        List<Map<Pos, BlockKind>> masses = TreeMasses.connect(cells);
        assertEquals(2, masses.size());
        assertEquals(21, masses.get(0).size(), "4 logs + 17 leaves each");
        assertEquals(21, masses.get(1).size());
    }

    @Test
    void touchingCanopiesFuseIntoOneMass() {
        Map<Pos, BlockKind> cells = new LinkedHashMap<>();
        oakInto(cells, 0, 0);
        oakInto(cells, 3, 0); // caps reach x=1 and x=2: adjacent, exactly how worldgen fuses
        List<Map<Pos, BlockKind>> masses = TreeMasses.connect(cells);
        assertEquals(1, masses.size());
        assertEquals(42, masses.get(0).size());
    }

    @Test
    void diagonalLogsConnect() {
        // Wood attaches to wood across corners — a real branch steps diagonally.
        Map<Pos, BlockKind> cells = new LinkedHashMap<>();
        cells.put(new Pos(0, 70, 0), BlockKind.LOG);
        cells.put(new Pos(1, 71, 1), BlockKind.LOG);
        assertEquals(1, TreeMasses.connect(cells).size());
    }

    @Test
    void diagonalLeavesDoNotConnect() {
        // A leaf attaches through its six faces only (vanilla's own leaf-distance rule), so
        // canopies interleaving corner-to-corner stay separate things; face contact fuses.
        Map<Pos, BlockKind> cells = new LinkedHashMap<>();
        cells.put(new Pos(0, 70, 0), BlockKind.LEAVES);
        cells.put(new Pos(1, 70, 1), BlockKind.LEAVES);
        assertEquals(2, TreeMasses.connect(cells).size());
        cells.put(new Pos(1, 70, 0), BlockKind.LEAVES); // faces both: one mass again
        assertEquals(1, TreeMasses.connect(cells).size());
    }

    @Test
    void insertionOrderNeverChangesTheAnswer() {
        Map<Pos, BlockKind> forward = new LinkedHashMap<>();
        oakInto(forward, 0, 0);
        oakInto(forward, 10, 0);
        Map<Pos, BlockKind> backward = new LinkedHashMap<>();
        oakInto(backward, 10, 0);
        oakInto(backward, 0, 0);
        assertEquals(TreeMasses.connect(forward), TreeMasses.connect(backward),
                "seeds are taken in cell order, not map order");
    }

    @Test
    void emptyScanIsNoMasses() {
        assertTrue(TreeMasses.connect(Map.of()).isEmpty());
    }
}
