package dev.luizloyola.autarkia.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Headless engine tests over hand-drawn {@link AsciiWorld} terrains. Coordinates follow the
 * heightmap convention: (x, feetY, z) where feetY is the digit drawn at that column.
 */
class PathfinderTest {

    private static Path find(NavGrid world, int sx, int sy, int sz, int gx, int gy, int gz) {
        return Pathfinder.find(world, PathRequest.of(sx, sy, sz, gx, gy, gz, AgentProfile.PERSON));
    }

    @Test
    void walksStraightAcrossFlatGround() {
        Path path = find(AsciiWorld.of("11111"), 0, 1, 0, 4, 1, 0);
        assertTrue(path.reachedGoal());
        // Strides cover up to 3 cells per step: 4 blocks take 2 waypoints, not 4.
        assertEquals(2, path.waypoints().size());
        assertTrue(path.waypoints().stream().allMatch(w -> w.move() == MoveType.WALK));
        assertEquals(new Waypoint(4, 1, 0, MoveType.WALK), path.last());
    }

    @Test
    void startEqualsGoalIsAnEmptyCompletedPath() {
        Path path = find(AsciiWorld.of("1"), 0, 1, 0, 0, 1, 0);
        assertTrue(path.reachedGoal());
        assertTrue(path.isEmpty());
    }

    @Test
    void routesAroundAWall() {
        AsciiWorld world = AsciiWorld.of(
                "11111",
                "11#11",
                "11111");
        Path path = find(world, 2, 1, 0, 2, 1, 2);
        assertTrue(path.reachedGoal());
        assertTrue(path.waypoints().stream().noneMatch(w -> w.x() == 2 && w.z() == 1),
                "path must go around the wall cell, not through it");
    }

    @Test
    void prefersDiagonalsInTheOpen() {
        AsciiWorld world = AsciiWorld.of(
                "11111",
                "11111",
                "11111",
                "11111",
                "11111");
        Path path = find(world, 0, 1, 0, 4, 1, 4);
        assertTrue(path.reachedGoal());
        assertTrue(path.waypoints().size() <= 2,
                "expected long 45-degree strides, got " + path.waypoints());
    }

    @Test
    void stridesTakeNaturalIntermediateAngles() {
        // Displacement (2,6) on open ground: two (1,3) strides walk the true bearing (~18.4 deg
        // off axis) for a cost of 2*sqrt(10) ~ 6.32 — cheaper than any unit-move zigzag (~6.83),
        // and in 2 waypoints instead of 6.
        AsciiWorld world = AsciiWorld.of(
                "111",
                "111",
                "111",
                "111",
                "111",
                "111",
                "111");
        Path path = find(world, 0, 1, 0, 2, 1, 6);
        assertTrue(path.reachedGoal());
        assertEquals(2, path.waypoints().size());
        assertEquals(new Waypoint(1, 1, 3, MoveType.WALK), path.waypoints().get(0));
    }

    @Test
    void stridesRefuseToSweepOverObstacles() {
        AsciiWorld world = AsciiWorld.of(
                "11111",
                "11#11",
                "11111");
        Path path = find(world, 0, 1, 1, 4, 1, 1);
        assertTrue(path.reachedGoal());
        assertTrue(path.waypoints().stream().noneMatch(w -> w.x() == 2 && w.z() == 1));
        assertTrue(path.waypoints().stream().anyMatch(w -> w.z() != 1),
                "path should bend around the wall, got " + path.waypoints());
    }

    @Test
    void stridesRequireFlatGround() {
        Path path = find(AsciiWorld.of("11211"), 0, 1, 0, 4, 1, 0);
        assertTrue(path.reachedGoal());
        assertTrue(path.waypoints().stream().anyMatch(w -> w.move() == MoveType.JUMP));
        assertTrue(path.waypoints().stream().anyMatch(w -> w.move() == MoveType.DROP));
    }

    @Test
    void neverCutsCorners() {
        // The only diagonal from S to G clips the '#' corner, so the path must take 2 steps.
        //   S 1
        //   # G
        AsciiWorld world = AsciiWorld.of(
                "11",
                "#1");
        Path path = find(world, 0, 1, 0, 1, 1, 1);
        assertTrue(path.reachedGoal());
        assertEquals(2, path.waypoints().size());
    }

    @Test
    void jumpsUpOneBlock() {
        Path path = find(AsciiWorld.of("12"), 0, 1, 0, 1, 2, 0);
        assertTrue(path.reachedGoal());
        assertEquals(List.of(new Waypoint(1, 2, 0, MoveType.JUMP)), path.waypoints());
    }

    @Test
    void cannotJumpTwoBlocks() {
        Path path = find(AsciiWorld.of("13"), 0, 1, 0, 1, 3, 0);
        assertFalse(path.reachedGoal());
    }

    @Test
    void jumpNeedsHeadroomAboveTheStart() {
        // Same one-block step as jumpsUpOneBlock, but a ceiling right above the agent's head at
        // the start column: no room to jump.
        AsciiWorld world = AsciiWorld.of("12").fill(0, 3, 0, 0, 3, 0, CellType.OBSTACLE);
        Path path = find(world, 0, 1, 0, 1, 2, 0);
        assertFalse(path.reachedGoal());
    }

    @Test
    void climbsAStaircase() {
        Path path = find(AsciiWorld.of("1234"), 0, 1, 0, 3, 4, 0);
        assertTrue(path.reachedGoal());
        assertEquals(3, path.waypoints().size());
        assertTrue(path.waypoints().stream().allMatch(w -> w.move() == MoveType.JUMP));
    }

    @Test
    void dropsUpToThreeBlocks() {
        Path path = find(AsciiWorld.of("41"), 0, 4, 0, 1, 1, 0);
        assertTrue(path.reachedGoal());
        assertEquals(List.of(new Waypoint(1, 1, 0, MoveType.DROP)), path.waypoints());
    }

    @Test
    void refusesADropOfFour() {
        Path path = find(AsciiWorld.of("51"), 0, 5, 0, 1, 1, 0);
        assertFalse(path.reachedGoal());
    }

    @Test
    void leapsAOneWideGap() {
        Path path = find(AsciiWorld.of("1 1"), 0, 1, 0, 2, 1, 0);
        assertTrue(path.reachedGoal());
        assertEquals(List.of(new Waypoint(2, 1, 0, MoveType.LEAP)), path.waypoints());
    }

    @Test
    void leapsATwoWideGapGivenARunUp() {
        Path path = find(AsciiWorld.of("11  11"), 0, 1, 0, 5, 1, 0);
        assertTrue(path.reachedGoal());
        assertTrue(path.waypoints().contains(new Waypoint(4, 1, 0, MoveType.LEAP)),
                "expected a 2-wide leap onto (4,1,0), got " + path.waypoints());
    }

    @Test
    void leapsAThreeWideGapGivenARunUp() {
        Path path = find(AsciiWorld.of("11   11"), 0, 1, 0, 6, 1, 0);
        assertTrue(path.reachedGoal());
        assertTrue(path.waypoints().contains(new Waypoint(5, 1, 0, MoveType.LEAP)),
                "expected a 3-wide leap onto (5,1,0), got " + path.waypoints());
    }

    @Test
    void leapsATwoWideGapFromAStaircaseSummit() {
        // The run-up cell behind a staircase summit is one step down, and must still count for a
        // 2-gap: jumping up and sprinting across the takeoff carries enough speed.
        Path path = find(AsciiWorld.of("1234  4"), 0, 1, 0, 6, 4, 0);
        assertTrue(path.reachedGoal());
        assertTrue(path.waypoints().contains(new Waypoint(6, 4, 0, MoveType.LEAP)),
                "expected a summit leap onto (6,4), got " + path.waypoints());
    }

    @Test
    void leapsAThreeWideGapFromAStaircaseSummit() {
        Path path = find(AsciiWorld.of("1234   4"), 0, 1, 0, 7, 4, 0);
        assertTrue(path.reachedGoal());
        assertTrue(path.waypoints().contains(new Waypoint(7, 4, 0, MoveType.LEAP)));
    }

    @Test
    void partialPathClimbsTowardAHighGoalInsteadOfStandingUnderIt() {
        // The partial path ends high on the staircase (3-D closest), not at the tower's foot where
        // the old horizontal-only metric scored zero.
        Path path = find(AsciiWorld.of("123411119"), 0, 1, 0, 8, 9, 0);
        assertFalse(path.reachedGoal());
        assertFalse(path.isEmpty());
        assertEquals(4, path.last().y(), "should end atop the staircase: " + path.waypoints());
    }

    @Test
    void leapsATwoWideGapFromAStandingStart() {
        // No run-up cell at all (world edge behind the takeoff): a 2-gap still clears from a
        // standing sprint-jump.
        Path path = find(AsciiWorld.of("1  1"), 0, 1, 0, 3, 1, 0);
        assertTrue(path.reachedGoal());
        assertEquals(List.of(new Waypoint(3, 1, 0, MoveType.LEAP)), path.waypoints());
    }

    @Test
    void leapsAThreeWideGapFromAStandingStart() {
        // No ground behind the takeoff at all: capability is geometric, so this equals the
        // has-a-block-behind case (the follower sprints from inside the takeoff either way).
        Path path = find(AsciiWorld.of("1   1"), 0, 1, 0, 4, 1, 0);
        assertTrue(path.reachedGoal());
        assertEquals(List.of(new Waypoint(4, 1, 0, MoveType.LEAP)), path.waypoints());
    }

    @Test
    void refusesAGapOfFour() {
        Path path = find(AsciiWorld.of("11    11"), 1, 1, 0, 6, 1, 0);
        assertFalse(path.reachedGoal());
    }

    @Test
    void leapNeedsTheArcCorridor() {
        // A block over the gap at arc height: the flight path is blocked, no leap.
        AsciiWorld world = AsciiWorld.of("1 1").fill(1, 3, 0, 1, 3, 0, CellType.OBSTACLE);
        Path path = find(world, 0, 1, 0, 2, 1, 0);
        assertFalse(path.reachedGoal());
    }

    @Test
    void prefersLeapingATrenchOverDippingThroughIt() {
        // 1-wide 1-deep trench: leaping it (2.4) beats drop-in + jump-out (3.0).
        Path path = find(AsciiWorld.of("22122"), 0, 2, 0, 4, 2, 0);
        assertTrue(path.reachedGoal());
        assertTrue(path.waypoints().stream().anyMatch(w -> w.move() == MoveType.LEAP));
        assertTrue(path.waypoints().stream().noneMatch(w -> w.move() == MoveType.DROP),
                "should fly over the trench, not dip: " + path.waypoints());
    }

    @Test
    void leapsANarrowHoleDirectly() {
        AsciiWorld world = AsciiWorld.of(
                "111",
                "1 1",
                "111");
        Path path = find(world, 1, 1, 0, 1, 1, 2);
        assertTrue(path.reachedGoal());
        assertTrue(path.waypoints().stream().noneMatch(w -> w.x() == 1 && w.z() == 1));
        assertTrue(path.waypoints().stream().anyMatch(w -> w.move() == MoveType.LEAP),
                "a 1-wide hole is cheaper leapt than skirted: " + path.waypoints());
    }

    @Test
    void leapsANarrowLavaStrip() {
        // Jumping a 1-wide lava channel is safe — the arc never touches the surface cell.
        Path path = find(AsciiWorld.of("1L1"), 0, 1, 0, 2, 1, 0);
        assertTrue(path.reachedGoal());
        assertEquals(MoveType.LEAP, path.last().move());
    }

    @Test
    void willNotCrossWideLava() {
        Path path = find(AsciiWorld.of("11LLLL11"), 1, 1, 0, 6, 1, 0);
        assertFalse(path.reachedGoal());
    }

    @Test
    void swimsAcrossWideWater() {
        // The water counterpart of willNotCrossWideLava: too wide to leap, but a Person can swim it.
        Path path = find(AsciiWorld.of("11WWWW11"), 1, 1, 0, 6, 1, 0);
        assertTrue(path.reachedGoal());
        assertTrue(path.waypoints().stream().anyMatch(w -> w.move() == MoveType.SWIM),
                "crosses by swimming: " + path.waypoints());
    }

    @Test
    void needsTwoTallClearance() {
        // A 1-high slot at head height in the middle column: a 2-tall body cannot pass.
        AsciiWorld world = AsciiWorld.of("111").fill(1, 2, 0, 1, 2, 0, CellType.OBSTACLE);
        Path path = find(world, 0, 1, 0, 2, 1, 0);
        assertFalse(path.reachedGoal());
    }

    @Test
    void walledInGoalYieldsAPartialPathToTheNearestReachableCell() {
        AsciiWorld world = AsciiWorld.of(
                "11111",
                "1###1",
                "1#1#1",
                "1###1",
                "11111");
        Path path = find(world, 0, 1, 0, 2, 1, 2);
        assertFalse(path.reachedGoal());
        assertFalse(path.isEmpty());
        Waypoint last = path.last();
        // The best reachable cells hug the wall ring; anything within octile distance 2 of the
        // goal is one of them.
        int dx = Math.abs(last.x() - 2);
        int dz = Math.abs(last.z() - 2);
        assertTrue(Math.max(dx, dz) + 0.5 * Math.min(dx, dz) <= 2.0,
                "partial path should end close to the goal, ended at " + last);
    }

    @Test
    void budgetExhaustionStillMakesProgress() {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < 40; i++) row.append('1');
        AsciiWorld world = AsciiWorld.of(row.toString(), row.toString(), row.toString());
        Path path = Pathfinder.find(world,
                new PathRequest(0, 1, 1, 39, 1, 1, AgentProfile.PERSON, 8));
        assertFalse(path.reachedGoal());
        assertFalse(path.isEmpty());
        assertTrue(path.last().x() > 0, "partial path should head toward the goal");
    }

    @Test
    void prefersSafeDetourOverNarrowBridge() {
        // The 1-wide bridge at z=5 wins on plain steps, but its cells border the void on both
        // sides and careful pricing (x2.2 per step) turns that round. The z=0..1 strip is longer
        // and safe — its z=0 lane has land or world-edge on every side.
        AsciiWorld world = AsciiWorld.of(
                "11111111111",
                "11111111111",
                "111     111",
                "111     111",
                "111     111",
                "11111111111",
                "111     111",
                "111     111",
                "111     111");
        Path path = find(world, 1, 1, 4, 9, 1, 4);
        assertTrue(path.reachedGoal());
        assertTrue(path.waypoints().stream().noneMatch(w -> w.z() == 5 && w.x() >= 3 && w.x() <= 7),
                "should take the safe strip, not the bridge: " + path.waypoints());
        assertTrue(path.waypoints().stream().anyMatch(w -> w.z() <= 1),
                "should cross via the strip: " + path.waypoints());
    }

    @Test
    void crossesNarrowBridgeWhenItIsTheOnlyWay() {
        AsciiWorld world = AsciiWorld.of(
                "111     111",
                "111     111",
                "11111111111",
                "111     111",
                "111     111");
        Path path = find(world, 1, 1, 2, 9, 1, 2);
        assertTrue(path.reachedGoal());
        // Careful ground forbids strides, so every bridge cell is its own unit waypoint (the
        // exit may stride — leaving a careful cell across safe ground is allowed).
        for (int x = 3; x <= 7; x++) {
            final int bx = x;
            assertTrue(path.waypoints().stream().anyMatch(w -> w.x() == bx && w.z() == 2),
                    "bridge cell x=" + bx + " should be a unit waypoint: " + path.waypoints());
        }
        assertTrue(path.waypoints().stream().allMatch(w -> w.move() == MoveType.WALK));
    }

    @Test
    void identicalRequestsProduceIdenticalPaths() {
        AsciiWorld world = AsciiWorld.of(
                "111111",
                "1#11#1",
                "111111",
                "1#1#11",
                "111111");
        Path first = find(world, 0, 1, 0, 5, 1, 4);
        Path second = find(world, 0, 1, 0, 5, 1, 4);
        assertEquals(first, second);
    }

    @Test
    void packingRoundTripsNegativeCoordinates() {
        int[][] samples = {{0, 0, 0}, {-1, -1, -1}, {30_000_000, 2031, -30_000_000}, {-341, -64, 12_550_820}};
        for (int[] s : samples) {
            long key = Pathfinder.pack(s[0], s[1], s[2]);
            assertEquals(s[0], Pathfinder.unpackX(key));
            assertEquals(s[1], Pathfinder.unpackY(key));
            assertEquals(s[2], Pathfinder.unpackZ(key));
        }
    }
}
