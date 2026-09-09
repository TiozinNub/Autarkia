package dev.luizloyola.autarkia.core.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Where a body must stand to reach every log, and the ladder of breaks and rises to get there —
 * counted from the cell it stands in beside the trunk, whatever height the ground puts that at.
 */
class ClimbTest {

    private static final int BASE = FakeProbe.GROUND_Y + 1;
    private static final Pos STUMP = new Pos(0, BASE, 0);
    /** Beside the stump on level ground. */
    private static final Pos BESIDE = new Pos(1, BASE, 0);
    /** Beside it where the ground is a block higher, and a block lower. */
    private static final Pos RAISED = new Pos(1, BASE + 1, 0);
    private static final Pos SUNKEN = new Pos(1, BASE - 1, 0);
    private static final int BODY = 2;
    /** A settler's eyes and arm — the survival player's numbers. */
    private static final Climb.Arm ARM = new Climb.Arm(1.62, 4.5);

    private static List<Pos> column(int logs) {
        return column(BASE, logs);
    }

    private static List<Pos> column(int from, int logs) {
        List<Pos> out = new ArrayList<>();
        for (int y = from; y < from + logs; y++) {
            out.add(new Pos(0, y, 0));
        }
        return out;
    }

    /** The world those logs stand in: flat ground at {@link FakeProbe#GROUND_Y}, the logs on it. */
    private static FakeProbe world(List<Pos> logs) {
        FakeProbe probe = new FakeProbe();
        for (Pos log : logs) {
            probe.set(log.x(), log.y(), log.z(), BlockKind.LOG);
        }
        return probe;
    }

    private static Climb plan(List<Pos> logs, Pos beside) {
        return Climb.plan(ARM, STUMP, logs, world(logs), beside, BODY);
    }

    @Test
    void theExampleFromTheBrief() {
        // A block at 10, one away: standing at 5 the eyes are at 6.62 and the arm reaches it;
        // at 4 they are not. Three away, the body has to be a block higher.
        assertEquals(5, Climb.feetToReach(ARM, 0, 0, new Pos(1, 10, 0)).orElseThrow());
        assertEquals(6, Climb.feetToReach(ARM, 0, 0, new Pos(3, 10, 0)).orElseThrow());
        assertEquals(5, Climb.feetToReach(ARM, 0, 0, new Pos(0, 10, 0)).orElseThrow(),
                "straight overhead: five below the log");
        assertTrue(Climb.feetToReach(ARM, 0, 0, new Pos(5, 10, 0)).isEmpty(),
                "farther out than the arm is long: no height reaches it");

        assertTrue(Climb.reaches(ARM, 0, 5, 0, new Pos(1, 10, 0)));
        assertFalse(Climb.reaches(ARM, 0, 4, 0, new Pos(1, 10, 0)));
    }

    @Test
    void theColumnReadsThroughALeafAndStopsAtAWiderGap() {
        FakeProbe probe = new FakeProbe();
        for (int y = BASE; y < BASE + 7; y++) {
            probe.set(0, y, 0, BlockKind.LOG);
        }
        probe.set(0, BASE + 7, 0, BlockKind.LEAVES);
        probe.set(0, BASE + 8, 0, BlockKind.LOG);   // a log over one leaf is still this trunk
        probe.set(0, BASE + 12, 0, BlockKind.LOG);  // one past three cells of air is not

        List<Pos> expected = new ArrayList<>(column(7));
        expected.add(new Pos(0, BASE + 8, 0));
        assertEquals(expected, Climb.column(STUMP, probe, BODY));
    }

    @Test
    void theColumnReadsAcrossTheGapABodyOpened() {
        FakeProbe probe = new FakeProbe();
        probe.set(0, BASE, 0, BlockKind.LOG);
        for (int y = BASE + 3; y < BASE + 7; y++) {
            probe.set(0, y, 0, BlockKind.LOG);
        }

        assertEquals(5, Climb.column(STUMP, probe, BODY).size(),
                "the base, two cells of air, four logs: one trunk");
        assertEquals(List.of(STUMP), Climb.column(STUMP, probe, 1),
                "a gap wider than the body opened is not this trunk");
    }

    @Test
    void aTrunkAlreadyOpenedStillStepsIn() {
        List<Pos> logs = new ArrayList<>();
        logs.add(STUMP);
        logs.addAll(column(BASE + 3, 4));

        Climb climb = plan(logs, BESIDE);
        assertTrue(climb.stepsIn());
        assertTrue(climb.stepIn().isEmpty(), "nothing left to open");
        assertEquals(new Pos(0, BASE + 1, 0), climb.stand());
        assertEquals(List.of(STUMP), climb.last());
        assertEquals("open 0 · step in · 4 to break · no rise · then 1 from the ground",
                climb.describe());
    }

    @Test
    void aSevenLogBirchWantsOneStepIn() {
        Climb climb = plan(column(7), BESIDE);

        assertEquals(BASE + 1, climb.needFeetY(), "the top log is reachable from one up");
        assertEquals(List.of(new Pos(0, BASE + 1, 0), new Pos(0, BASE + 2, 0)), climb.stepIn(),
                "the two logs a body's height above the stump; the stump stays as the floor");
        assertEquals(new Pos(0, BASE + 1, 0), climb.stand());
        assertEquals(1, climb.levels().size());
        Climb.Level level = climb.levels().get(0);
        assertEquals(BASE + 1, level.feetY());
        assertEquals(column(BASE + 3, 4), level.breaks());
        assertFalse(level.rise());
        assertEquals(List.of(STUMP), climb.last(), "the stump: from the ground, at the end");
        assertTrue(climb.complete());
        assertEquals("open 2 · step in · 4 to break · no rise · then 1 from the ground",
                climb.describe());
    }

    @Test
    void aShortTreeIsBrokenFromBeside() {
        Climb climb = plan(column(4), BESIDE);

        assertTrue(climb.needFeetY() <= BASE);
        assertFalse(climb.stepsIn());
        assertTrue(climb.stepIn().isEmpty());
        assertEquals(BESIDE, climb.stand());
        assertEquals(List.of(new Climb.Level(BASE, column(BASE + 1, 3), false)), climb.levels(),
                "every log above where the body stands, lowest first");
        assertEquals(List.of(STUMP), climb.last(), "and the stump after them");
        assertTrue(climb.complete());
        assertEquals("4 to break from beside", climb.describe());
    }

    @Test
    void aTallTrunkIsClimbedALogAtATime() {
        Climb climb = plan(column(12), BESIDE);

        assertEquals(BASE + 6, climb.needFeetY());
        assertEquals(2, climb.stepIn().size());
        assertEquals(6, climb.levels().size());
        assertEquals(4, climb.levels().get(0).breaks().size(), "four in reach from the base log");
        assertTrue(climb.levels().get(0).rise());
        for (int i = 1; i < 6; i++) {
            assertEquals(1, climb.levels().get(i).breaks().size(), "then one per rise");
        }
        assertFalse(climb.levels().get(5).rise(), "the top comes out from the last level");
        assertEquals(5, climb.rises());
        assertEquals(9, climb.toBreak());
        assertTrue(climb.complete());
        assertEquals("open 2 · step in · 9 to break · 5 rises · then 1 from the ground",
                climb.describe());
    }

    @Test
    void aLogTooFarOutIsSaidSo() {
        List<Pos> logs = new ArrayList<>(column(7));
        logs.add(new Pos(5, BASE + 4, 0)); // a branch farther out than the arm is long

        Climb climb = plan(logs, BESIDE);
        assertFalse(climb.complete());
        assertTrue(climb.describe().endsWith("some out of reach"));
    }

    @Test
    void aLeafInTheWayOfSteppingInIsOpenedToo() {
        List<Pos> logs = new ArrayList<>(column(7));
        logs.remove(new Pos(0, BASE + 2, 0));
        FakeProbe world = world(logs);
        world.set(0, BASE + 2, 0, BlockKind.LEAVES);

        Climb climb = Climb.plan(ARM, STUMP, logs, world, BESIDE, BODY);
        assertEquals(List.of(new Pos(0, BASE + 1, 0), new Pos(0, BASE + 2, 0)), climb.stepIn());
        assertEquals("open 2 · step in · 4 to break · no rise · then 1 from the ground",
                climb.describe());
    }

    // ── the ground beside the stump ──────────────────────────────────────────────────────────

    @Test
    void onRaisedGroundTheSecondLogIsTheStepUp() {
        Climb climb = plan(column(9), RAISED);

        assertTrue(climb.stepsIn());
        assertEquals(List.of(new Pos(0, BASE + 2, 0), new Pos(0, BASE + 3, 0)), climb.stepIn(),
                "the body's height above the log at its own feet height");
        assertEquals(new Pos(0, BASE + 2, 0), climb.stand(), "on the second log");
        for (Climb.Level level : climb.levels()) {
            assertFalse(level.breaks().contains(new Pos(0, BASE + 1, 0)),
                    "never the floor underfoot");
        }
        assertEquals(List.of(new Pos(0, BASE + 1, 0), STUMP), climb.last(),
                "the step up, then the stump under it — from the ground, at the end");
        assertTrue(climb.complete());
        assertEquals("open 2 · step in · 5 to break · 1 rise · then 2 from the ground",
                climb.describe());
    }

    @Test
    void onRaisedGroundAShortTrunkIsAllFromBeside() {
        Climb climb = plan(column(7), RAISED);

        assertFalse(climb.stepsIn());
        assertEquals(column(BASE + 2, 5), climb.levels().get(0).breaks());
        assertEquals(List.of(new Pos(0, BASE + 1, 0), STUMP), climb.last());
        assertTrue(climb.complete());
        assertEquals("7 to break from beside", climb.describe());
    }

    @Test
    void onSunkenGroundTheDirtIsTheStepUpAndNothingComesLast() {
        Climb climb = plan(column(7), SUNKEN);

        assertTrue(climb.stepsIn());
        assertEquals(List.of(STUMP, new Pos(0, BASE + 1, 0)), climb.stepIn(),
                "the stump is in the body's way now, and comes out with the log above it");
        assertEquals(STUMP, climb.stand(), "feet where the stump was, on the dirt under it");
        assertTrue(climb.last().isEmpty(), "the dirt was never part of the tree");
        assertTrue(climb.complete());
        assertEquals("open 2 · step in · 5 to break · 1 rise", climb.describe());
    }

    @Test
    void groundTwoBelowTheStumpGivesNoFootingYet() {
        Climb climb = plan(column(7), new Pos(1, BASE - 2, 0));

        assertFalse(climb.stepsIn(), "the cell to stand in is ground, and the axe does not dig");
        assertFalse(climb.complete());
        assertTrue(climb.describe().endsWith("no footing to step in"), climb.describe());
    }

    @Test
    void aTrunkOverAirGivesNoFootingEither() {
        Climb climb = plan(column(BASE + 1, 7), BESIDE); // a log up, nothing under it

        assertFalse(climb.stepsIn());
        assertTrue(climb.describe().endsWith("no footing to step in"), climb.describe());
    }
}
