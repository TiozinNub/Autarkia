package dev.luizloyola.autarkia.core.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Drop;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.BreakBlock;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.GatherNearbyDrops;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.Try;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** One harvester for what grows up (Luiz, 2026-10-03): the bottom kept, a cactus never touched. */
class CutStalksTest {

    private static final int G = FakeProbe.GROUND_Y;
    private static final Pos ANCHOR = new Pos(0, G + 1, 0);

    private final FakeContext ctx = new FakeContext();

    CutStalksTest() {
        ctx.percepts.position = new Pos(-6, G + 1, 0);
    }

    private void column(int x, int z, int height, dev.luizloyola.anima.core.brain.knowledge.BlockKind kind) {
        for (int y = G + 1; y <= G + height; y++) {
            ctx.percepts.blocks.set(x, y, z, kind);
        }
    }

    private static Task inside(Task step) {
        return assertInstanceOf(Try.class, step).attempt();
    }

    private static Pos at(GoTo walk) {
        String[] xyz = walk.describe().replaceAll("[^0-9, -]", "").trim().split(",\\s*");
        return new Pos(Integer.parseInt(xyz[0].trim()), Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim()));
    }

    @Test
    void caneIsCutOnceAColumnAboveItsBottom() {
        column(1, 0, 3, Patches.SUGAR_CANE);
        column(2, 1, 1, Patches.SUGAR_CANE);
        column(0, 2, 2, Patches.SUGAR_CANE);

        assertEquals(Set.of(new Pos(1, G + 2, 0), new Pos(0, G + 2, 2)),
                Set.copyOf(new CutStalks(Stalks.CANE, ANCHOR).cuts(ctx)),
                "one cut drops all above it; a column of one has nothing to give, and grows");
        List<Task> plan = new CutStalks(Stalks.CANE, ANCHOR).methods().get(0).decompose(ctx);
        assertInstanceOf(BreakBlock.class, inside(plan.get(1)));
        assertEquals(Stalks.CANE_ITEM, assertInstanceOf(GatherNearbyDrops.class, inside(plan.get(plan.size() - 1))).spec());
    }

    @Test
    void tallBambooIsCutOnceAboveTheBottom() {
        column(3, 0, 9, Patches.BAMBOO_STALK);
        assertEquals(List.of(new Pos(3, G + 2, 0)), new CutStalks(Stalks.BAMBOO, new Pos(3, G + 7, 0)).cuts(ctx));
    }

    @Test
    void aCactusIsCutFromClearOfItAndWhatFallsIsGrabbedFromClearOfItToo() {
        column(0, 0, 3, Patches.CACTUS);
        CutStalks cutting = new CutStalks(Stalks.CACTUS, ANCHOR);
        List<Task> plan = cutting.methods().get(0).decompose(ctx);

        Pos stand = at(assertInstanceOf(GoTo.class, inside(plan.get(0))));
        assertFalse(cutting.touches(ctx.percepts.blocks, stand), "never beside it: " + stand);
        assertTrue(Math.hypot(stand.x(), stand.z()) <= 4.5, "within the arm's reach: " + stand);
        assertInstanceOf(BreakBlock.class, inside(plan.get(1)));
        CutStalks.GrabClear grab = assertInstanceOf(CutStalks.GrabClear.class, inside(plan.get(plan.size() - 1)));

        ctx.percepts.drops = List.of(new Drop(new Pos(1, G + 1, 0), "minecraft:cactus", Region.of(new Pos(1, G + 1, 0))));
        List<Task> grabbing = grab.methods().get(0).decompose(ctx);
        assertEquals(1, grabbing.size());
        Pos near = at(assertInstanceOf(GoTo.class, inside(grabbing.get(0))));
        assertFalse(cutting.touches(ctx.percepts.blocks, near), "the drop lies beside the cactus; she does not");
        assertTrue(Math.max(Math.abs(near.x() - 1), Math.abs(near.z())) <= 1, "and within a block of it: " + near);
    }
}
