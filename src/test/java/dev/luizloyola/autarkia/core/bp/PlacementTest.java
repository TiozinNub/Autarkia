package dev.luizloyola.autarkia.core.bp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.autarkia.core.bp.Blueprint.Facing;
import org.junit.jupiter.api.Test;

/** The eight placements, and the anchor at the footprint's centre. */
class PlacementTest {

    private static final int W = 3;
    private static final int D = 5;

    @Test
    void theNorthEdgeFacingEastIsAQuarterTurnClockwise() {
        Placement east = new Placement(Facing.EAST, false);
        assertEquals(5, east.width(W, D));
        assertEquals(3, east.depth(W, D));
        // The drawing's north-west corner becomes the north-east one; its south-west, the north-west.
        assertArrayEquals(new int[] {4, 0}, east.cell(0, 0, W, D));
        assertArrayEquals(new int[] {0, 0}, east.cell(0, 4, W, D));
        assertEquals(Facing.EAST, east.facing(Facing.NORTH));
        assertEquals(Facing.WEST, east.facing(Facing.SOUTH));
    }

    @Test
    void fourQuarterTurnsAndTwoMirrorsChangeNothing() {
        Placement quarter = new Placement(Facing.EAST, false);
        Placement mirror = new Placement(Facing.NORTH, true);
        for (int z = 0; z < D; z++) {
            for (int x = 0; x < W; x++) {
                int[] cell = {x, z};
                int width = W;
                int depth = D;
                for (int i = 0; i < 4; i++) {
                    cell = quarter.cell(cell[0], cell[1], width, depth);
                    int swap = width;
                    width = depth;
                    depth = swap;
                }
                assertArrayEquals(new int[] {x, z}, cell);
                int[] once = mirror.cell(x, z, W, D);
                assertArrayEquals(new int[] {x, z}, mirror.cell(once[0], once[1], W, D));
            }
        }
    }

    /** A step between two cells, placed, is the step's direction placed — for all eight. */
    @Test
    void cellsAndFacingsTurnTogether() {
        for (Facing north : Facing.values()) {
            for (boolean flip : new boolean[] {false, true}) {
                Placement placement = new Placement(north, flip);
                for (Facing step : Facing.values()) {
                    int[] from = placement.cell(1, 2, W, D);
                    int[] to = placement.cell(1 + step.dx, 2 + step.dz, W, D);
                    Facing placed = placement.facing(step);
                    assertEquals(placed.dx, to[0] - from[0], placement + " " + step);
                    assertEquals(placed.dz, to[1] - from[1], placement + " " + step);
                }
            }
        }
    }

    @Test
    void theAnchorIsTheMiddleCellRoundedNorthWest() {
        assertEquals(-2, Placement.offset(0, 5));
        assertEquals(0, Placement.offset(2, 5));
        assertEquals(2, Placement.offset(4, 5));
        assertEquals(-1, Placement.offset(0, 4));
        assertEquals(2, Placement.offset(3, 4));
        assertEquals(0, Placement.offset(0, 1));
    }
}
