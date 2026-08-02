package dev.luizloyola.autarkia.core.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        // Every non-mast log is accounted for exactly once — as a move's target, or as a bonus
        // chop riding another swing's line — and no leaf is ever dug for a giant's own trunk.
        Set<Pos> felled = new HashSet<>();
        int lastY = Integer.MAX_VALUE;
        for (ChopPlan.Layer layer : plan.layers()) {
            assertTrue(layer.y() < lastY, "layers descend");
            lastY = layer.y();
            for (ChopPlan.Move move : layer.moves()) {
                assertEquals(new Pos(0, layer.y(), 0), move.stand(),
                        "every swing comes from atop the mast");
                assertTrue(felled.add(move.target()), "each log is planned once");
                for (Pos dig : move.digs()) {
                    assertTrue(felled.add(dig), "each log is planned once");
                }
            }
        }
        Set<Pos> expected = new HashSet<>(base);
        expected.addAll(columns);
        plan.mast().forEach(expected::remove);
        assertEquals(expected, felled, "the whole giant comes down, and only the giant");
    }

    @Test
    void aFarBranchGrowsATunnelOnlyAsFarAsTheArmFallsShort() {
        // A branch log past arm's length. The tunnel digs outward only until the target comes
        // inside REACH, then the swing clears its own line — the leaf in the way lands in the
        // digs. The nearer branch is inside reach of the mast itself: a swing, no tunnel.
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
        assertEquals(2, layer.moves().size());
        ChopPlan.Move far = layer.moves().get(0);
        assertEquals(new Pos(4, 64, 0), far.target(), "outermost first");
        assertEquals(new Pos(1, 64, 0), far.stand(), "one step out brings it inside reach");
        assertTrue(far.digs().contains(new Pos(1, 64, 0)), "the tunnel's own cell is dug");
        assertTrue(far.digs().contains(new Pos(3, 64, 0)), "the leaf on the swing line is dug");
        assertFalse(far.leap());
        ChopPlan.Move near = layer.moves().get(1);
        assertEquals(new Pos(2, 64, 0), near.target());
        assertEquals(new Pos(0, 64, 0), near.stand(), "inside reach of the mast: no tunnel");
        assertTrue(near.digs().isEmpty());
    }

    @Test
    void aOneCellFloorHoleIsALeapATwoCellHoleRefuses() {
        // The dig rule's single allowance: one missing floor cell is a jump, two is no route.
        // The target sits far enough out that the tunnel must cross the hole, and high enough
        // that no floor below (not even the ground) can serve the swing instead. Same tree
        // twice, floor leaves apart.
        List<Pos> floorWithHole = List.of(new Pos(1, 68, 0), new Pos(2, 68, 0),
                new Pos(4, 68, 0), new Pos(5, 68, 0));
        TreeShape.Trunk leapable = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 70),
                List.of(new Pos(7, 69, 0)), floorWithHole);

        ChopPlan plan = ChopPlan.of(leapable);

        assertTrue(plan.refusals().isEmpty());
        ChopPlan.Move move = plan.layers().get(0).moves().get(0);
        assertTrue(move.leap(), "one hole in the canopy floor is a jump");
        assertEquals(new Pos(4, 69, 0), move.stand(), "the first floored cell inside reach");

        TreeShape.Trunk gapped = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 70),
                List.of(new Pos(7, 69, 0)), List.of(new Pos(1, 68, 0), new Pos(2, 68, 0)));

        ChopPlan refused = ChopPlan.of(gapped);

        assertTrue(refused.layers().isEmpty());
        assertEquals(1, refused.refusals().size());
        assertEquals(ChopPlan.Reason.NO_FLOOR, refused.refusals().get(0).reason());
        assertEquals(new Pos(7, 69, 0), refused.refusals().get(0).cell());
    }

    @Test
    void woodAboveTheTrunkTopIsServedByExtendingTheMast() {
        // The acacia signature: a stubby vertical column, wood climbing far above it. The mast
        // no longer stops where the trunk top broke — rise-one keeps going on her own logs,
        // level by level, until the drifted tip comes inside a swing.
        TreeShape.Trunk acacia = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 62),
                List.of(new Pos(1, 63, 1), new Pos(2, 68, 2)),
                List.of(new Pos(2, 69, 2)));

        ChopPlan plan = ChopPlan.of(acacia);

        assertTrue(plan.refusals().isEmpty(), "the extension serves what a swing cannot");
        assertEquals(2, plan.chopCount());
        ChopPlan.Layer top = plan.layers().get(0);
        assertTrue(top.y() > 61, "the tip's layer stands above the old trunk-top perch");
        ChopPlan.Move tip = top.moves().get(0);
        assertEquals(new Pos(2, 68, 2), tip.target());
        assertEquals(new Pos(1, 64, 1), tip.stand(),
                "leapt from the extended mast onto the branch itself — the diagonal log is her "
                        + "floor, and its own chop waits in a lower layer");
        assertTrue(tip.leap());
    }

    @Test
    void aFarHighBranchCostsOneMastBlockNotARefusal() {
        // Luiz's gauntlet find at (245,70), distilled: a five-log trunk, a branch three out
        // and five up — a boosted swing from the old perch misses by a hair, one mast block
        // higher lands it. The escalation finds the cheapest serving height.
        TreeShape.Trunk oak = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 64),
                List.of(new Pos(0, 68, 3)), List.of(new Pos(0, 69, 3)));

        ChopPlan plan = ChopPlan.of(oak);

        assertTrue(plan.refusals().isEmpty());
        ChopPlan.Layer top = plan.layers().get(0);
        assertEquals(64, top.y(), "one level above the old perch is enough");
        ChopPlan.Move move = top.moves().get(0);
        assertEquals(new Pos(0, 68, 3), move.target());
        assertTrue(move.boost(), "and the one-block boost rides on top of it");
        assertEquals(new Pos(0, 64, 0), move.stand());
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
    void aHoledFloorIsWalkedAroundNotRefused() {
        // The gauntlet oak's far fragment, distilled: the straight ray to the target crosses a
        // two-cell hole in the canopy floor, but a one-cell dogleg is fully floored. The
        // tunnel is a search, not a ray — it goes around.
        TreeShape.Trunk oak = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 65),
                List.of(new Pos(5, 64, 0)),
                List.of(new Pos(0, 63, 1), new Pos(1, 63, 1), new Pos(2, 63, 1)));

        ChopPlan plan = ChopPlan.of(oak);

        assertTrue(plan.refusals().isEmpty(), "a dogleg exists, so no refusal");
        ChopPlan.Move move = plan.layers().get(0).moves().get(0);
        assertEquals(new Pos(2, 64, 1), move.stand(), "the floored lane one step south");
        assertFalse(move.leap(), "walked around, never jumped");
    }

    @Test
    void aBendingTrunksTipCostsOneExtraBlock() {
        // Luiz's diagram, verbatim: the trunk bends away from the base column and its tip sits
        // a hair past a swing from atop the mast — but one of her own logs placed underfoot
        // brings it home. The second pass spends that one-block budget.
        TreeShape.Trunk bent = new TreeShape.Trunk(
                List.of(new Pos(2, 60, 0)), column(2, 0, 61, 62),
                List.of(new Pos(1, 63, 0), new Pos(0, 64, 0), new Pos(0, 65, 0),
                        new Pos(0, 66, 0)),
                List.of(new Pos(0, 67, 0)));

        ChopPlan plan = ChopPlan.of(bent);

        assertTrue(plan.refusals().isEmpty(), "one placed block serves the whole bend");
        ChopPlan.Move tip = null;
        for (ChopPlan.Layer layer : plan.layers()) {
            for (ChopPlan.Move move : layer.moves()) {
                if (move.target().equals(new Pos(0, 66, 0))) {
                    tip = move;
                } else {
                    assertFalse(move.boost(), "the budget is spent only where the arm fails");
                }
            }
        }
        assertTrue(tip != null && tip.boost(), "the tip needs the one-block budget");
        assertEquals(new Pos(2, 61, 0), tip.stand(), "boosted from atop the mast itself");
    }

    @Test
    void aFarLevelLogIsSwungAtFromTheDenserFloorBelow() {
        // The fancy oak's top-blob signature (Luiz's finds at 251,84 and 213,84): a branch at
        // trunk-top height, five out, with no canopy floor at its own level — but the blob one
        // level down is dense. The escalation walks down into it and swings upward, free of
        // charge, instead of refusing.
        TreeShape.Trunk oak = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 70),
                List.of(new Pos(0, 69, 5)),
                List.of(new Pos(0, 67, 1), new Pos(0, 67, 2), new Pos(0, 67, 3),
                        new Pos(0, 67, 4)));

        ChopPlan plan = ChopPlan.of(oak);

        assertTrue(plan.refusals().isEmpty(), "the floor below serves what its own level lacks");
        ChopPlan.Layer layer = plan.layers().get(0);
        assertEquals(68, layer.y(), "one level below the target, on the dense floor");
        ChopPlan.Move move = layer.moves().get(0);
        assertEquals(new Pos(0, 69, 5), move.target());
        assertEquals(new Pos(0, 68, 2), move.stand(), "swinging upward from the blob floor");
        assertFalse(move.boost(), "walking lower costs nothing");
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
