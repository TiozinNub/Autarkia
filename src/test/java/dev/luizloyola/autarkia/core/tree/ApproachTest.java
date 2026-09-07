package dev.luizloyola.autarkia.core.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The first look a fell takes: which sides of the stump a body could walk up to. */
class ApproachTest {

    private static final int BASE = FakeProbe.GROUND_Y + 1;
    private static final Pos ANCHOR = new Pos(0, BASE, 0);
    /** A settler stands two cells tall. */
    private static final int BODY = 2;

    private final FakeProbe probe = new FakeProbe();

    private void trunk(int x, int z) {
        for (int y = BASE; y < BASE + 6; y++) {
            probe.set(x, y, z, BlockKind.LOG);
        }
    }

    private Approach survey() {
        return Approach.survey(ANCHOR, probe, BODY);
    }

    private static Approach.Side side(Approach approach, String bearing) {
        return approach.sides().stream()
                .filter(side -> approach.bearing(side.cell()).equals(bearing))
                .findFirst().orElseThrow();
    }

    @Test
    void aLoneTrunkOnFlatGroundIsOpenOnEverySide() {
        trunk(0, 0);
        Approach approach = survey();

        assertTrue(approach.standing());
        assertEquals(List.of(ANCHOR), approach.base());
        assertEquals(4, approach.sides().size());
        assertEquals(4, approach.approachable());
        for (Approach.Side side : approach.sides()) {
            assertEquals(Approach.Verdict.OPEN, side.verdict());
            assertEquals(side.cell(), side.feet(), "an open side is stood on where it is");
        }
        assertEquals("N open · E open · S open · W open", approach.summary(),
                "compass order, north first, so two readouts of one tree read the same");
    }

    @Test
    void solidBesideTheStumpRaisesTheFeet() {
        trunk(0, 0);
        probe.set(1, BASE, 0, BlockKind.OTHER);

        Approach.Side east = side(survey(), "E");
        assertEquals(Approach.Verdict.RAISED, east.verdict());
        assertEquals(new Pos(1, BASE + 1, 0), east.feet());
        assertEquals("up 1", east.describe());

        probe.set(1, BASE + 1, 0, BlockKind.OTHER);
        assertEquals("up 2", side(survey(), "E").describe());
    }

    @Test
    void aWallPastReachIsNotAStep() {
        trunk(0, 0);
        for (int y = BASE; y < BASE + Approach.REACH; y++) {
            probe.set(1, y, 0, BlockKind.OTHER);
        }
        assertEquals("up " + Approach.REACH, side(survey(), "E").describe(),
                "exactly REACH solid cells are still a step");

        probe.set(1, BASE + Approach.REACH, 0, BlockKind.OTHER);
        Approach.Side east = side(survey(), "E");
        assertEquals(Approach.Verdict.TOO_HIGH, east.verdict());
        assertNull(east.feet());
        assertFalse(east.verdict().approachable());
    }

    @Test
    void aDipDropsTheFeet() {
        trunk(0, 0);
        probe.set(0, BASE - 1, -1, BlockKind.AIR);
        probe.set(0, BASE - 2, -1, BlockKind.AIR);

        Approach.Side north = side(survey(), "N");
        assertEquals(Approach.Verdict.SUNKEN, north.verdict());
        assertEquals(new Pos(0, BASE - 2, -1), north.feet());
        assertEquals(-2, north.rise());
        assertEquals("down 2", north.describe());
    }

    @Test
    void aShaftPastReachIsNotADip() {
        trunk(0, 0);
        for (int y = BASE - Approach.REACH; y < BASE; y++) {
            probe.set(0, y, -1, BlockKind.AIR);
        }
        assertEquals("down " + Approach.REACH, side(survey(), "N").describe(),
                "exactly REACH cells down is still a dip");

        probe.set(0, BASE - Approach.REACH - 1, -1, BlockKind.AIR);
        Approach.Side north = side(survey(), "N");
        assertEquals(Approach.Verdict.TOO_DEEP, north.verdict());
        assertNull(north.feet());
    }

    @Test
    void leavesWhereTheBodyWouldStandAreListed() {
        trunk(0, 0);
        probe.set(0, BASE, 1, BlockKind.LEAVES);
        probe.set(0, BASE + 1, 1, BlockKind.LEAVES);

        Approach.Side south = side(survey(), "S");
        assertEquals(Approach.Verdict.LEAVES, south.verdict());
        assertEquals(new Pos(0, BASE, 1), south.feet(), "the feet go where the leaves are now");
        assertEquals(List.of(new Pos(0, BASE, 1), new Pos(0, BASE + 1, 1)), south.leaves());
        assertEquals("leaves", south.describe());
        assertTrue(south.verdict().approachable(), "clearable, so still a way in");
    }

    @Test
    void leavesOnTheWayDownAreListedToo() {
        trunk(0, 0);
        probe.set(0, BASE, -1, BlockKind.LEAVES);
        probe.set(0, BASE - 1, -1, BlockKind.AIR);

        Approach.Side north = side(survey(), "N");
        assertEquals(Approach.Verdict.LEAVES, north.verdict());
        assertEquals(new Pos(0, BASE - 1, -1), north.feet());
        assertEquals(List.of(new Pos(0, BASE, -1)), north.leaves(),
                "a body cannot drop into the dip through the leaf over it");
        assertEquals("leaves down 1", north.describe());
    }

    @Test
    void waterIsRefusedAboveAndBelow() {
        trunk(0, 0);
        probe.set(0, BASE - 1, -1, BlockKind.WATER);
        probe.set(1, BASE, 0, BlockKind.WATER);

        Approach approach = survey();
        assertEquals(Approach.Verdict.WATER, side(approach, "N").verdict());
        assertEquals(Approach.Verdict.WATER, side(approach, "E").verdict());
        assertEquals(2, approach.approachable());
    }

    @Test
    void aRoofOverTheStepIsNoRoom() {
        trunk(0, 0);
        probe.set(1, BASE, 0, BlockKind.OTHER);
        probe.set(1, BASE + 2, 0, BlockKind.OTHER);

        Approach.Side east = side(survey(), "E");
        assertEquals(Approach.Verdict.NO_ROOM, east.verdict(),
                "one cell of air over the step is not room for a two-cell body");
        assertNull(east.feet());
    }

    @Test
    void woodOnTopOfTheStepIsNotAPlaceToStand() {
        trunk(0, 0);
        probe.set(1, BASE, 0, BlockKind.OTHER);
        probe.set(1, BASE + 1, 0, BlockKind.LOG);

        assertEquals(Approach.Verdict.WOOD, side(survey(), "E").verdict());
    }

    @Test
    void aGiantHasEightSides() {
        trunk(0, 0);
        trunk(1, 0);
        trunk(0, 1);
        trunk(1, 1);

        Approach approach = survey();
        assertEquals(4, approach.base().size(), "a 2×2 stump is one base");
        assertEquals(8, approach.sides().size());
        assertEquals(8, approach.approachable());
        assertEquals("N open · N open · E open · E open · S open · S open · W open · W open",
                approach.summary());
    }

    @Test
    void aFifthLogAgainstAGiantIsWood() {
        trunk(0, 0);
        trunk(1, 0);
        trunk(0, 1);
        trunk(1, 1);
        probe.set(2, BASE, 0, BlockKind.LOG);

        Approach approach = survey();
        assertEquals(4, approach.base().size(), "the base never grows past a 2×2");
        assertEquals(Approach.Verdict.WOOD, approach.sides().stream()
                .filter(side -> side.cell().equals(new Pos(2, BASE, 0)))
                .findFirst().orElseThrow().verdict());
    }

    @Test
    void anUnloadedSideIsUnseen() {
        trunk(0, 0);
        probe.markUnloaded(-1, 0);

        Approach.Side west = side(survey(), "W");
        assertEquals(Approach.Verdict.UNSEEN, west.verdict());
        assertNull(west.feet());
    }

    @Test
    void aFelledAnchorSaysSo() {
        Approach approach = survey();

        assertFalse(approach.standing());
        assertTrue(approach.summary().startsWith("no log at the anchor; "),
                "the sides are still read — the readout says what is there");
        assertEquals(4, approach.sides().size());
    }
}
