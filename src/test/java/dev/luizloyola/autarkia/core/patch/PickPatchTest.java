package dev.luizloyola.autarkia.core.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.BreakBlock;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.GatherNearbyDrops;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.Try;
import dev.luizloyola.anima.core.brain.task.UseBlock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a trip picks once it is at the patch: what is ripe there, as it looks on arrival. */
class PickPatchTest {

    private static final Pos ANCHOR = new Pos(0, 64, 0);

    private final FakeContext ctx = new FakeContext();

    PickPatchTest() {
        ctx.percepts.position = new Pos(-1, 64, 0);
    }

    private void plant(Pos at, dev.luizloyola.anima.core.brain.knowledge.BlockKind kind) {
        ctx.percepts.blocks.set(at.x(), at.y(), at.z(), kind);
    }

    private List<Task> plan(PickPatch patch) {
        return patch.methods().get(0).decompose(ctx);
    }

    private static Task inside(Task step) {
        return assertInstanceOf(Try.class, step).attempt();
    }

    @Test
    void onlyRipeBushesArePicked() {
        Pos ripe = new Pos(2, 64, 1);
        plant(ripe, Patches.RIPE_SWEET_BERRIES);
        plant(new Pos(1, 64, -2), Patches.SWEET_BERRIES);

        assertEquals(List.of(ripe), new PickPatch(Patches.BERRIES, ANCHOR).ripeCells(ctx),
                "a bush with no berries on it is not worth the walk");
    }

    @Test
    void theWalkGoesFromEachBushToTheNearestNext() {
        Pos first = new Pos(1, 64, 0);
        Pos far = new Pos(7, 64, 0);
        Pos second = new Pos(3, 64, 0);
        for (Pos at : List.of(far, first, second)) {
            plant(at, Patches.RIPE_SWEET_BERRIES);
        }

        assertEquals(List.of(first, second, far), new PickPatch(Patches.BERRIES, ANCHOR).ripeCells(ctx));
    }

    @Test
    void oneTripPicksAtMostSoMany() {
        for (int x = 1; x <= PickPatch.MOST + 3; x++) {
            plant(new Pos(x - 4, 64, 3), Patches.RIPE_SWEET_BERRIES);
        }
        assertEquals(PickPatch.MOST, new PickPatch(Patches.BERRIES, ANCHOR).ripeCells(ctx).size());
    }

    @Test
    void aBushIsPickedByHandFromBesideIt() {
        Pos bush = new Pos(2, 64, 1);
        plant(bush, Patches.RIPE_SWEET_BERRIES);

        List<Task> steps = plan(new PickPatch(Patches.BERRIES, ANCHOR));

        assertEquals(3, steps.size(), "walk, pick, sweep");
        assertInstanceOf(GoTo.class, inside(steps.get(0)));
        assertEquals(bush, assertInstanceOf(UseBlock.class, inside(steps.get(1))).target());
        assertInstanceOf(GatherNearbyDrops.class, inside(steps.get(2)));
    }

    @Test
    void aMelonIsBrokenAndItsStemLeftToGrowAnother() {
        Pos melon = new Pos(2, 64, 1);
        plant(melon, Patches.MELON);

        List<Task> steps = plan(new PickPatch(Patches.MELONS, ANCHOR));

        assertEquals(melon, assertInstanceOf(BreakBlock.class, inside(steps.get(1))).target());
    }

    @Test
    void aPatchWithNothingRipeIsDoneAtOnce() {
        plant(new Pos(2, 64, 1), Patches.SWEET_BERRIES);
        assertTrue(plan(new PickPatch(Patches.BERRIES, ANCHOR)).isEmpty());
    }

    @Test
    void everyStepIsATrySoOneBareBushCostsOnlyItself() {
        List<Pos> bushes = new ArrayList<>(List.of(new Pos(1, 64, 0), new Pos(3, 64, 0)));
        bushes.forEach(at -> plant(at, Patches.RIPE_SWEET_BERRIES));

        for (Task step : plan(new PickPatch(Patches.BERRIES, ANCHOR))) {
            assertInstanceOf(Try.class, step);
        }
    }
}
