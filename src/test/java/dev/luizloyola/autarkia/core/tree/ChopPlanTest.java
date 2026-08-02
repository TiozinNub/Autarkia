package dev.luizloyola.autarkia.core.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the chop's dance card — each pins one choreography rule from the
 * 2026-08-02 redesign, built on hand-made {@link TreeShape.Trunk}s (the compiler is a pure
 * function of the shape; no probe, no world).
 */
class ChopPlanTest {

    private static List<Pos> column(int x, int z, int fromY, int toY) {
        List<Pos> cells = new ArrayList<>();
        for (int y = fromY; y <= toY; y++) {
            cells.add(new Pos(x, y, z));
        }
        return cells;
    }

    @Test
    void aBareColumnIsMastOnlyUpThenStraightDown() {
        // A birch: trunk and crown only — the whole dance is the elevator, the descent an
        // implicit mine-below to the ground.
        TreeShape.Trunk birch = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 66),
                List.of(), List.of(new Pos(0, 67, 0), new Pos(1, 66, 0)));

        ChopPlan plan = ChopPlan.of(birch);

        assertEquals(new Pos(0, 60, 0), plan.entry());
        assertEquals(7, plan.mast().size());
        assertTrue(plan.layers().isEmpty());
        assertTrue(plan.refusals().isEmpty());
        assertEquals(0, plan.chopCount());
    }

    @Test
    void aGiantsOtherColumnsArePointBlankFromTheMast() {
        // A 2x2 spruce: one column is the elevator, the other three are targets at every
        // level — each within arm's reach of the shaft, so no move ever needs a dig, and the
        // layers run strictly top-down.
        List<Pos> base = List.of(new Pos(0, 60, 0), new Pos(1, 60, 0),
                new Pos(0, 60, 1), new Pos(1, 60, 1));
        List<Pos> columns = new ArrayList<>();
        columns.addAll(column(0, 0, 61, 70));
        columns.addAll(column(1, 0, 61, 70));
        columns.addAll(column(0, 1, 61, 70));
        columns.addAll(column(1, 1, 61, 70));
        TreeShape.Trunk giant = new TreeShape.Trunk(base, columns, List.of(),
                List.of(new Pos(0, 71, 0)));

        ChopPlan plan = ChopPlan.of(giant);

        assertEquals(11, plan.mast().size(), "the (0,·,0) column plus its base cell");
        assertTrue(plan.refusals().isEmpty());
        assertEquals(3 + 3 * 10, plan.chopCount(), "three sibling bases, three ten-log columns");
        assertEquals(0, plan.digCount(), "a giant's own footprint never needs a tunnel");
        int lastY = Integer.MAX_VALUE;
        for (ChopPlan.Layer layer : plan.layers()) {
            assertTrue(layer.y() < lastY, "layers descend");
            lastY = layer.y();
            for (ChopPlan.Move move : layer.moves()) {
                assertEquals(new Pos(0, layer.y(), 0), move.stand(),
                        "every swing comes from atop the mast");
            }
        }
    }

    @Test
    void aBranchInTheCanopyIsReachedByDiggingAndNearerWoodFallsEnRoute() {
        // Two branch logs on one ray at the same level. Furthest-first digs one tunnel to the
        // far one; the near one is broken en route ("freeing space as needed") and must appear
        // as that tunnel's dig, not as a second move.
        TreeShape.Trunk oak = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 65),
                List.of(new Pos(2, 64, 0), new Pos(4, 64, 0)),
                List.of(new Pos(1, 64, 0), new Pos(3, 64, 0),
                        new Pos(1, 63, 0), new Pos(2, 63, 0), new Pos(3, 63, 0)));

        ChopPlan plan = ChopPlan.of(oak);

        assertTrue(plan.refusals().isEmpty());
        assertEquals(1, plan.layers().size());
        ChopPlan.Layer layer = plan.layers().get(0);
        assertEquals(64, layer.y());
        assertEquals(1, layer.moves().size(), "the near log rides the far log's tunnel");
        ChopPlan.Move move = layer.moves().get(0);
        assertEquals(new Pos(4, 64, 0), move.target(), "outermost first");
        assertEquals(new Pos(3, 64, 0), move.stand());
        assertTrue(move.digs().contains(new Pos(2, 64, 0)), "the near log is dug en route");
        assertTrue(move.digs().contains(new Pos(1, 64, 0)), "the access leaf is dug");
        assertFalse(move.leap());
    }

    @Test
    void aOneCellFloorHoleIsALeapATwoCellHoleRefuses() {
        // The dig rule's single allowance: one missing floor cell is a jump, two is no route.
        // Same tree twice, one floor leaf apart.
        List<Pos> floorWithHole = List.of(new Pos(1, 63, 0), new Pos(3, 63, 0));
        TreeShape.Trunk leapable = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 65),
                List.of(new Pos(4, 64, 0)), floorWithHole);

        ChopPlan plan = ChopPlan.of(leapable);

        assertTrue(plan.refusals().isEmpty());
        ChopPlan.Move move = plan.layers().get(0).moves().get(0);
        assertTrue(move.leap(), "one hole in the canopy floor is a jump");
        assertEquals(new Pos(3, 64, 0), move.stand());

        TreeShape.Trunk gapped = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 65),
                List.of(new Pos(4, 64, 0)), List.of(new Pos(1, 63, 0)));

        ChopPlan refused = ChopPlan.of(gapped);

        assertTrue(refused.layers().isEmpty());
        assertEquals(1, refused.refusals().size());
        assertEquals(ChopPlan.Reason.NO_FLOOR, refused.refusals().get(0).reason());
        assertEquals(new Pos(4, 64, 0), refused.refusals().get(0).cell());
    }

    @Test
    void woodTheMastCannotServeRefusesAsTooHigh() {
        // The acacia signature: a stubby vertical column, wood climbing far above it. The plan
        // does not improvise a way up — it refuses at compile time, painted.
        TreeShape.Trunk acacia = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 62),
                List.of(new Pos(1, 63, 1), new Pos(2, 66, 2)),
                List.of(new Pos(2, 67, 2)));

        ChopPlan plan = ChopPlan.of(acacia);

        assertEquals(1, plan.refusals().size());
        ChopPlan.Refusal refusal = plan.refusals().get(0);
        assertEquals(new Pos(2, 66, 2), refusal.cell());
        assertEquals(ChopPlan.Reason.TOO_HIGH, refusal.reason());
        assertEquals(1, plan.chopCount(), "the reachable diagonal step is still planned");
    }

    @Test
    void lowBranchesBelowTheBaseAreGroundWork() {
        // A downhill branch resting below the base level: feet stay at ground, floor is the
        // terrain, and the swing reaches down — never a refusal, never a tunnel.
        TreeShape.Trunk downhill = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 64),
                List.of(new Pos(2, 59, 0)), List.of(new Pos(0, 65, 0)));

        ChopPlan plan = ChopPlan.of(downhill);

        assertTrue(plan.refusals().isEmpty());
        assertEquals(1, plan.chopCount());
        ChopPlan.Layer layer = plan.layers().get(0);
        assertEquals(60, layer.y(), "feet never plan below ground level");
    }

    @Test
    void theSameShapeAlwaysCompilesTheSameDance() {
        // The plan is a memory-identity-grade artifact: replans (resume-after-a-fall recompiles
        // from the remnant) must reproduce the interrupted card exactly, so nothing about it
        // may depend on set order or iteration accident.
        TreeShape.Trunk tree = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 65),
                List.of(new Pos(2, 64, 0), new Pos(-2, 64, 0), new Pos(0, 63, 2)),
                List.of(new Pos(1, 64, 0), new Pos(-1, 64, 0), new Pos(0, 63, 1),
                        new Pos(1, 63, 0), new Pos(-1, 63, 0), new Pos(0, 62, 1)));

        assertEquals(ChopPlan.of(tree), ChopPlan.of(tree));
    }
}
