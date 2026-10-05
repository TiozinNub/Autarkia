package dev.luizloyola.autarkia.mod.debug;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import dev.luizloyola.anima.core.brain.knowledge.CoverageGrid;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.SurveyArea;
import dev.luizloyola.autarkia.core.tree.Pois;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The board view shades a sweep's cell known exactly when the sweep counts it known. */
class BoardViewerTest {

    @Test
    void aCellAWalkSettledIsDrawnKnown() {
        Region box = new Region(new Pos(0, 64, 0), new Pos(3 * CoverageGrid.CELL - 1, 64, CoverageGrid.CELL - 1));
        int settled = (1 << CoverageGrid.WALKED_SQUARES) - 1;
        int half = (1 << (CoverageGrid.SUB * CoverageGrid.SUB / 2)) - 1;
        SurveyArea sweep = new SurveyArea(box, Pois.TREE, Map.of(
                new Pos(0, 64, 0), settled,
                new Pos(CoverageGrid.CELL, 64, 0), half));

        assertArrayEquals(new int[] {BoardViewer.COVER_KNOWN, BoardViewer.COVER_PARTIAL, BoardViewer.COVER_UNKNOWN},
                BoardViewer.coverColours(sweep));
    }
}
