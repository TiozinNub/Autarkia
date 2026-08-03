package dev.luizloyola.autarkia.core.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The chop's dance card. Under the grounding invariant (a floating remnant must be impossible)
 * the pillar stands BESIDE the tree: nothing of the trunk is consumed by any ascent, every log
 * is a move, and short trunks plan no pillar at all.
 */
class ChopPlanTest {

    private static List<Pos> column(int x, int z, int fromY, int toY) {
        List<Pos> cells = new ArrayList<>();
        for (int y = fromY; y <= toY; y++) {
            cells.add(new Pos(x, y, z));
        }
        return cells;
    }

    private static ChopPlan.Move moveFor(ChopPlan plan, Pos target) {
        for (ChopPlan.Layer layer : plan.layers()) {
            for (ChopPlan.Move move : layer.moves()) {
                if (move.target().equals(target)) {
                    return move;
                }
            }
        }
        return null;
    }

    /** Every cell the card brings down, however it is charged: targets and bonus digs alike. */
    private static Set<Pos> felled(ChopPlan plan) {
        Set<Pos> cells = new HashSet<>();
        for (ChopPlan.Layer layer : plan.layers()) {
            for (ChopPlan.Move move : layer.moves()) {
                cells.add(move.target());
                cells.addAll(move.digs());
            }
        }
        return cells;
    }

    private static int layerOf(ChopPlan plan, Pos target) {
        for (ChopPlan.Layer layer : plan.layers()) {
            for (ChopPlan.Move move : layer.moves()) {
                if (move.target().equals(target)) {
                    return layer.y();
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    @Test
    void aPlainTallTrunkClimbsItself() {
        // A plain trunk climbs itself (break the log overhead, rise on the log it dropped), so
        // the ascent is the fell and no layer has work left. Nothing carried in: the tree pays
        // for its own ladder.
        TreeShape.Trunk birch = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 66),
                List.of(), List.of(new Pos(0, 67, 0), new Pos(1, 66, 0)));

        ChopPlan plan = ChopPlan.of(birch);

        assertTrue(plan.climbsTheTrunk(), "a plain trunk is its own elevator shaft");
        assertEquals(new Pos(0, 60, 0), plan.mast().get(0), "the doorway is the stump");
        assertTrue(plan.mast().stream().allMatch(c -> c.x() == 0 && c.z() == 0),
                "the shaft is the trunk, cell for cell");
        assertEquals(5, plan.mast().size(),
                "rungs from the stump to two below the top — the last two are headroom");
        assertEquals(7, plan.ascentChops(), "all seven logs come down on the way up");
        assertTrue(plan.layers().isEmpty(), "nothing is left standing to work a layer for");
        assertTrue(plan.refusals().isEmpty());
    }

    @Test
    void aBranchedTrunkKeepsThePillarBesideIt() {
        // An interrupted in-trunk climb could strand a branch in the canopy, so one branch sends
        // the mast OUTSIDE the footprint: the trunk stands grounded to its last log, every log a
        // move.
        TreeShape.Trunk branched = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 66),
                List.of(new Pos(1, 65, 0)),
                List.of(new Pos(0, 67, 0), new Pos(2, 65, 0)));

        ChopPlan plan = ChopPlan.of(branched);

        assertFalse(plan.climbsTheTrunk(), "wood hangs off it: the pillar stands beside");
        assertTrue(plan.mast().stream().allMatch(c -> c.x() == -1 && c.z() == 0),
                "the pillar stands beside the trunk, never in it");
        assertEquals(2, plan.mast().size(),
                "raised to the arm, not the treetop: two rungs put the top inside a swing");
        assertEquals(0, plan.ascentChops(), "the ascent beside the tree takes none of it");
        assertTrue(plan.refusals().isEmpty());
        Set<Pos> felled = felled(plan);
        for (Pos log : column(0, 0, 60, 66)) {
            assertTrue(felled.contains(log), "the trunk comes down entirely: " + log);
        }
        assertTrue(felled.contains(new Pos(1, 65, 0)), "and so does the branch");
        int lastY = Integer.MAX_VALUE;
        for (ChopPlan.Layer layer : plan.layers()) {
            assertTrue(layer.y() < lastY, "layers descend");
            lastY = layer.y();
            for (ChopPlan.Move move : layer.moves()) {
                assertEquals(new Pos(-1, layer.y(), 0), move.stand(),
                        "every swing comes from atop the pillar, face to face with the trunk");
            }
        }
    }

    @Test
    void aFloatingRemnantIsNeverClimbedThroughItsOwnFoot() {
        // A remnant: its lowest log is in mid-air, so there is no stump to cut a doorway in and
        // nothing to walk to. Plain or not, a remnant gets the mast beside it.
        TreeShape.Trunk remnant = new TreeShape.Trunk(
                List.of(new Pos(0, 66, 0)), column(0, 0, 67, 72),
                List.of(), List.of(new Pos(0, 73, 0)));

        assertTrue(ChopPlan.of(remnant).climbsTheTrunk(),
                "standing on the ground, a plain trunk climbs itself");
        assertFalse(ChopPlan.of(remnant, false).climbsTheTrunk(),
                "hanging in the air, the same shape takes the pillar beside it");
    }

    @Test
    void noPlainTreeEverAsksForWoodItDoesNotOwn() {
        // A plain trunk is ground work or its own elevator at any height; the pillar and its bill
        // of prepaid carried logs belong to trees with wood hanging off them. Quoting a plain
        // trunk a pillar left an empty pack unable to begin the one tree it could fell.
        for (int height = 1; height <= 12; height++) {
            TreeShape.Trunk plain = new TreeShape.Trunk(
                    List.of(new Pos(0, 60, 0)), column(0, 0, 61, 60 + height),
                    List.of(), List.of(new Pos(0, 61 + height, 0)));

            ChopPlan plan = ChopPlan.of(plain);

            assertTrue(plan.mast().isEmpty() || plan.climbsTheTrunk(),
                    "a plain trunk of " + height + " raised a pillar it would have to pay for");
            assertTrue(plan.refusals().isEmpty(), "a plain trunk of " + height + " refused wood");
            Set<Pos> covered = new HashSet<>(plan.mast());
            for (ChopPlan.Layer layer : plan.layers()) {
                for (ChopPlan.Move move : layer.moves()) {
                    covered.add(move.target());
                    covered.addAll(move.digs());
                }
            }
            if (plan.climbsTheTrunk()) {
                Pos last = plan.mast().get(plan.mast().size() - 1);
                covered.add(new Pos(last.x(), last.y() + 1, last.z()));
                covered.add(new Pos(last.x(), last.y() + 2, last.z()));
            }
            for (Pos log : column(0, 0, 60, 60 + height)) {
                assertTrue(covered.contains(log),
                        "a plain trunk of " + height + " leaves " + log + " standing");
            }
        }
    }

    @Test
    void aShortTrunkPlansNoPillarAtAll() {
        // Three logs up: the arm serves everything from the ground; a pillar would be wasted
        // wood and wasted time, and the ascent's carried-log price should never be charged.
        TreeShape.Trunk sapling = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 63),
                List.of(), List.of(new Pos(0, 64, 0)));

        ChopPlan plan = ChopPlan.of(sapling);

        assertTrue(plan.mast().isEmpty(), "short trees are ground work");
        assertTrue(plan.refusals().isEmpty());
        Set<Pos> covered = new HashSet<>();
        for (ChopPlan.Layer layer : plan.layers()) {
            for (ChopPlan.Move move : layer.moves()) {
                covered.add(move.target());
                covered.addAll(move.digs());
            }
        }
        for (Pos log : column(0, 0, 60, 63)) {
            assertTrue(covered.contains(log), "every log is served, as a move or a bonus: " + log);
        }
    }

    @Test
    void aGiantsColumnsAreAllServedFromTheOnePillar() {
        // The pillar picks a column OUTSIDE the footprint (the giant's base cells are excluded as
        // sites), and each of the forty-four logs is a move within arm's reach, planned once.
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

        assertFalse(plan.climbsTheTrunk(), "four columns are not one plain trunk");
        assertEquals(6, plan.mast().size(),
                "raised to the arm: the last rung sees the top of all four columns");
        assertTrue(plan.mast().stream().allMatch(c -> c.x() == -1 && c.z() == 0));
        assertTrue(plan.refusals().isEmpty());
        Set<Pos> felled = new HashSet<>();
        int lastY = Integer.MAX_VALUE;
        for (ChopPlan.Layer layer : plan.layers()) {
            assertTrue(layer.y() < lastY, "layers descend");
            lastY = layer.y();
            for (ChopPlan.Move move : layer.moves()) {
                assertEquals(new Pos(-1, layer.y(), 0), move.stand(),
                        "every swing comes from atop the pillar");
                assertTrue(felled.add(move.target()), "each log is planned once");
                for (Pos dig : move.digs()) {
                    assertTrue(felled.add(dig), "each log is planned once");
                }
            }
        }
        Set<Pos> expected = new HashSet<>(base);
        expected.addAll(columns);
        assertTrue(felled.containsAll(expected), "the whole giant comes down");
        felled.removeAll(expected);
        assertTrue(List.of(new Pos(0, 71, 0)).containsAll(felled),
                "anything extra is canopy cleared off a swing line, never foreign blocks");
    }

    @Test
    void aFarBranchGrowsATunnelOnlyAsFarAsTheArmFallsShort() {
        // The tunnel digs outward (eating trunk cells as bonus chops) only until the target is
        // inside REACH. High on the trunk on purpose: lower down a ground swing still lands, so
        // there is no tunnel to grow.
        TreeShape.Trunk oak = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 71),
                List.of(new Pos(2, 70, 0), new Pos(4, 70, 0)),
                List.of(new Pos(1, 70, 0), new Pos(3, 70, 0),
                        new Pos(1, 69, 0), new Pos(2, 69, 0), new Pos(3, 69, 0)));

        ChopPlan plan = ChopPlan.of(oak);

        assertTrue(plan.refusals().isEmpty());
        ChopPlan.Move far = moveFor(plan, new Pos(4, 70, 0));
        assertNotNull(far);
        assertEquals(new Pos(1, 70, 0), far.stand(), "one step past the trunk is inside reach");
        assertTrue(far.digs().contains(new Pos(1, 70, 0)),
                "the tunnel digs the leaf it stands on");
        assertTrue(far.digs().contains(new Pos(3, 70, 0)), "the swing-line leaf is dug");
        assertFalse(far.leap());
        assertTrue(felled(plan).contains(new Pos(0, 70, 0)),
                "the trunk cell in the way rides as a bonus chop, on whichever swing meets it");
        ChopPlan.Move near = moveFor(plan, new Pos(2, 70, 0));
        assertNotNull(near);
        assertEquals(new Pos(-1, 67, 0), near.stand(),
                "the near log needs no tunnel at all — the pillar top already sees it");
    }

    @Test
    void aOneCellFloorHoleIsALeapATwoCellHoleRefuses() {
        // The dig rule's single allowance, unchanged by the pillar's move outdoors.
        List<Pos> floorWithHole = List.of(new Pos(1, 68, 0), new Pos(2, 68, 0),
                new Pos(4, 68, 0), new Pos(5, 68, 0));
        TreeShape.Trunk leapable = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 70),
                List.of(new Pos(7, 69, 0)), floorWithHole);

        ChopPlan plan = ChopPlan.of(leapable);

        assertTrue(plan.refusals().isEmpty());
        ChopPlan.Move move = moveFor(plan, new Pos(7, 69, 0));
        assertNotNull(move);
        assertTrue(move.leap(), "one hole in the canopy floor is a jump");
        assertEquals(new Pos(4, 69, 0), move.stand(), "the first floored cell inside reach");

        TreeShape.Trunk gapped = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 70),
                List.of(new Pos(7, 69, 0)), List.of(new Pos(1, 68, 0), new Pos(2, 68, 0)));

        ChopPlan refused = ChopPlan.of(gapped);

        assertEquals(1, refused.refusals().size());
        assertEquals(ChopPlan.Reason.NO_FLOOR, refused.refusals().get(0).reason());
        assertEquals(new Pos(7, 69, 0), refused.refusals().get(0).cell());
        assertFalse(refused.layers().isEmpty(), "the trunk itself is still planned");
    }

    @Test
    void aHoledFloorIsWalkedAroundNotRefused() {
        // The tunnel is a search: with no floor on the straight ray it goes around, through the
        // trunk, which rides as digs. High on the trunk again — a canopy hole only matters where
        // the canopy is the floor.
        TreeShape.Trunk oak = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 71),
                List.of(new Pos(5, 70, 0)),
                List.of(new Pos(0, 69, 1), new Pos(1, 69, 1), new Pos(2, 69, 1)));

        ChopPlan plan = ChopPlan.of(oak);

        assertTrue(plan.refusals().isEmpty(), "a dogleg exists, so no refusal");
        ChopPlan.Move move = moveFor(plan, new Pos(5, 70, 0));
        assertNotNull(move);
        assertEquals(new Pos(2, 70, 1), move.stand(), "the floored lane one step south");
        assertFalse(move.leap(), "walked around, never jumped");
    }

    @Test
    void aBendingTrunksTipCostsOneExtraBlock() {
        // A short trunk is mast-free, and the bend's tip sits a hair past a swing from the site
        // level — one placed log underfoot brings it home, and only the tip pays the block.
        TreeShape.Trunk bent = new TreeShape.Trunk(
                List.of(new Pos(2, 60, 0)), column(2, 0, 61, 62),
                List.of(new Pos(1, 63, 0), new Pos(0, 64, 0), new Pos(0, 65, 0),
                        new Pos(0, 66, 0)),
                List.of(new Pos(0, 67, 0)));

        ChopPlan plan = ChopPlan.of(bent);

        assertTrue(plan.mast().isEmpty(), "a two-log trunk is ground work");
        assertTrue(plan.refusals().isEmpty(), "one placed block serves the whole bend");
        ChopPlan.Move tip = moveFor(plan, new Pos(0, 66, 0));
        assertNotNull(tip);
        assertTrue(tip.boost(), "the tip needs the one-block budget");
        for (ChopPlan.Layer layer : plan.layers()) {
            for (ChopPlan.Move move : layer.moves()) {
                if (!move.target().equals(tip.target())) {
                    assertFalse(move.boost(), "the budget is spent only where the arm fails");
                }
            }
        }
    }

    @Test
    void woodAboveTheTrunkTopIsServedByClimbingTheSite() {
        // The acacia signature: a stubby trunk (mast-free), wood far above. The escalation
        // climbs the site level by level until the drifted tip comes inside a swing.
        TreeShape.Trunk acacia = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 62),
                List.of(new Pos(1, 63, 1), new Pos(2, 68, 2)),
                List.of(new Pos(2, 69, 2)));

        ChopPlan plan = ChopPlan.of(acacia);

        assertTrue(plan.refusals().isEmpty(), "the climb serves what a ground swing cannot");
        Set<Pos> covered = new HashSet<>();
        for (ChopPlan.Layer layer : plan.layers()) {
            for (ChopPlan.Move move : layer.moves()) {
                covered.add(move.target());
                covered.addAll(move.digs());
            }
        }
        assertTrue(covered.contains(new Pos(1, 63, 1)), "the diagonal step is served");
        Pos tip = new Pos(2, 68, 2);
        assertNotNull(moveFor(plan, tip));
        assertTrue(layerOf(plan, tip) > 62, "the tip's layer stands above the trunk top");
    }

    @Test
    void aFarLevelLogIsSwungAtFromTheDenserFloorBelow() {
        // The fancy-oak top-blob signature: a branch at trunk-top height, five out, no floor
        // at its own level — served by walking down into the dense blob floor and swinging up.
        TreeShape.Trunk oak = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 70),
                List.of(new Pos(0, 69, 5)),
                List.of(new Pos(0, 67, 1), new Pos(0, 67, 2), new Pos(0, 67, 3),
                        new Pos(0, 67, 4)));

        ChopPlan plan = ChopPlan.of(oak);

        assertTrue(plan.refusals().isEmpty(), "the floor below serves what its own level lacks");
        ChopPlan.Move move = moveFor(plan, new Pos(0, 69, 5));
        assertNotNull(move);
        assertEquals(new Pos(0, 68, 2), move.stand(), "swinging upward from the blob floor");
        assertFalse(move.boost(), "walking lower costs nothing");
    }

    @Test
    void lowBranchesBelowTheBaseAreGroundWork() {
        TreeShape.Trunk downhill = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 64),
                List.of(new Pos(2, 59, 0)), List.of(new Pos(0, 65, 0)));

        ChopPlan plan = ChopPlan.of(downhill);

        assertTrue(plan.refusals().isEmpty());
        assertEquals(60, layerOf(plan, new Pos(2, 59, 0)),
                "feet never plan below ground level");
    }

    @Test
    void theSameShapeAlwaysCompilesTheSameDance() {
        TreeShape.Trunk tree = new TreeShape.Trunk(
                List.of(new Pos(0, 60, 0)), column(0, 0, 61, 65),
                List.of(new Pos(2, 64, 0), new Pos(-2, 64, 0), new Pos(0, 63, 2)),
                List.of(new Pos(1, 64, 0), new Pos(-1, 64, 0), new Pos(0, 63, 1),
                        new Pos(1, 63, 0), new Pos(-1, 63, 0), new Pos(0, 62, 1)));

        assertEquals(ChopPlan.of(tree), ChopPlan.of(tree));
    }
}
