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
 * Where a body must stand to reach every log, how it gets there — counted from the cell it stands
 * in beside the trunk, whatever height the ground puts that at — and what comes out on the way
 * up, from the top, on the way down, and from outside.
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
        return plan(logs, beside, true);
    }

    private static Climb plan(List<Pos> logs, Pos beside, boolean jumpRoom) {
        return Climb.plan(ARM, STUMP, logs, world(logs), beside, jumpRoom, BODY);
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
        assertEquals(List.of(STUMP), climb.under());
        assertEquals("open 0 · step in · no rise · 4 above · 1 underfoot", climb.describe());
    }

    @Test
    void aSevenLogBirchWantsOneStepInAndNoRise() {
        Climb climb = plan(column(7), BESIDE);

        assertEquals(BASE + 1, climb.needFeetY(), "the top log is reachable from one up");
        assertEquals(List.of(new Pos(0, BASE + 1, 0), new Pos(0, BASE + 2, 0)), climb.stepIn(),
                "the two logs a body's height above the stump; the stump stays as the floor");
        assertEquals(new Pos(0, BASE + 1, 0), climb.stand());
        assertEquals(0, climb.rises());
        assertEquals(column(BASE + 3, 4), climb.above());
        assertEquals(List.of(STUMP), climb.under(), "the stump: underfoot, on the way down");
        assertTrue(climb.last().isEmpty());
        assertEquals(BASE, climb.floor());
        assertTrue(climb.complete());
        assertEquals("open 2 · step in · no rise · 4 above · 1 underfoot", climb.describe());
    }

    @Test
    void aShortTreeIsBrokenFromBeside() {
        Climb climb = plan(column(4), BESIDE);

        assertTrue(climb.needFeetY() <= BASE);
        assertFalse(climb.stepsIn());
        assertTrue(climb.stepIn().isEmpty());
        assertEquals(BESIDE, climb.stand());
        assertEquals(column(BASE + 1, 3), climb.above(), "every log above the floor, lowest first");
        assertEquals(List.of(STUMP), climb.last(), "and the stump after them, from where it stands");
        assertTrue(climb.under().isEmpty());
        assertEquals(BASE, climb.floor());
        assertTrue(climb.complete());
        assertEquals("4 to break from beside", climb.describe());
    }

    @Test
    void aTallTrunkRisesToTheMinimumThenTakesTheRest() {
        Climb climb = plan(column(12), BESIDE);

        assertEquals(BASE + 6, climb.needFeetY());
        assertEquals(5, climb.rises(), "from the stand at one up to the height the top demands");
        assertEquals(2, climb.stepIn().size());
        assertEquals(column(BASE + 3, 9), climb.above());
        assertEquals(List.of(STUMP), climb.under());
        assertTrue(climb.complete());
        assertEquals("open 2 · step in · 5 rises · 9 above · 1 underfoot", climb.describe());
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

        Climb climb = Climb.plan(ARM, STUMP, logs, world, BESIDE, true, BODY);
        assertEquals(List.of(new Pos(0, BASE + 1, 0), new Pos(0, BASE + 2, 0)), climb.stepIn());
        assertEquals("open 2 · step in · no rise · 4 above · 1 underfoot", climb.describe());
    }

    // ── a giant ──────────────────────────────────────────────────────────────────────────────

    private static final List<Pos> SQUARE = List.of(new Pos(0, BASE, 0), new Pos(1, BASE, 0),
            new Pos(1, BASE, 1), new Pos(0, BASE, 1));

    private static List<Pos> giant(int logs) {
        List<Pos> out = new ArrayList<>();
        for (Pos c : SQUARE) {
            out.addAll(column(c.x(), BASE, c.z(), logs));
        }
        return out;
    }

    private static List<Pos> column(int x, int from, int z, int logs) {
        List<Pos> out = new ArrayList<>();
        for (int y = from; y < from + logs; y++) {
            out.add(new Pos(x, y, z));
        }
        return out;
    }

    @Test
    void theRingRunsClockwiseFromAboveAndBackAgain() {
        List<Pos> shuffled = List.of(SQUARE.get(2), SQUARE.get(0), SQUARE.get(3), SQUARE.get(1));
        assertEquals(SQUARE, Climb.ringOf(shuffled), "north-west, north-east, south-east, south-west");

        Climb cw = new Climb(SQUARE.get(0), BASE, true, false, List.of(), List.of(), List.of(),
                List.of(), true, SQUARE, true, List.of());
        assertEquals(SQUARE.get(1), cw.next(SQUARE.get(0), true), "east of the north-west cell");
        assertEquals(SQUARE.get(3), cw.next(SQUARE.get(0), false), "and back the other way");
        Climb ccw = new Climb(SQUARE.get(0), BASE, true, false, List.of(), List.of(), List.of(),
                List.of(), true, SQUARE, false, List.of());
        assertEquals(SQUARE.get(3), ccw.next(SQUARE.get(0), true));
        assertEquals(SQUARE.get(0), ccw.column(new Pos(0, BASE + 9, 0)), "by x and z, whatever the height");
    }

    @Test
    void aGiantIsSpiralledFromTheEntryColumn() {
        List<Pos> logs = giant(12);
        Pos beside = new Pos(-1, BASE, 0); // the west side, beside the north-west column
        Climb climb = Climb.plan(ARM, SQUARE, SQUARE.get(0), logs, List.of(), world(logs), beside,
                true, true, BODY);

        assertTrue(climb.giant());
        assertEquals(new Pos(0, BASE + 1, 0), climb.stand());
        assertEquals(column(0, BASE + 1, 0, 3), climb.stepIn(), "three: the body's two and one to hop on from");
        assertEquals(BASE + 6, climb.needFeetY(), "the top of twelve, priced from the diagonal column");
        assertEquals(5, climb.rises());
        assertEquals(List.of(new Pos(0, BASE, 0)), climb.under(), "the entry column's base, on the way down");
        assertTrue(climb.last().isEmpty());
        assertEquals(48 - 3 - 1, climb.above().size(), "everything else, the stairs included");
        assertTrue(climb.complete());
        assertEquals("open 3 · step in · spiral 5 · 44 to break · 1 underfoot", climb.describe());
    }

    @Test
    void aShortGiantIsAllFromBeside() {
        List<Pos> logs = giant(3);
        Climb climb = Climb.plan(ARM, SQUARE, SQUARE.get(0), logs, List.of(), world(logs),
                new Pos(-1, BASE, 0), true, true, BODY);

        assertFalse(climb.stepsIn());
        assertEquals(4, climb.columns().size());
        assertEquals("12 to break from beside", climb.describe());
    }

    @Test
    void branchesRideAlongAndAreCounted() {
        List<Pos> logs = column(7);
        List<Pos> branches = List.of(new Pos(1, BASE + 4, 0), new Pos(2, BASE + 5, 0));
        Climb climb = Climb.plan(ARM, List.of(STUMP), STUMP, logs, branches, world(logs), BESIDE,
                true, false, BODY);

        assertEquals(branches, climb.branches());
        assertEquals("open 2 · step in · no rise · 4 above · 2 branches · 1 underfoot",
                climb.describe());
    }

    // ── the ground beside the stump ──────────────────────────────────────────────────────────

    @Test
    void onRaisedGroundTheSecondLogIsTheStepUpAndTheStumpComesFromOutside() {
        Climb climb = plan(column(9), RAISED);

        assertTrue(climb.stepsIn());
        assertEquals(List.of(new Pos(0, BASE + 2, 0), new Pos(0, BASE + 3, 0)), climb.stepIn(),
                "the body's height above the log at its own feet height");
        assertEquals(new Pos(0, BASE + 2, 0), climb.stand(), "on the second log");
        assertEquals(1, climb.rises());
        assertEquals(column(BASE + 4, 5), climb.above());
        assertEquals(List.of(new Pos(0, BASE + 1, 0)), climb.under(), "the step up, on the way down");
        assertEquals(List.of(STUMP), climb.last(), "one below floor level: from outside");
        assertEquals(BASE + 1, climb.floor());
        assertTrue(climb.complete());
        assertEquals("open 2 · step in · 1 rise · 5 above · 1 underfoot · then 1 from outside",
                climb.describe());
    }

    @Test
    void onRaisedGroundAShortTrunkIsAllFromBeside() {
        Climb climb = plan(column(7), RAISED);

        assertFalse(climb.stepsIn());
        assertEquals(column(BASE + 2, 5), climb.above());
        assertEquals(List.of(new Pos(0, BASE + 1, 0), STUMP), climb.last(),
                "floor level and one below, highest first");
        assertTrue(climb.complete());
        assertEquals("7 to break from beside", climb.describe());
    }

    @Test
    void onSunkenGroundTheDirtIsTheStepUpAndNothingComesAfter() {
        Climb climb = plan(column(7), SUNKEN);

        assertTrue(climb.stepsIn());
        assertEquals(List.of(STUMP, new Pos(0, BASE + 1, 0)), climb.stepIn(),
                "the stump is in the body's way now, and comes out with the log above it");
        assertEquals(STUMP, climb.stand(), "feet where the stump was, on the dirt under it");
        assertEquals(1, climb.rises());
        assertTrue(climb.under().isEmpty(), "the dirt was never part of the tree");
        assertTrue(climb.last().isEmpty());
        assertEquals(BASE - 1, climb.floor());
        assertTrue(climb.complete());
        assertEquals("open 2 · step in · 1 rise · 5 above", climb.describe());
    }

    @Test
    void groundTwoUpLeavesTheStumpBuried() {
        Climb climb = plan(column(9), new Pos(1, BASE + 2, 0));

        assertEquals(List.of(new Pos(0, BASE + 2, 0)), climb.under());
        assertEquals(List.of(new Pos(0, BASE + 1, 0)), climb.last(), "one below floor level");
        assertFalse(climb.above().contains(STUMP));
        assertTrue(climb.complete(), "two below floor level is a pit, and left on purpose");
    }

    // ── no room to hop ───────────────────────────────────────────────────────────────────────

    @Test
    void aLowSideDigsInAtItsOwnLevel() {
        Climb climb = plan(column(7), BESIDE, false);

        assertTrue(climb.stepsIn());
        assertTrue(climb.digsIn());
        assertEquals(List.of(STUMP, new Pos(0, BASE + 1, 0)), climb.stepIn(),
                "the trunk is dug open at the body's own height, stump included");
        assertEquals(STUMP, climb.stand(), "and it walks in flat, onto the dirt under the stump");
        assertEquals(1, climb.rises());
        assertTrue(climb.under().isEmpty(), "nothing of the tree is left underfoot");
        assertEquals(BASE, climb.floor());
        assertTrue(climb.complete());
        assertEquals("open 2 · dig in · 1 rise · 5 above", climb.describe());
    }

    @Test
    void aLowSideOnRaisedGroundDigsThroughTheSecondLogAndKeepsTheStumpForOutside() {
        Climb climb = plan(column(9), RAISED, false);

        assertTrue(climb.digsIn());
        assertEquals(List.of(new Pos(0, BASE + 1, 0), new Pos(0, BASE + 2, 0)), climb.stepIn());
        assertEquals(new Pos(0, BASE + 1, 0), climb.stand(), "level with the ground beside");
        assertEquals(2, climb.rises());
        assertTrue(climb.under().isEmpty());
        assertEquals(List.of(STUMP), climb.last());
        assertEquals("open 2 · dig in · 2 rises · 6 above · then 1 from outside", climb.describe());
    }

    @Test
    void aLowSideOnSunkenGroundHasNothingToDigInto() {
        Climb climb = plan(column(7), SUNKEN, false);

        assertFalse(climb.stepsIn(), "the cell at its own level is dirt, and the axe does not dig");
        assertTrue(climb.describe().endsWith("no footing to step in"), climb.describe());
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
