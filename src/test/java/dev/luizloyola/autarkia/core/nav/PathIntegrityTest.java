package dev.luizloyola.autarkia.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The completion-critical cells {@link PathIntegrity} watches ahead of a walking Person: the floor
 * and body along the deck the feet cross on a level edge, a swim waypoint's surface-float condition,
 * a vertical move's destination standability. This is the contract the follower's integrity check
 * re-validates a few nodes ahead — see {@code Navigator.pathChangedAhead}.
 */
class PathIntegrityTest {

    private static final AgentProfile PERSON = AgentProfile.PERSON; // 2 cells tall

    @Test
    void unitWalkNeedsFloorGroundAndBodyClearanceAtBothCells() {
        // A one-cell step: the line is [from, to], so both cells' floor+body are watched.
        List<CellNeed> needs = PathIntegrity.edgeNeeds(
                new Waypoint(3, 10, 7, MoveType.WALK), new Waypoint(4, 10, 7, MoveType.WALK), PERSON);
        assertEquals(List.of(
                new CellNeed(3, 9, 7, CellType.GROUND),
                new CellNeed(3, 10, 7, CellType.PASSABLE),
                new CellNeed(3, 11, 7, CellType.PASSABLE),
                new CellNeed(4, 9, 7, CellType.GROUND),
                new CellNeed(4, 10, 7, CellType.PASSABLE),
                new CellNeed(4, 11, 7, CellType.PASSABLE)), needs);
    }

    @Test
    void strideWatchesEveryDeckCellBetweenTheWaypoints() {
        // Every cell a stride crosses is watched, not just the endpoints destination-only
        // watching saw — so a block pulled from a bridge's middle is caught.
        List<CellNeed> needs = PathIntegrity.edgeNeeds(
                new Waypoint(0, 5, 0, MoveType.WALK), new Waypoint(3, 5, 0, MoveType.WALK), PERSON);
        for (int x = 0; x <= 3; x++) {
            assertTrue(needs.contains(new CellNeed(x, 4, 0, CellType.GROUND)),
                    "floor under x=" + x + " must be watched across the stride");
        }
    }

    @Test
    void lineCoversEveryCellCrossedOnAShallowStride() {
        // A (3,1) stride: Bresenham crosses one cell per column — the deck under the feet.
        List<int[]> cells = PathIntegrity.lineCells(0, 0, 3, 1);
        assertEquals(4, cells.size());
        assertEquals(0, cells.get(0)[0]);            
        assertEquals(3, cells.get(cells.size() - 1)[0]); 
        assertTrue(cells.stream().allMatch(c -> c[1] == 0 || c[1] == 1),
                "every crossed cell sits in the z-band the segment spans");
    }

    @Test
    void swimEdgeNeedsWaterFeetAndClearBodyAbove() {
        List<CellNeed> needs = PathIntegrity.edgeNeeds(
                new Waypoint(1, 63, 8, MoveType.SWIM), new Waypoint(2, 63, 8, MoveType.SWIM), PERSON);
        assertEquals(List.of(
                new CellNeed(2, 63, 8, CellType.WATER),
                new CellNeed(2, 64, 8, CellType.PASSABLE)), needs);
    }

    @Test
    void dropAndJumpWatchTheDestinationOnly() {
        // Drop/jump: only the landing's standability — the shaft/cleared block is v1-out-of-scope.
        for (MoveType move : List.of(MoveType.DROP, MoveType.JUMP)) {
            List<CellNeed> needs = PathIntegrity.edgeNeeds(
                    new Waypoint(0, 8, 0, MoveType.WALK), new Waypoint(2, 5, 0, move), PERSON);
            assertEquals(List.of(
                    new CellNeed(2, 4, 0, CellType.GROUND),
                    new CellNeed(2, 5, 0, CellType.PASSABLE),
                    new CellNeed(2, 6, 0, CellType.PASSABLE)), needs,
                    move + " should watch only the landing cell's standability");
        }
    }

    @Test
    void leapWatchesLandingTakeoffHeadroomAndTheWholeArc() {
        // A 2-wide gap: columns x=1,2 at z=0.
        List<CellNeed> needs = PathIntegrity.edgeNeeds(
                new Waypoint(0, 5, 0, MoveType.WALK), new Waypoint(3, 5, 0, MoveType.LEAP), PERSON);
        assertEquals(List.of(
                // landing standability
                new CellNeed(3, 4, 0, CellType.GROUND),
                new CellNeed(3, 5, 0, CellType.PASSABLE),
                new CellNeed(3, 6, 0, CellType.PASSABLE),
                // takeoff headroom (y + height, above the launch cell)
                new CellNeed(0, 7, 0, CellType.PASSABLE),
                // arc corridor over gap column x=1: body-height+1 cells (y..y+height)
                new CellNeed(1, 5, 0, CellType.PASSABLE),
                new CellNeed(1, 6, 0, CellType.PASSABLE),
                new CellNeed(1, 7, 0, CellType.PASSABLE),
                // arc corridor over gap column x=2
                new CellNeed(2, 5, 0, CellType.PASSABLE),
                new CellNeed(2, 6, 0, CellType.PASSABLE),
                new CellNeed(2, 7, 0, CellType.PASSABLE)), needs);
        // The gap floor itself is cost-only — never watched (filling it doesn't break the leap).
        assertTrue(needs.stream().noneMatch(n -> n.y() == 4 && (n.x() == 1 || n.x() == 2)),
                "gap-column floors must not be watched");
    }
}
