package dev.luizloyola.autarkia.core.earthwork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.terrain.NaturalGround;
import dev.luizloyola.autarkia.core.earthwork.FlattenPlan.Column;
import dev.luizloyola.autarkia.core.earthwork.FlattenPlan.Refusal;
import dev.luizloyola.autarkia.core.earthwork.FlattenPlan.Rules;
import dev.luizloyola.autarkia.core.earthwork.FlattenPlan.Why;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

class FlattenPlanTest {

    /** The area is [0, 15]²; the scan reaches the ring's 8 past it. */
    private static final int LO = 0;
    private static final int HI = 15;
    private static final int RING = 8;

    private static NaturalGround scan(IntBinaryOperator height) {
        NaturalGround ground = new NaturalGround(LO - RING, LO - RING, HI - LO + 1 + 2 * RING,
                HI - LO + 1 + 2 * RING);
        for (int x = LO - RING; x <= HI + RING; x++) {
            for (int z = LO - RING; z <= HI + RING; z++) {
                ground.set(x, z, height.applyAsInt(x, z), 0);
            }
        }
        return ground;
    }

    private static FlattenPlan plan(NaturalGround scan, int tolerance, OptionalInt target) {
        return FlattenPlan.of(scan, LO, LO, HI, HI, new Rules(tolerance, target, 2, RING));
    }

    /** Every column's height once the plan is done: its goal where it has one, else its ground. */
    private static IntBinaryOperator after(NaturalGround scan, FlattenPlan plan) {
        Map<Long, Integer> goals = new HashMap<>();
        for (Column c : plan.columns()) {
            goals.put(key(c.x(), c.z()), c.goal());
        }
        return (x, z) -> goals.getOrDefault(key(x, z), scan.groundAt(x, z));
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    /**
     * No two neighbours, anywhere the scan reaches, differ by more than {@code most} — or by more
     * than the land did before, which the plan has no business smoothing outside its reach.
     */
    private static void assertSteps(NaturalGround scan, FlattenPlan plan, int most) {
        IntBinaryOperator h = after(scan, plan);
        for (int x = LO - RING; x < HI + RING; x++) {
            for (int z = LO - RING; z < HI + RING; z++) {
                int east = Math.max(most, Math.abs(scan.groundAt(x, z) - scan.groundAt(x + 1, z)));
                int south = Math.max(most, Math.abs(scan.groundAt(x, z) - scan.groundAt(x, z + 1)));
                assertTrue(Math.abs(h.applyAsInt(x, z) - h.applyAsInt(x + 1, z)) <= east,
                        "step at " + x + ", " + z + " east");
                assertTrue(Math.abs(h.applyAsInt(x, z) - h.applyAsInt(x, z + 1)) <= south,
                        "step at " + x + ", " + z + " south");
            }
        }
    }

    @Test
    void flatGroundNeedsNothing() {
        FlattenPlan plan = plan(scan((x, z) -> 64), 1, OptionalInt.empty());
        assertFalse(plan.refused());
        assertEquals(64, plan.y());
        assertTrue(plan.columns().isEmpty());
    }

    /** Inside the band, but a spike: smoothed away. */
    @Test
    void aOneBlockBumpIsSmoothedAway() {
        NaturalGround scan = scan((x, z) -> x == 7 && z == 7 ? 65 : 64);
        FlattenPlan plan = plan(scan, 1, OptionalInt.empty());
        assertEquals(1, plan.columns().size());
        Column bump = plan.columns().get(0);
        assertEquals(7, bump.x());
        assertEquals(64, bump.goal());
    }

    /** One side higher than the other, within the band: a slope may stay a slope. */
    @Test
    void aStepWithinTheBandIsKeptAndBalancesAtItsMiddle() {
        NaturalGround scan = scan((x, z) -> x < 8 ? 64 : 66);
        FlattenPlan plan = plan(scan, 1, OptionalInt.empty());
        assertEquals(64, plan.median());
        assertEquals(65, plan.y());
        assertEquals(Why.BALANCED, plan.why());
        assertTrue(plan.columns().isEmpty());
    }

    @Test
    void aToleranceOfZeroLevelsToTheMedian() {
        NaturalGround scan = scan((x, z) -> x < 8 ? 64 : 66);
        FlattenPlan plan = plan(scan, 0, OptionalInt.empty());
        assertEquals(Why.MEDIAN, plan.why());
        for (Column c : plan.columns()) {
            if (c.x() >= LO && c.x() <= HI && c.z() >= LO && c.z() <= HI) {
                assertEquals(64, c.goal());
            }
        }
        assertSteps(scan, plan, 1);
    }

    /** A plateau above the band is cut to the band's top edge, not the target. */
    @Test
    void aColumnOutsideTheBandGoesToItsEdge() {
        NaturalGround scan = scan((x, z) -> 70);
        FlattenPlan plan = plan(scan, 1, OptionalInt.of(64));
        assertEquals(Why.GIVEN, plan.why());
        assertFalse(plan.refused());
        IntBinaryOperator h = after(scan, plan);
        for (int x = LO; x <= HI; x++) {
            for (int z = LO; z <= HI; z++) {
                assertEquals(65, h.applyAsInt(x, z));
            }
        }
    }

    /** A pad cut 5 into a plateau eases out to the land round it, never more than a block a step. */
    @Test
    void theRingEasesThePadIntoTheLand() {
        NaturalGround scan = scan((x, z) -> 70);
        FlattenPlan plan = plan(scan, 1, OptionalInt.of(64));
        IntBinaryOperator h = after(scan, plan);
        assertTrue(h.applyAsInt(HI + 1, 7) < 70, "the ring starts next to the pad");
        assertEquals(70, h.applyAsInt(HI + RING, 7), "and has met the land by its end");
        assertSteps(scan, plan, 1);
    }

    @Test
    void aStepTooHighForTheRingIsAHillside() {
        NaturalGround scan = scan((x, z) -> 80);
        FlattenPlan plan = plan(scan, 0, OptionalInt.of(64));
        assertTrue(plan.refused());
        assertTrue(plan.refusals().stream().allMatch(r -> r.why() == Refusal.HILLSIDE));
        assertTrue(plan.columns().isEmpty());
    }

    @Test
    void aTreeInTheAreaRefusesUntilItIsCleared() {
        NaturalGround scan = scan((x, z) -> 64);
        scan.set(3, 4, 64, NaturalGround.TREE);
        FlattenPlan plan = plan(scan, 1, OptionalInt.empty());
        assertEquals(1, plan.refusals().size());
        assertEquals(Refusal.TREE, plan.refusals().get(0).why());
        assertEquals(3, plan.refusals().get(0).x());
    }

    @Test
    void waterInTheAreaRefuses() {
        NaturalGround scan = scan((x, z) -> 64);
        scan.set(9, 9, 62, NaturalGround.FLUID);
        assertEquals(Refusal.FLUID, plan(scan, 1, OptionalInt.empty()).refusals().get(0).why());
    }

    @Test
    void anUnseenColumnInTheAreaRefuses() {
        NaturalGround scan = scan((x, z) -> 64);
        scan.set(9, 9, NaturalGround.UNKNOWN, 0);
        assertEquals(Refusal.UNSEEN, plan(scan, 1, OptionalInt.empty()).refusals().get(0).why());
    }

    /** The ring outside, inverted: the pad round a built column eases to its ground. */
    @Test
    void aBuiltColumnKeepsItsNeighbours() {
        NaturalGround scan = scan((x, z) -> 64);
        scan.set(7, 7, 64, NaturalGround.BUILT);
        FlattenPlan plan = plan(scan, 0, OptionalInt.of(60));
        assertFalse(plan.refused(), () -> plan.refusals().toString());
        assertEquals(1, plan.skipped());
        IntBinaryOperator h = after(scan, plan);
        assertTrue(plan.columns().stream().noneMatch(c -> c.x() == 7 && c.z() == 7));
        assertEquals(64, h.applyAsInt(7, 7));
        assertTrue(h.applyAsInt(8, 7) >= 63, "its neighbour is not cut from under it");
        assertEquals(60, h.applyAsInt(0, 0), "far from it the pad is level");
        for (int x = 7; x < HI; x++) {
            assertTrue(Math.abs(h.applyAsInt(x, 7) - h.applyAsInt(x + 1, 7)) <= 1, "step at " + x);
        }
    }

    @Test
    void anAreaAllBuiltHasNothingToLevel() {
        NaturalGround scan = new NaturalGround(LO - RING, LO - RING, HI + 1 + 2 * RING, HI + 1 + 2 * RING);
        for (int x = LO - RING; x <= HI + RING; x++) {
            for (int z = LO - RING; z <= HI + RING; z++) {
                scan.set(x, z, 64, NaturalGround.BUILT);
            }
        }
        assertEquals(Refusal.NOTHING, plan(scan, 1, OptionalInt.empty()).refusals().get(0).why());
    }

    /** A half buried under spoil and a half cut: what comes out goes back in. */
    @Test
    void cutAndFillBalanceWhenTheMedianAllows() {
        NaturalGround scan = scan((x, z) -> x < 8 ? 62 : 67);
        FlattenPlan plan = plan(scan, 2, OptionalInt.empty());
        assertFalse(plan.refused(), () -> plan.refusals().toString());
        assertTrue(Math.abs(plan.cut() - plan.fill()) <= Math.abs(
                plan(scan, 0, OptionalInt.empty()).cut() - plan(scan, 0, OptionalInt.empty()).fill()));
        assertTrue(Math.abs(plan.y() - plan.median()) <= 2);
    }

    @Test
    void easeIsFlatAtBothEnds() {
        assertEquals(0.0, FlattenPlan.ease(0), 1e-9);
        assertEquals(1.0, FlattenPlan.ease(1), 1e-9);
        assertEquals(0.5, FlattenPlan.ease(0.5), 1e-9);
        assertEquals(0, FlattenPlan.blendWidth(0));
        assertEquals(1, FlattenPlan.blendWidth(1));
        assertEquals(7, FlattenPlan.blendWidth(5));
    }
}
