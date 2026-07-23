package dev.luizloyola.autarkia.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Surface-swimming tests over hand-drawn {@link AsciiWorld} terrains. The {@code 'W'} char draws a
 * water column: {@link CellType#WATER} at feet level {@code y=0}, {@link CellType#GROUND} bed below,
 * air above — so the water surface sits one cell below the {@code y=1} feet of an adjacent {@code
 * '1'} shore, the real waterline geometry (enter steps down one, exit climbs up one).
 */
class PathfinderSwimTest {

    private static Path find(NavGrid world, int sx, int sy, int sz, int gx, int gy, int gz) {
        return find(world, sx, sy, sz, gx, gy, gz, AgentProfile.PERSON);
    }

    private static Path find(NavGrid world, int sx, int sy, int sz, int gx, int gy, int gz,
                             AgentProfile profile) {
        return Pathfinder.find(world, PathRequest.of(sx, sy, sz, gx, gy, gz, profile));
    }

    private static boolean hasMove(Path path, MoveType move) {
        return path.waypoints().stream().anyMatch(w -> w.move() == move);
    }

    /** A river four cells wide — wider than a Person's 3-block leap, so it must be swum. */
    private static final String[] WIDE_RIVER = {"11WWWW11"};

    @Test
    void swimsAcrossWaterTooWideToLeap() {
        Path path = find(AsciiWorld.of(WIDE_RIVER), 0, 1, 0, 7, 1, 0);
        assertTrue(path.reachedGoal(), "should reach the far bank by swimming");
        assertEquals(new Waypoint(7, 1, 0, MoveType.WALK), path.last());
        assertTrue(hasMove(path, MoveType.SWIM), "the crossing must use SWIM steps");
        assertTrue(path.waypoints().stream()
                        .filter(w -> w.move() == MoveType.SWIM)
                        .allMatch(w -> w.y() == 0),
                "swim waypoints sit at the water surface, not the bed: " + path.waypoints());
    }

    @Test
    void climbsOutOntoTheFarBank() {
        Path path = find(AsciiWorld.of(WIDE_RIVER), 0, 1, 0, 7, 1, 0);
        // The only land adjacent to the water is the far bank at x=6, one cell up out of the
        // surface at y=0.
        assertTrue(path.waypoints().stream()
                        .anyMatch(w -> w.x() == 6 && w.y() == 1 && w.move() == MoveType.JUMP),
                "should climb out onto the far bank at (6,1) as a JUMP: " + path.waypoints());
    }

    @Test
    void aLandWalkerCannotCrossOpenWater() {
        AgentProfile landOnly = new AgentProfile(2, 1, 3, 3, false);
        Path walker = find(AsciiWorld.of(WIDE_RIVER), 0, 1, 0, 7, 1, 0, landOnly);
        assertFalse(walker.reachedGoal(), "a non-swimmer can't cross a 4-wide river");
        assertFalse(hasMove(walker, MoveType.SWIM), "a non-swimmer never produces a SWIM step");

        // Same terrain, same goal: the only difference is the capability.
        Path swimmer = find(AsciiWorld.of(WIDE_RIVER), 0, 1, 0, 7, 1, 0);
        assertTrue(swimmer.reachedGoal());
    }

    @Test
    void stillLeapsNarrowWaterRatherThanSwim() {
        // A one-wide water gap: a single leap (2.4) beats entering-crossing-exiting (~5.0), so the
        // search keeps its feet dry.
        Path path = find(AsciiWorld.of("11W11"), 0, 1, 0, 4, 1, 0);
        assertTrue(path.reachedGoal());
        assertTrue(hasMove(path, MoveType.LEAP), "narrow water should be leapt: " + path.waypoints());
        assertFalse(hasMove(path, MoveType.SWIM), "narrow water should not be swum: " + path.waypoints());
    }

    @Test
    void prefersAShortDryDetourOverSwimming() {
        // The far dry row (z=2) does not border the water, so the go-around pays no careful
        // penalty and stays cheaper than the long wet crossing.
        AsciiWorld world = AsciiWorld.of(
                "1WWWW1",
                "111111",
                "111111");
        Path path = find(world, 0, 1, 0, 5, 1, 0);
        assertTrue(path.reachedGoal());
        assertFalse(hasMove(path, MoveType.SWIM),
                "a cheap dry detour should win over swimming: " + path.waypoints());
    }
}
